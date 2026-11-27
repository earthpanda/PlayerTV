plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false

}

// Packaging must use the SDK selected by the checked packaging entry point.
val playbackSdkBuildVersion = providers.gradleProperty("playbackSdkBuildVersion")
val playbackSdkManifest = rootProject.file("sdk-maven/playback-sdk.json")
val bundledSdkVersion = if (playbackSdkManifest.isFile) {
    (groovy.json.JsonSlurper().parse(playbackSdkManifest) as Map<*, *>)["version"] as? String
} else null
val packageRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').matches(Regex("(?i)(assemble|bundle|install|package)(debug|release)?|build"))
}
if (packageRequested && !playbackSdkBuildVersion.isPresent) {
    throw GradleException("Use .\\build-playtv.ps1 to select the PlaybackSDK and build PlayTV.")
}
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.ai.media" && requested.name == "playback-android") {
                (playbackSdkBuildVersion.orNull ?: bundledSdkVersion)?.let { useVersion(it) }
            }
        }
    }
}
