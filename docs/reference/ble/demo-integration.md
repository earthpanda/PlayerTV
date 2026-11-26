# BLE 演练源码接入

本轮在扫描/服务查看之外补齐传输、可替换协议、会话与本地 peer 的接口链路。**依旧是按需参考交付**：没有注册 ble capability；资料生成在 docs，不会因为创建新项目就编译或启用蓝牙。用户明确要求 BLE 演练时才按以下清单采用。设备协议未知时，页面真实链路只做服务发现，不能把夹具当 Sony 实测。新设备接入以 [协议扩展指南](protocol-adaptation.md) 为入口。

## 文件映射与组装

以本目录 `demo-files/` 为源，移除该前缀，将其下 base/model/app/docs 路径逐项放入目标工程同名位置；当前文件已按目标包名渲染。保留所有 `src/debug`，不要为了“通用”移动到 main。开始前阅读 [演练 Spec](demo-files/docs/feature/bledemo/test-ble-demo.md)，实际接入后的 Spec 在 docs/feature/bledemo。

| 资料路径（demo-files 下） | 接入职责 |
|---|---|
| base/src/debug/java/com/ai/playtv/base/bluetooth/ble/BleInspectionTypes.kt、BleScanner.kt、BleGattInspector.kt | 真实扫描/连接/服务查看、技术状态和清理 |
| base/src/debug/java/com/ai/playtv/base/bluetooth/ble/gatt/BleGattWriteGate.kt | 保留单写原语供理解；完整 transport 统一队列不额外使用该门闩 |
| base/src/debug/java/com/ai/playtv/base/bluetooth/ble/transport | BleTransport 契约与 AndroidBleTransport，MTU/CCCD/读写统一串行队列 |
| base/src/debug/java/com/ai/playtv/base/bluetooth/ble/frame、retry | 复用上级组帧、示例格式和退避，调整包名 |
| model/src/debug/java/com/ai/playtv/model/contract/bledemo、state/bledemo、manager/bledemo | 页面契约、夹具状态及本地任务 |
| model/src/debug/java/com/ai/playtv/model/protocol/ble、protocol/ble/example | 可替换设备协议、完整教学协议 codec 与握手 |
| model/src/debug/java/com/ai/playtv/model/manager/ble、state/ble | 实际会话/请求 broker、只读阶段和带代次事件 |
| app/src/debug/java/com/ai/playtv/ui/test/bledemo | Activity、ViewModel、设备列表；Factory 注入 page-owned 实例 |
| app/src/debug/res/layout、values、values-en | 纯文字技术页面与双语资源，无设计资产 |
| docs/feature/bledemo/test-ble-demo.md | 实际功能边界与手动验收依据 |

模板技术栈固定 app/base/model/support，当前这些文件需要目标已有 app→base/model、model→base，coroutines、Core、RecyclerView、ViewModel、MVI，以及已选择的 permissions/feedback 实现。缺实现先按对应可选包完整接入，不猜 API、不新增另一个权限/Toast 管理器。本轮不新增第三方 SDK、版本或 Gradle 插件。

将 [debug Manifest 片段](demo-manifest.xml) **合并**到目标 app/src/debug/AndroidManifest.xml；不能覆盖旧 Activity/Service/权限。neverForLocation 仅适用本演练明确不推导位置的用途，可能过滤某些 beacon。release 不增加声明。

在已有 debug 验证目录加文字入口，显式打开 `com.ai.playtv.ui.test.bledemo.BleDemoActivity`；该 Activity exported=false，仅供应用内部进入。清理该入口监听，更新真实功能索引/组件目录与采用记录。不要修改 release 启动页或全局 Application 初始化。

页面 onResume 首次只申请 List 权限，点击扫描再用真实 API。选择设备后扫描停止，依次 CONNECTING→DISCOVERING→DISCOVERED；后者只表示发现服务。离开、设置、旋转会停止本页运行，返回不自动恢复。四个夹具入口无需设备或授权，始终单独标明本地模拟；没有实际向未知端点写命令。

## 后续真设备协议要补什么

Inspector 保持未知设备只读查看。需要真实收发时，按协议扩展指南组合 AndroidBleTransport、设备自己的 BleDeviceProtocol 和 BleDeviceSessionManager；队列、订阅、响应关联和重连调度已有源码参考。须填真实端点/协议/安全策略并确认限制，之后授权设备验证。音频、经典蓝牙和后台 Service 另按需求选填，页面演练的重连仍是本地 peer 断线，不是无线验收。

本例能比较模板的生成组织和原语是否接上，不能验证未知设备兼容性。没有 Android 编译/自动测试/真机证明；遵循项目 AGENTS 的工具授权边界，再执行相应验证。
