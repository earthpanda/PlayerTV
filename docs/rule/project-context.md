# PlayTV 项目基线与准则维护

本文件记录本项目采用的方案、适用边界和待确认事项。日常开发从 [AGENTS](../../AGENTS.md) 进入；具体规则均在本项目 docs/rule 中维护，不需要读取生成原型或其他应用工程。

## 已确定的项目选择

| 事项 | 本项目选择与核对入口 |
|---|---|
| 项目身份 | 工程 PlayTV；applicationId `com.ai.playtv`；app namespace `com.ai.playtv` |
| 技术栈 | Kotlin、XML/ViewBinding、自定义 MVI、AndroidX 列表；见 [项目约定](project-conventions.md) |
| 网络与图片默认方案 | 请求链路见 [网络请求](network-request-rules.md)，图片绑定见 [图片加载](image-loading-rules.md)，类的目录查数据契约；选型与真实交付状态查依赖/组件目录，不能把规则当作已接入 |
| 模块 | app/base/model/support；职责及允许依赖方向见 [数据契约](data-contract-rules.md)，已声明的边以四模块 Gradle 为准 |
| 构建、仓库与下载源 | 读取 [依赖目录](build-dependency-catalog.md)、[settings](../../settings.gradle.kts)、[Wrapper](../../gradle/wrapper/gradle-wrapper.properties) 和 [版本目录](../../gradle/libs.versions.toml)，不用其他工程配置覆盖实际值 |
| 公共基础 | 采用 0.4.0，当前源码/摘要见 [.foundation-baseline.json](../../.foundation-baseline.json) 和 [公共基础](common-foundation.md)；基础变更有版本和本地差异记录 |
| 初始化选择的能力 | `none`；工具库 `com.blankj:utilcodex:1.31.1`。后续可用能力以 [组件目录](ui-component-catalog.md) 和源码为准，初始化 lock 不追记当前功能 |
| 设计宽度 | [design-config.properties](../../design-config.properties) 是唯一配置入口；开发前读取当前值，不能从原型示例或其他应用推断 |
| 当前入口 | [SampleActivity](../../app/src/main/java/com/ai/playtv/ui/test/sample/SampleActivity.kt) 技术预览；正式入口随真实功能确定 |
| 当前需求依据 | [功能索引](../feature/README.md) 与具体 Spec；技术样例不是已确认的产品需求 |
| 产品方向 | 独立 Android TV 播放器，遥控器操作；播放内核使用独立 PlaybackSDK，不在 JustSoSo 内开发电视页面 |
| 首要播放来源 | GitHub 视频或 Release 地址、GitHub 上的 M3U 列表及列表中的 M3U8 直播/点播；功能尚未实现 |

## 适用边界与待确认

- 当前预览仅演示本地列表和 MVI，没有预设账号或后端协议；公共文件操作与按需启用能力以组件目录为准。是否启用刷新以本项目实际能力和源码为准。
- 已声明 PlaybackSDK 本地 Maven 依赖并完成本机 debug 构建；2026-11-26 安装到 vivo V2244A / Android 14 成功，尚未调用播放器或验证 TV 行为。多段缓存 WIP 不作为本项目的默认源码输入，版本和摘要见依赖目录。
- 目标电视设备、遥控器、系统版本和 ABI 尚未确认；目前 SDK 历史产物仅提供 arm64-v8a、minSdk 26，不能据此声称兼容全部电视盒子。
- 技术预览声明了 TV 启动类别及触屏可选，但尚无电视品牌 banner、正式焦点设计或设备验证，不能宣称电视桌面入口已可用。
- 产品业务、接口成功判定、真实分页、设计资源、权限场景、数据保留与账号体系按后续需求确定；不从原型或参考应用继承业务假设。
- Manager、存储、权限等规则规定新增实现的写法，不等于已交付同名工具。功能需要时根据真实依赖和生命周期实现，再登记组件目录。
- 构建、运行、测试与静态核对分开记录；初始化文件存在不等于已完成 Android 验证，实际状态记录在对应 Spec/依赖目录。

## 项目准则的生命周期

1. **首次形成**：从原型提取适用规则，替换项目身份和路径，核对源码、构建、Manifest、资源与能力；保留已确认选择，把未知业务明确列为待确认。不能只替换包名就声称已经适配项目。
2. **独立维护**：生成后这些文档归本项目。日常开发遵循本项目 AGENTS 和规则，原型后续更新不自动覆盖本项目；其他项目的同名文件也不是本项目规则来源。
3. **随决策演进**：项目确认新的全局方案后，按 AGENTS 的规则维护授权更新唯一对应文档；单个功能的需求和差异写入功能 Spec。接口/业务不同可以有不同准则，不为保持与原型一致强行改代码。
4. **发现差异**：源码/Gradle 描述现状，规则描述约定，Spec 描述功能决策。三者冲突先识别有意例外还是实现偏差，沿用已有授权处理；业务信息不足时只询问必要决策，不默默改规则或倒推新需求。
5. **借鉴更新**：需要采用原型的新规则时，逐项比较本项目当前内容、实际源码与新规则，保留本项目决策，记录变更；不重跑初始化器，不重写 `.template-lock.json` 的历史摘要。

一项事实只维护在一个权威位置：模块/类型归属在数据契约，实际依赖在 Gradle/依赖目录，可用能力在组件目录，功能行为在对应 Spec。本文件只记录项目选择和适用边界，并链接这些位置。
