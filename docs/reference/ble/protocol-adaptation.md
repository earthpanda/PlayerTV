# 接入另一种 BLE 设备协议

本页供业务项目和后续 AI 独立使用，不依赖 Sony 源码。先读 [总入口](README.md)、[接入映射](demo-integration.md) 与目标项目的数据契约/Manager/权限规则，再读下列真实源码。BLE 仍是选填参考，没有注册 ble capability；代码留在 docs，明确需要时逐项采用，不运行初始化器覆盖已有工程。

## 已提供的接口和调用链

路径从 `demo-files/` 起算，所有运行示例均在 src/debug。

| 源文件 | 可以复用什么 | 新协议实现什么 |
|---|---|---|
| [BleTransport](demo-files/base/src/debug/java/com/ai/playtv/base/bluetooth/ble/transport/BleTransport.kt) | 平台中立的连接/写/读/通知契约 | BleGattProfile 中端点、属性模式、MTU/单包限制 |
| [AndroidBleTransport](demo-files/base/src/debug/java/com/ai/playtv/base/bluetooth/ble/transport/AndroidBleTransport.kt) | 原生 GATT；连接→发现→MTU→CCCD；同一队列串行发现/MTU/descriptor/read/write | 默认初始化顺序是否符合设备要求；特殊流程需显式适配 |
| [BleDeviceProtocol](demo-files/model/src/debug/java/com/ai/playtv/model/protocol/ble/BleDeviceProtocol.kt) | 命令、响应键、每连接 codec 和握手接口 | 实现 BleDeviceProtocol.newSession 和 BleProtocolSession 的所有方法 |
| [ExampleBleProtocol](demo-files/model/src/debug/java/com/ai/playtv/model/protocol/ble/example/ExampleBleProtocol.kt) | 完整 Hello/Ping 编码、分片、响应解析及握手样例 | 替换为真实协议，不能照用教学 UUID/操作码/校验 |
| [BleDeviceSessionManager](demo-files/model/src/debug/java/com/ai/playtv/model/manager/ble/BleDeviceSessionManager.kt) | 连接意图、Ready 门禁、响应等待、超时、重连、关闭 | 重连预算、期限和 owner；特殊关联/安全/流量策略 |
| [BleSessionSnapshot](demo-files/model/src/debug/java/com/ai/playtv/model/state/ble/BleSessionSnapshot.kt) | 只读阶段、代次、协商结果和错误语义 | 产品自己的设备信息类型实现 BleDeviceInfo |
| [ExampleBlePeer](demo-files/model/src/debug/java/com/ai/playtv/model/manager/bledemo/ExampleBlePeer.kt)、[BleProtocolFixture](demo-files/model/src/debug/java/com/ai/playtv/model/manager/bledemo/BleProtocolFixture.kt) | 不依赖硬件的同接口组合；分片/粘包、重复响应、重连、缺回调和旧连接回调 | 用真实设备的公开协议样本增加自己的 peer/夹具 |

调用链：app 组合根 → BleDeviceSessionManager → BleDeviceProtocol / BleProtocolSession → BleTransport → AndroidBleTransport。通知经每特征独立 assembler → decode → 响应关联或事件；不经 UI 解析。新设备通常新增 `model/protocol/<deviceFamily>/` 的协议、命令、设备信息和帧格式，继续使用同一传输与会话管理器。只有真实使用第三方 SDK 才在 support 增加适配。

## 开始编码前必须拿到的资料

1. 广播字段、服务/特征 UUID、读写属性、notify 或 indicate、CCCD、稳定设备身份。地址可能轮换，不能默认是持久 ID。
2. MTU 最低值/期望值、是否允许默认 MTU 降级、设备单包上限、长帧分片规则。不能假设任意帧都能按 MTU-3 切片。
3. 帧头/长度/大小端/转义/校验/坏帧重同步、最大完整帧、残帧期限。BleFrameFormat 只在完整且校验通过后返回 Complete。
4. 命令与响应格式、请求 ID 范围、响应操作码、ACK 与最终完成的区别、重复/乱序语义、主动上报事件。
5. 握手顺序、认证/加密/配对需求、真正 Ready 条件、可选查询失败能否降级。文档没有安全参数时不能编造。
6. 哪些命令幂等、结果不明如何查询、允许重试的链路错误、重连预算与稳定窗口、前后台 owner 和停止时机。

缺项记录到目标功能 Spec 并向需求方索取；可先复用 Scanner/Inspector 查看真实 GATT，但不能根据服务列表猜私有协议或发送教学命令。

## 照着示例接新协议

以 Acme 为**目录占位名**，不代表已支持设备：

