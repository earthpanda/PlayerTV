# 实现与按需参考入口

按 [AGENTS](../../AGENTS.md) 的查找顺序使用本页。先读本项目组件目录及真实源码；下面的先例用于理解职责、所有者和错误处理，不覆盖现行规则，也不提供当前业务需求。

| 常用说法/问题 | 入口与实际范围 |
|---|---|
| Codex、Ponytail 安装、按需代码审查、工具规则冲突 | [Ponytail 技能接入](codex-ponytail.md)；固定来源/版本与显式调用，开发环境工具，生成器不安装、不作为 Android 依赖 |
| MVI、列表加载、取消、失败保留内容、依赖注入 | [样例 Spec](../feature/sample/test-sample.md)、[ViewModel](../../app/src/main/java/com/ai/playtv/ui/test/sample/SampleViewModel.kt)、[Repository](../../model/src/main/java/com/ai/playtv/model/repository/sample/SampleRepository.kt)、[组合根](../../app/src/main/java/com/ai/playtv/di/AppContainer.kt)；已生成的本地技术样例，不是生产协议，也不代表运行已通过 |
| 请求/图片、KV、Room 同步、权限、反馈等已有能力 | [组件目录](../rule/ui-component-catalog.md)、[可选能力](../optional-capabilities.md)；以启用状态和真实源码为准，未启用不猜 API |
| 多个 Service、共用通知、分别停止 | [Service 参考及 Kotlin](service/README.md)；位于 docs，不参与 Android 编译，不是已启用的后台能力 |
| 蓝牙/BLE、传输与协议分离、连接/重连 | [BLE 架构](ble/README.md)、[新协议适配](ble/protocol-adaptation.md)；文档与按需源码参考，不自动接入设备协议 |
| 公共 Loading、Toast、确认弹窗的查找与登记 | [反馈登记示例](ui-feedback/README.md)；实际组件仍查组件目录 |
| Android 原生媒体内核、GPU 纹理/Shader 处理标准、软硬解与线程边界 | [播放 SDK 接入及源码示例](media/README.md)；硬解优先 → GPU 纹理 → Shader → 渲染，独立 SDK 按需接入，不注册默认能力、不预初始化 |

项目新增有代表性的实现，在本表登记问题、真实入口、适用边界和验证状态，不复制整份业务代码到模板。外部参考路径缺失如实报告；独立生成项目不依赖 Sony 或其他业务工程可访问。无可用先例时按现行规则实现职责明确的局部逻辑；涉及尚未决定的业务或架构取舍再提出讨论。
