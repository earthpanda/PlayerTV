param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$sdkDir = [IO.Path]::GetFullPath((Join-Path $projectDir '..\..\..\PlaybackSDK-playtv'))
$sdkRepository = Join-Path $projectDir 'sdk-maven'
$sdkManifest = Join-Path $sdkRepository 'playback-sdk.json'
$sdkHelper = Join-Path $projectDir 'tools\playback_sdk_repository.py'
$logDir = Join-Path $projectDir 'build\verification\packaging'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$runId = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
$version = '0.1.0-dev-' + $runId

function Wait-GradleIdle {
    do {
        $active = @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'GradleDaemon|GradleWrapperMain'
        })
        if ($active.Count -gt 0) {
            Write-Host "Waiting for another Gradle build (PID $($active.ProcessId -join ', '))"
            Start-Sleep -Seconds 5
        }
    } while ($active.Count -gt 0)
}

function Invoke-CheckedGradle([string]$directory, [string[]]$arguments, [string]$label) {
    Wait-GradleIdle
    $wrapper = Join-Path $directory 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) { throw "Missing Gradle wrapper: $wrapper" }
    $outLog = Join-Path $logDir "$runId-$label.log"
    $errLog = Join-Path $logDir "$runId-$label-error.log"
    $argumentLine = '/d /c ""' + $wrapper + '" ' + ($arguments -join ' ') + '"'
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList $argumentLine -WorkingDirectory $directory `
        -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
    # Retain the process handle so Windows PowerShell can read ExitCode after exit.
    $null = $process.Handle
    $owned = @{}
    $owned[[int]$process.Id] = $true
    $observed = @{}
    $completed = $false
    $retry = $false
    try {
        while (-not $process.HasExited) {
            $all = @(Get-CimInstance Win32_Process)
            do {
                $found = $false
                foreach ($child in $all) {
                    if ($owned.ContainsKey([int]$child.ParentProcessId) -and -not $owned.ContainsKey([int]$child.ProcessId)) {
                        $owned[[int]$child.ProcessId] = $true
                        $observed[[int]$child.ProcessId] = $child
                        $found = $true
                    }
                }
            } while ($found)
            $other = @($all | Where-Object { $_.Name -match '^java(w)?\.exe$' -and
                $_.CommandLine -match 'GradleDaemon|GradleWrapperMain' -and
                -not $owned.ContainsKey([int]$_.ProcessId) })
            if ($other.Count -gt 0) {
                Write-Host "Another Gradle build started (PID $($other.ProcessId -join ', ')); stopping this build and waiting."
                $retry = $true
                break
            }
            Start-Sleep -Milliseconds 500
            $process.Refresh()
        }
        if (-not $retry) { $process.WaitForExit() }
        if (-not $retry -and $process.ExitCode -ne 0) {
            Get-Content -LiteralPath $outLog -Tail 80
            Get-Content -LiteralPath $errLog -Tail 80
            throw "$label failed with exit code $($process.ExitCode). Logs: $outLog"
        }
        $completed = -not $retry
    } finally {
        foreach ($id in $observed.Keys) {
            $previous = $observed[$id]
            if ($completed -and ($previous.Name -notmatch '^java(w)?\.exe$' -or $previous.CommandLine -notmatch 'GradleDaemon|GradleWrapperMain')) { continue }
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$id"
            if ($current -and $current.CreationDate -eq $previous.CreationDate -and $current.CommandLine -eq $previous.CommandLine) {
                Stop-Process -Id $id
                Wait-Process -Id $id -ErrorAction SilentlyContinue
            }
        }
        if (-not $completed -and -not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
    }
    if ($retry) {
        Invoke-CheckedGradle $directory $arguments $label
        return
    }
    Write-Host "$label completed. Log: $outLog"
}

$offlineArgs = if ($Offline) { @('--offline') } else { @() }
$manifest = $null
if (Test-Path -LiteralPath $sdkManifest) {
    $manifest = Get-Content -Raw -LiteralPath $sdkManifest | ConvertFrom-Json
}
$sdkExists = Test-Path -LiteralPath $sdkDir -PathType Container
$needsPublication = $true
if ($sdkExists) {
    Write-Host 'Checking PlaybackSDK source and prepared inputs'
    $sdkFingerprint = & python -X utf8 $sdkHelper fingerprint $sdkDir
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect local PlaybackSDK inputs.' }
    if ($manifest -and $manifest.sdkFingerprint -and $manifest.sdkFingerprint -eq $sdkFingerprint) {
        $needsPublication = $false
    }
} else {
    $needsPublication = $false
    Write-Host 'Local PlaybackSDK is absent; using the publication in PlayTV.'
}
if ($needsPublication) {
    Write-Host "Publishing current PlaybackSDK as $version"
    Invoke-CheckedGradle $sdkDir (@(':android:publishReleasePublicationToLocalSdkRepository', '--no-daemon', "-PsdkPublicationVersion=$version") + $offlineArgs) 'sdk'
    $afterFingerprint = & python -X utf8 $sdkHelper fingerprint $sdkDir
    if ($LASTEXITCODE -ne 0 -or $afterFingerprint -ne $sdkFingerprint) { throw 'PlaybackSDK inputs changed during publication.' }
    & python -X utf8 $sdkHelper install $sdkDir $version --fingerprint $sdkFingerprint
    if ($LASTEXITCODE -ne 0) { throw 'Unable to update the PlayTV SDK publication.' }
    $manifest = Get-Content -Raw -LiteralPath $sdkManifest | ConvertFrom-Json
} else {
    if (-not $manifest) { throw 'Missing SDK publication in PlayTV; prepare PlaybackSDK and run this script again.' }
    $version = [string]$manifest.version
    Write-Host "Reusing PlayTV SDK publication $version"
}
if ($version -notmatch '^[A-Za-z0-9.-]+$') { throw 'Invalid SDK publication version.' }
$publicationDir = Join-Path $sdkRepository "com\ai\media\playback-android\$version"
foreach ($suffix in @('aar', 'pom', 'module')) {
    $required = "playback-android-$version.$suffix"
    if (-not $manifest.files.PSObject.Properties[$required]) { throw "Incomplete SDK publication: $required" }
}
foreach ($item in $manifest.files.PSObject.Properties) {
    if ([IO.Path]::GetFileName($item.Name) -ne $item.Name) { throw 'Invalid SDK publication file name.' }
    $file = Join-Path $publicationDir $item.Name
    if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -ne $item.Value) {
        throw "SDK publication missing or changed: $($item.Name)"
    }
}
Write-Host 'Building PlayTV with -DTest=true'
Invoke-CheckedGradle $projectDir (@(':app:assembleDebug', '--no-daemon', '-DTest=true', '-PtestWebmCandidate=true', "-PplaybackSdkBuildVersion=$version") + $offlineArgs) 'app'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$aarPath = Join-Path $publicationDir "playback-android-$version.aar"
$apkPath = Join-Path $projectDir 'app\build\outputs\apk\debug\app-debug.apk'
$aar = [IO.Compression.ZipFile]::OpenRead($aarPath)
$apk = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a')) {
        foreach ($name in @('libc++_shared.so', 'libgstreamer_android.so', 'libplayback_native.so')) {
            $source = $aar.GetEntry("jni/$abi/$name")
            $target = $apk.GetEntry("lib/$abi/$name")
            if (-not $source -or -not $target) { throw "Missing $abi/$name in SDK or APK" }
            $hash = [Security.Cryptography.SHA256]::Create()
            try {
                $s = $source.Open(); try { $a = [BitConverter]::ToString($hash.ComputeHash($s)) } finally { $s.Dispose() }
                $s = $target.Open(); try { $b = [BitConverter]::ToString($hash.ComputeHash($s)) } finally { $s.Dispose() }
                if ($a -ne $b) { throw "SDK/APK mismatch for $abi/$name" }
            } finally { $hash.Dispose() }
        }
    }
} finally { $aar.Dispose(); $apk.Dispose() }
Write-Host "Verified SDK $version in $apkPath"