1. 新增 `AcmeBleProtocol : BleDeviceProtocol`，profile 完全来自设备文档；newSession 每次返回新 codec，序号/密钥/握手状态不得跨连接复用。
2. 新增 `AcmeBleCommand : BleDeviceCommand` 和 `AcmeDeviceInfo : BleDeviceInfo`。不要把设备型号写入 base，勿把 BluetoothGatt 暴露到 model/app 页面。
3. 实现 frameFormat(endpoint)；多个通知特征各有独立有序字节流，不合并残片。实现 encode(id,command,maxWriteBytes)，显式分包或拒绝过长命令，不能依赖传输层擅自分包。
4. 实现 decode(endpoint,frame)，返回 Response(key,accepted,payload) 或 Event。响应键来自真实序号和操作码；本地写回调不构造 Response。
5. 实现 suspend handshake(exchange)，使用 exchange.execute 发真实初始化命令，校验返回字段并构建设备信息。无需握手也应明确返回能力事实；不能只因连接成功就返回 Ready。
6. 将 app 组合根的 `ExampleBleProtocol()` 替换为 `AcmeBleProtocol()`，注入相同 AndroidBleTransport/会话接口。UI 只观察 state、发命令、处理权限与反馈。
7. 给真实帧样本建立本地 peer，再授权验证 Android/硬件。新的认证、DFU 或高吞吐音频属于单独能力，不强塞普通请求队列。

参考 broker 在**一个连接内永不复用请求 ID**，单条命令在途，有界准入 8 条；ID 耗尽即退役，需业务显式开始新连接。响应等待者在首次写入前登记，重复/不匹配响应计数后忽略。无 ID、只有交替 1bit 序号、异步 ACK+最终完成、多并发响应的设备，必须先设计安全关联策略（包括迟到响应隔离），调整 broker/契约；不能把固定 ID 或立即回绕接到这个实现上。没有“所有协议只改 UUID”的承诺。

## 实际组装和调用

下面是 app 层组装片段，导入表中实际类型。**ExampleBleProtocol 只用于本地 peer 或明确实现该教学协议的外设**；不要对扫描列表中的任意设备调用此组合。真实产品替换为其已实现的设备协议。

```kotlin
// 由 app 的页面/功能容器在 Main 创建；parentScope 使用 Dispatchers.Main.immediate。
val session = BleDeviceSessionManager(
    parentScope = parentScope,
    transport = AndroidBleTransport(context.applicationContext),
    protocol = selectedDeviceProtocol, // 实现 BleDeviceProtocol，由产品明确选择。
    reconnectPolicy = BleReconnectPolicy(maxAttempts = 3, initialDelayMs = 1_000, maxDelayMs = 8_000),
)
// UI 宿主先通过项目已有 List 权限入口和蓝牙开关检查，用户触发后才调用：
session.connect(selectedAddress)
// state 到 READY 后，所属协程中才执行该协议定义的命令。
val response = session.execute(selectedDeviceCommand)
// 用户停止/页面离开（页面 owner）使连接意图失效，也取消重连：
session.disconnect()
// 所属容器最终关闭；共享 owner 的页面不能关闭别人的会话。
session.close()
```

片段中的 selected* 是调用方真实选择，不是示例库提供的全局变量。实际 `execute` 是挂起方法，必须由所属 scope 调用；保留 CancellationException，其余 BleProtocolException/BleTransportException 映射到项目 State/公共反馈，不能空 catch。StateFlow 观察跟随页面生命周期，停止/关闭按功能 owner 负责。此示例没有 Application 自动连接或后台 Service；跨页面/后台持续连接另定义 owner/平台运行需求。

## 已明确的失败边界和限制

- 传输层返回表示发现、MTU 策略和所有 CCCD 成功；握手成功之后才由会话发布 READY。CCCD 本地启用和远端写入都要成功。
- 所有平台入口、状态和父 scope 在同一 Main owner。队列准入 8、回调投递 64、通知 64、事件 32；控制流超量失败退役，不静默丢控制包。事件只一个消费者，带 generation，异步处理完成前再核对当前代次。
- 新旧回调绑定原 GATT 和特征/descriptor 实例。API33+ 使用回调 value 的副本。受理后缺回调/取消即退役；不能释放槽后复用同一连接。MTU 只有明确拒绝/失败且设备允许时降级，缺回调不降级续用。
- execute 期限覆盖写入和响应；命令等待准入时取消不影响连接，进入执行后取消按结果不明退役。设备明确拒绝普通命令不损坏健康连接；握手拒绝不能 Ready。格式/响应超时/认证错误不无限重连。
- 只有链路丢失、连接超时和 GATT 操作超时按预算重连；重连重建全部订阅、codec、缓冲和握手，绝不回放业务命令。持续 Ready 30秒后才重置预算（可注入）。权限/蓝牙关闭需宿主重新准备并显式 connect，不自动恢复旧业务动作。
- 默认初始化固定为发现→MTU→订阅；同 UUID 多实例会明确拒绝。无自动 bond、加密会话、残帧计时器、PHY/连接参数优化、扫描寻找轮换地址、长期后台/进程恢复、多设备仲裁、音频或升级。需要时根据真实协议扩展，不能按已有 API 猜测支持。
- WITHOUT_RESPONSE 仍等待 Android 本地写回调；它不提供对端可靠性/ACK，不保证所有厂商栈行为。通知/读属性值限定普通 ATT 512字节；长读写/可靠写另行实现。

四项夹具是可调用源码，当前仅完成静态核对，尚未执行编译、自动测试或硬件验收。需覆盖真实 MTU/CCCD 回调、权限撤销、关蓝牙、队列溢出/取消、协议坏帧、响应乱序/重复、同地址新连接旧回调以及后台限制后，才能登记为目标设备已验证支持。
