# PlayTV 依赖与启用能力

机器可读的真实声明在 [版本目录](../../gradle/libs.versions.toml)、根/四模块 Gradle 与 [settings](../../settings.gradle.kts)。初始化记录见 [.template-lock.json](../../.template-lock.json)，不引用外部原工程。

- 模板版本：0.4.0；能力：none；工具库：com.blankj:utilcodex:1.31.1。
- 播放器：`com.ai.media:playback-android`，仅声明在 app；通过相邻 `PlaybackSDK-playtv/build/repository` Maven 仓库解析。debug 打包入口 `build-playtv.ps1` 先发布相邻 SDK 当前源码，生成唯一版本供该次构建使用，并校验 APK 的 `arm64-v8a` 和 `armeabi-v7a` 库；具体命令与 Gradle 进程边界见 AGENTS。脚本要求相邻 SDK 工程及两个 ABI 的已准备开发库，不会自动下载缺失文件。SDK 候选最低 API 26，设备验证状态以功能记录为准。
- 初始化时 AAR `fb04deaa5f3bb147915dcf5da98bb74ffcb9b7500f303b90df20d4e95077453d` 是历史 arm64 产物，并非当前打包来源或验收证明。正式分发仍需独立版本和验收。
- 构建组合：AGP/Kotlin 见版本目录；Gradle 8.13、JDK 17、compile/target/min SDK 35/35/26。
- Gradle 版本采用 AGP 8.13 文档给出的默认/最低 8.13；并非复制源项目的 Wrapper 9.2.1。完整组合尚未构建验证。
- 仓库集中在 settings，FAIL_ON_PROJECT_REPOS；本工程采用 `aliyun` 仓库配置。默认 aliyun 按顺序声明阿里云 HTTP public、jcenter、HTTPS public、JitPack，再接 Google/Maven Central；none 仅保留公共来源及所选能力的必要来源。插件仓库按顺序为受分组限制的 google、Maven Central、Gradle Plugin Portal。
- 默认不携带火山引擎和未选用的厂商仓库；只有实际选择相应工具库/能力时才添加其声明来源，按 URL 去重，位置在 Google/Maven Central 之前。仓库条目不会自动添加对应 SDK。
- Gradle 分发来源：`aliyun`，地址 `https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.13-all.zip`；版本仍为 8.13，保留官方分发 SHA-256。Maven 镜像与 Wrapper 下载源独立，地址声明不代表已下载或构建通过。
- 模板显式声明实际使用的 ConstraintLayout、RecyclerView、Activity、Fragment，不能依赖偶然的传递依赖。model 没有 UI 栈/Binding，base 没有上层工程依赖。
- 列表统一 AndroidX ListAdapter/DiffUtil/ViewBinding，包括多 viewType；禁止引入 MultiType 或自建大型适配器框架。
- network/image/sse/permissions/mmkv/preferences 按能力配置生成真实源码与依赖；新项目 KV 推荐 mmkv，preferences 保留旧 DataStore 兼容；Room、支付、定位、蓝牙、语音、RTC 等未接入，不能按参考项目 API 调用。
- 本项目未启用 refresh；没有 kernel/header/footer 依赖，不按其他项目的类名调用刷新容器。

## 实际声明的依赖仓库（按声明顺序）

后续网络/图片能力的默认方案见本文末尾，以下清单只反映本次实际生成的依赖，不因存在规则就视为已启用。

- 本地 PlaybackSDK Maven：固定 `../PlaybackSDK-playtv/build/repository`，仅 `com.ai.media` 使用。
- 阿里云 HTTP public: `http://maven.aliyun.com/nexus/content/groups/public/`（显式允许 HTTP）
- 阿里云 jcenter: `http://maven.aliyun.com/nexus/content/repositories/jcenter`（显式允许 HTTP）
- 阿里云 HTTPS public: `https://maven.aliyun.com/repository/public`
- JitPack: `https://jitpack.io`
- `google()`
- `mavenCentral()`

## 实际生成的库

