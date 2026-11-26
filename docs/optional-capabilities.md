# PlayTV 可选能力使用入口

项目可以按需增加更多能力。初始化选择为：`none`。这是初始化选择快照，后续真实启用情况以本项目 Gradle、Manifest、源码、组件目录和功能 Spec 为准。

## 区分三种交付

| 类型 | 怎样使用 |
|---|---|
| 默认基础 | 复用已生成的基础页面、MVI、组装和平台工具，实际 API 见 [组件目录](rule/ui-component-catalog.md) |
| 已注册可选包 | 初始化时显式选择才生成运行源码/SDK；已有工程后续需要时逐项接入，不重跑初始化器 |
| 参考材料 | 放在 docs/reference，提供组织方式和代码；不参加 Android 编译，不表示项目已启用相应功能 |

能力包是生成单元，文件按职责落在 app/base/model/support，不要求每项能力增加一个 Gradle 模块。归属见 [数据契约](rule/data-contract-rules.md)。

模板目前注册的可选包：refresh、network、image、sse、websocket、permissions、feedback、mmkv、preferences、room、reminders。feedback 按需交付公共 Loading、Toast、确认框与 debug 演练，不添加新第三方 SDK。SSE/WebSocket 均需显式同时选择 network；WebSocket 不自动连接或启动 Service，未选时不生成源码及网络状态权限。preferences 为兼容保留，不与 MMKV 自动双写同一数据。未选包不能按熟悉的类名直接调用；已选包的说明位于 docs/template-capabilities。

## 用到时再构建的参考

| 功能 | 资料 | 当前状态 |
|---|---|---|
| Android Service 与共享通知 | [源码与接入说明](reference/service/README.md)、[专题规则](rule/android-service-notification-rules.md) | 仅参考，尚未注册 service 能力；不默认添加服务、权限或运行初始化 |
| Bluetooth / BLE 与其他设备协议 | [分层与源码](reference/ble/README.md)、[新协议适配](reference/ble/protocol-adaptation.md) | 选填参考，未注册 ble 能力；含 debug 扫描/查看、GATT 队列/订阅、协议/会话接口和本地 peer，默认不添加权限、SDK、扫描或 Service |

后续参考按 docs/reference/<feature>/README.md 组织，说明用途、支持范围、文件位置、依赖/权限、组装、启动/停止、错误/取消及验证状态，并给明确代码。实际业务需要时才接入，没有需求写不涉及。

## 已有项目接入

1. 查 [公共基础](rule/common-foundation.md)、组件与依赖目录，判断已有实现、参考或缺失，避免重复生成。
2. 按真实需求补 Spec，明确配置、权限、所有者、初始化、业务启动、释放和恢复；参考不能代替业务决策。
3. 逐项接入代码、Gradle、Manifest、资源和依赖组装。当前初始化器不支持一键增量装卸；保留业务与历史 lock，按公共基线审阅迁移。
4. 登记真实可用 API 和依赖；区分源码交付、静态核对、构建与运行。授权后才执行构建/测试/运行。

多个能力共同修改 Manifest 或启动入口时，显式核对组合结果，不能假设文件覆盖会自动合并。模板更新不自动同步已有工程；没有需求的能力继续保持未接入。
