# PlayTV

独立 Android TV 播放器项目。工程现为模板技术预览，尚未实现 TV 播放页面或 GitHub/M3U/M3U8 播放；应用清单声明 TV 启动类别和触屏可选，电视桌面 banner、焦点交互及真实设备验证仍待完成。

应用已声明 PlaybackSDK 的本地 Maven 坐标 `com.ai.media:playback-android:0.1.0`，默认仓库为 `../PlaybackSDK/build/repository`，可通过 Gradle 属性 `playbackMavenRepository` 指定其他本地仓库。本机初始化核对的 AAR 摘要及设备限制见 [依赖目录](docs/rule/build-dependency-catalog.md)。debug 包已在本机解析并构建、安装到一台 Android 手机；播放器代码尚未调用，TV 播放与遥控器行为尚未验证。播放内核的研发、源码和 WIP 继续由独立 PlaybackSDK 仓库维护。

由本地 `android-xml-mvi` 模板 **0.4.0** 生成。实际配置、文件来源和摘要见 [.template-lock.json](.template-lock.json)。AI 先读 [AGENTS.md](AGENTS.md)。

开发准则由本项目独立维护，入口为 [项目基线](docs/rule/project-context.md)。原型用于首次形成准则；后续开发读取本项目自己的规则，按本项目技术和业务决策演进，不自动跟随原型更新。

公共源码和使用契约见 [公共基础](docs/rule/common-foundation.md)，当前能力查 [组件目录](docs/rule/ui-component-catalog.md)，公共源码版本/摘要记录在 [.foundation-baseline.json](.foundation-baseline.json)。后续复用标准实现，地址、协议和业务策略由项目接入；没有新增完整网络功能页。

推荐流程是用户先用 Android Studio 创建默认工程，再由 AI 读取本地模板库、检查默认代码并接入。若本项目按该流程初始化，lock 的 `android_studio_adoption` 记录原工程信息、改动清单与 `.template-backups/<批次>/backup-manifest.json` 位置；原配置与默认源码保存在该目录。现有 `.git`、`.idea`、`local.properties` 保留，构建采用下述模板基线，不默认沿用 AS 自带版本。也支持向空目录生成，届时没有 AS 备份记录。

- applicationId：`com.ai.playtv`
- 模块 namespace：`com.ai.playtv`、`com.ai.playtv.base`、`com.ai.playtv.model`、`com.ai.playtv.support`
- 设计宽度：以根目录 [design-config.properties](design-config.properties) 的 `design.width` 为准，普通页面使用 pt，XML 和代码共用适配入口。
- 启用能力：`none`；工具库为 `com.blankj:utilcodex:1.31.1`。
- 构建声明：AGP/Kotlin 等实际值见版本目录，Gradle 8.13，JDK 17，SDK 35/35/26。
- Gradle 分发来源：aliyun，下载地址 `https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.13-all.zip`；Wrapper 保留 SHA-256 校验。Maven 仓库及顺序见依赖目录，厂商仓库按所选能力配置。

工程包含完整四模块脚本、版本目录、Gradle Wrapper、MVI 基类、页面状态、AndroidX 列表和本地样例。启动页是 `ui.test.sample.SampleActivity`，对应 [技术样例 Spec](docs/feature/sample/test-sample.md)，不代表真实业务已完成。没有设计文件、后端地址、登录、数据库、品牌图标或生产签名配置；网络/图片 SDK 随所选能力生成。

设计宽度允许留空：技术样例按系统 dp 基准适配，不代表设计宽度已确认。正式页面开发前，将 `design-config.properties` 的 `design.width` 填为设计画板的实际逻辑宽度（整数 100–2000）。修改后重新构建并重启生效，不重跑初始化器、不改 Kotlin；非法值在 Gradle 配置时报错。该文件随项目版本管理；`.template-lock.json` 的配置仅记录初始化历史。具体单位约定见 [项目约定](docs/rule/project-conventions.md)。

用 Android Studio 打开本目录，配置本机 Android SDK 和 JDK 17。也可由用户明确授权后执行 `gradlew.bat :app:assembleDebug`（Windows）或 `./gradlew :app:assembleDebug`（Unix）。生成器没有执行这些命令；Gradle 分发包和 Maven 依赖首次使用仍需网络或已有缓存，本地模板不是完整离线依赖仓库。

普通版本的样例用文字操作刷新和加载下一页；启用 refresh 时使用安全容器与经典刷新头/底部，数据均为固定的本地 3 页。样例不申请权限、不下载图片，也没有用生成资产替代正式设计。

制作第一个正式功能前，根据真实需求确认设计与数据协议，创建正式 Spec 并设置真实启动页。发布前提供用户的品牌资源、签名与所选能力配置。模板没有代替这些业务决策。

后续开发规则已随工程交付：[业务与异步状态](docs/rule/business-development-rules.md)、[Manager 与作用域](docs/rule/manager-lifecycle-rules.md)、[文件/KV/数据库](docs/rule/file-storage-rules.md)、[权限](docs/rule/permission-rules.md)、[UI 生命周期](docs/rule/android-ui-lifecycle-rules.md)、[工具与扩展](docs/rule/common-capability-rules.md)。AGENTS 按任务触发阅读，Spec 记录本功能的具体选择；无需访问来源工程。这些文档规定写法，真实可用 API 以组件目录、能力说明和源码为准。

后续选填功能从 [可选能力入口](docs/optional-capabilities.md) 查看，区分已注册可选包与按需参考；没有需求时保持未接入。

能力版本、仓库和验证状态见 [依赖目录](docs/rule/build-dependency-catalog.md)。未交付或未启用的功能以组件目录和实际源码为准，不能仅按参考项目 API 调用。模板更新不会自动覆盖生成项目；迁移时按公共基线核对本地差异，保留历史 lock。

接入中断时先按备份清单核对恢复，生成器不提供自动回滚或重复覆盖。备份目录已加入忽略规则，保留到用户确认不再需要；不要让后续 AI 自动删除它。