- `androidx-activity`: `androidx.activity:activity:1.10.1`
- `androidx-appcompat`: `androidx.appcompat:appcompat:1.7.1`
- `androidx-constraintlayout`: `androidx.constraintlayout:constraintlayout:2.2.1`
- `androidx-core-ktx`: `androidx.core:core-ktx:1.16.0`
- `androidx-fragment`: `androidx.fragment:fragment:1.8.6`
- `androidx-lifecycle-runtime-ktx`: `androidx.lifecycle:lifecycle-runtime-ktx:2.8.6`
- `androidx-lifecycle-viewmodel-ktx`: `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6`
- `androidx-recyclerview`: `androidx.recyclerview:recyclerview:1.4.0`
- `kotlinx-coroutines-android`: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
- `kotlinx-coroutines-core`: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3`
- `material`: `com.google.android.material:material:1.12.0`
- `playback-android`: `com.ai.media:playback-android`（debug 打包时由入口指定唯一版本；默认坐标仅用于非打包解析）
- `toolkit`: `com.blankj:utilcodex:1.31.1`

2026-11-26 执行 `:app:assembleDebug --no-daemon --offline` 成功，APK SHA-256 `9c42f73f076bca13ff875bf16bf29abfffc2694281ea4389ec00257872c69bb9`，安装到 vivo V2244A / Android 14 成功；未运行播放或 TV 操作测试。构建提示三个 native 库未执行 strip，按原样打包。本次 wrapper PID 20236 和单次 daemon PID 15124 均已退出。依赖来源和镜像长期可用性、其他设备兼容仍需另行验证；没有新增测试/统计 SDK，也没有嵌入签名和凭据。

## 公共基础与能力状态

- `network`：已接入模板源码及 OkHttp 4.12.0、Retrofit/converter-gson 2.9.0、Gson 2.10.1；debug 地址解析调用。
- `image`：未启用，不生成其源码或 SDK 依赖。
- `sse`：未启用，不生成其源码或 SDK 依赖。
- `websocket`：未启用，不生成其源码或 SDK 依赖。
- `permissions`：未启用，不生成其源码或 SDK 依赖。
- `feedback`：已接入模板 base 公共源码及资源，使用既有 AndroidX 并声明 lifecycle-runtime-ktx，无新增第三方 SDK。
- `mmkv`：未启用，不生成其源码或 SDK 依赖。
- `preferences`：未启用，不生成其源码或 SDK 依赖。
- `room`：未启用，不生成其源码或 SDK 依赖。
- `reminders`：未启用，不生成其源码或 SDK 依赖。

基础源码和扩展方式见 [公共基础](common-foundation.md)，SDK 的实际版本以上方本次生成的库清单为准。network 使用 OkHttp 4.12.0、Retrofit/converter-gson 2.9.0、Gson 2.10.1；image 使用 Glide 4.16.0；sse 使用 okhttp-sse 4.12.0；mmkv 使用 com.tencent:mmkv:1.3.14（原生库）；preferences 固定 Preferences DataStore 1.1.1；permissions 使用现有 AndroidX，不额外引入权限 SDK。feedback 使用现有 AndroidX/AppCompat，并显式声明 base 的 lifecycle-runtime-ktx，不新增第三方 SDK；是否交付以本次能力选择为准。

network 的 OkHttp/Retrofit/Gson 因公开 SDK 配置和扩展点使用 api，converter-gson 为 implementation；model 显式声明 DTO/Service 的 Gson/Retrofit，app 的网络组装显式声明 Gson。sse/websocket/preferences 公开 Flow 时 coroutines-core 使用 api，未启用时不扩散；websocket 复用 OkHttp 并在 support 使用 coroutines-android implementation，不新增 SDK；仅启用时增加 ACCESS_NETWORK_STATE 普通权限，不自动连接。Glide、okhttp-sse、DataStore、MMKV 为 implementation。support 只在实际用基础诊断时依赖 base，base 不引用 support/model/app。

DTO 使用 @JsonModel 与 @SerializedName，提供定向 R8 规则；自定义反射入口另行保护，不整包 keep。Glide 无生成 API，不加 kapt/compiler。各能力无业务地址/凭据，不自动初始化或声明运行时敏感权限；network/image 添加 INTERNET 普通权限。

SDK 签名仅做源码核对；未运行 Gradle 解析、编译、混淆或设备验证。数据库、上传下载、账号、私有图片等不因依赖存在就自动具备。

本项目未启用 room，没有 Room SDK、数据库工厂或同步器源码。需要时先按数据库与同步规则补齐能力。

本项目未启用 reminders，未生成提醒平台入口及对应权限声明。
