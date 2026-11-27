# PlayTV

PlayTV 是独立的 Android TV 播放器工程，应用 ID 为 `com.ai.playtv`，最低支持 Android 8（API 26）。本仓库拥有独立的 Gradle Wrapper 和 `app`、`base`、`model`、`support` 模块。

当前 debug 包提供媒体来源输入与播放验证页面；GitHub、HTTPS 地址和播放列表等路径仍在逐步验证。已验证的设备、来源和未完成的回归见 [播放验证记录](docs/feature/test-tv-playback.md)，不能将测试页面视为完整发布版本。

## 构建 PlayTV

用 Android Studio 打开本仓库，配置 JDK 17 和 Android SDK 35。工程使用 Gradle Wrapper；首次构建需要下载 Gradle 和相关依赖，或已有可用缓存。

在 Windows 的项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-playtv.ps1
```

离线构建可加 `-Offline`。该入口构建 debug 包、启用 `-DTest=true`，并对照 APK 与 SDK AAR 中的 32 位和 64 位原生库。直接 Gradle 打包或 Android Studio 默认 Run 会提示使用该入口；安装时使用本次入口成功生成的 APK。Linux/macOS 的等效打包入口尚未提供。

## 播放 SDK

工程内的 `sdk-maven/` 保存了构建所需的 PlaybackSDK Maven 产物。没有本地 SDK 源码时，打包脚本直接使用这份产物。

如需修改 SDK，可将其仓库克隆到 PlayTV 仓库的同级目录，目录名须为 `PlaybackSDK-playtv`：

```powershell
git clone --branch codex/playtv-network https://gitee.com/lin-dream/playback-sdk.git PlaybackSDK-playtv
```

打包时，脚本检查本地 SDK 源码（包括未提交和未跟踪文件）及其准备输入：没有变化时复用 PlayTV 内的产物；有变化时重新发布，并覆盖 PlayTV 内的 Maven 产物。SDK 检查或发布失败时会报错。更新后的产物需与 PlayTV 一起提交，其他人才能获取。

重新构建 SDK 需要 Python、Android NDK r29、两个 ABI 的开发库及 SDK 工程的本机配置。具体环境以 SDK 仓库的 README 为准；在 SDK 目录可执行以下命令准备输入：

```powershell
python -X utf8 tools/prepare_upstream.py --sources
python -X utf8 tools/prepare_upstream.py --android --abi arm64 --abi armv7
```

## 安装到设备

成功构建的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。设备启用 ADB 并连接后，在项目根目录使用实际设备序列号安装：

```powershell
adb devices -l
$deviceSerial = '在此填写设备序列号'
adb -s $deviceSerial install -r .\app\build\outputs\apk\debug\app-debug.apk
```

安装命令返回 `Success` 表示安装成功。调试包的签名由本机生成；如果设备上的已有版本签名不同，覆盖安装会失败，应先确认处理方式。其他项目可通过应用 ID `com.ai.playtv` 识别已安装的 PlayTV；安装和实际播放能力需要分别验证。

## 开发资料

- [项目工作规则](AGENTS.md)
- [播放验证记录](docs/feature/test-tv-playback.md)
- [播放器开发计划](docs/plans/playtv-player-v1-plan.md)
- [播放器能力协作](docs/collaboration/player-ui-capability.md)
- [依赖与构建配置](docs/rule/build-dependency-catalog.md)
