# 生成演练与 Sony 蓝牙实现的对照

本对照基于源码静态审阅。Sony 有实际产品协议链路；本次生成的是通用连接检查与本地协议演练，尚未达到 Sony 的设备功能覆盖。没有执行 Android 编译、自动测试或真机连接，不能由“生成了”推导“运行正常”。

## 逐项差异

| 项目 | Sony 参考源码的能力 | 模板演练生成后的能力 | 差距归属 |
|---|---|---|---|
| 扫描 | SonyBLEScanner 按服务 UUID、广播字段、经典蓝牙地址哈希/型号筛选，并随前后台选择模式 | BleScanner 真实扫描、按当前地址去重、100 项容量、15 秒截止、错误及蓝牙关闭清理，用户手选目标 | 已有通用扫描；自动识别需新设备 Profile，不照搬 Sony 哈希 |
| GATT 连接 | SonyBLEManager 连接、MTU、PHY、服务发现，再找私有端点 | Inspector 保持通用查看；另提供 AndroidBleTransport 初始化、MTU 策略和端点验证 | 已补源码；未做 PHY 优化或设备验收 |
| 通知订阅 | 本地通知开关+写 CCCD，descriptor 成功后查询型号 | AndroidBleTransport 检查属性，串行启用 notify/indicate 和 CCCD，缺回调退役 | 真实端点由设备协议提供，平台行为待验证 |
| 真实读写 | SonyBLECommandService 实际写特征，控制队列有界、音频/控制共用 gate、busy 等待 | AndroidBleTransport 统一串行 discovery/MTU/descriptor/read/write，匹配回调、容量/截止与取消 | 已补源码；不包含长读写、音频调速或所有厂商栈兼容性验证 |
| 组帧 | Sony 特定帧头尾/转义/长度/校验、命令与音频分流 | BleFrameAssembler+ExampleLengthFrameFormat，真实调用处理夹具半帧/粘包；有界缓存、校验再输出 | 通用机制可参考；真实 Sony/其他协议适配、坏帧/残帧恢复尚未接入无线通知 |
| 握手与 Ready | CCCD 完成后发送 GetModelInfo，处理型号/固件/电量等，带 Sony 分类/降级状态 | 可替换 BleDeviceProtocol/BleProtocolSession，完整教学 Hello/Ping；manager 握手成功才 READY；查看页面仍 DISCOVERED | 扩展点已提供，真实设备 Profile/安全/握手/能力仍需实现 |
| ACK/请求响应 | Sony ACK 切换交替序号，并按命令分派响应 | broker 写前登记响应键，单命令在途、取消/超时退役、重复/不匹配忽略，每连接 ID 不复用 | 真实 ACK、多阶段完成、无 ID/交替序号需专门设计，不能直接套用 |
| 断线重连 | 上层 SonyBluetoothManager 在经典耳机仍连接时重新检测/扫描，分阶段超时与前后台策略 | manager 持有连接意图，退避重开地址、重新订阅/握手，稳定窗口后重置预算；peer 调用同一逻辑 | 源码已补；无线未验收。轮换地址扫描、后台及权限/开关恢复需产品定义 |
| 生命周期 | 长期 Manager、Service 及音频会话共同参与 | 页面 ViewModel 拥有依赖，onStop 停止所有操作，onCleared close；无单例/全局启动 | 后台持续连接/跨页共享若需要，另定义长寿命 owner/Service，不将页面示例直接常驻 |
| 经典音频 | A2DP/HFP 状态、配对、SCO 等与 BLE 配合 | 未接入 | 可选产品能力，普通 BLE 设备无需引入 |
| 音频/升级 | 音频收发、编解码/节奏/序号/诊断；产品相关 OTA 能力分类 | 未接入音频 SDK、设备升级或私有命令 | 可选专用链路；单独分析协议、SDK/ABI/许可证，不能通用搬运 |
| 权限/提示 | 产品权限入口与连接页面配合 | 复用已有 PermissionActionController 的 List、拒绝 Toast/设置引导；本地夹具无需蓝牙权限；一般错误内联 | 实际接入位置已经明确，仍需各 Android 版本验收；后台权限另按需求 |

## 从对照暴露的模板缺口

此前的原语和 Inspector 不足以说明其他协议如何接。后续按用户授权补齐以下源码参考：

1. AndroidBleTransport：全操作队列、新旧 API、MTU/订阅、原 GATT 身份与超时退役。
2. BleDeviceSessionManager：连接意图、握手/Ready、响应关联、退役/重连、目标切换与稳定窗口。
3. BleDeviceProtocol/BleProtocolSession：Profile、组帧、命令、握手和能力接口，完整教学实现与同接口 peer。
4. [新协议接入指南](protocol-adaptation.md)：真实 API/路径、资料清单、适配步骤、组合根、限制与验证边界。

剩余主要是实际设备协议、安全/认证、产品 owner/后台行为，以及授权后的编译/夹具执行/无线验收。教学 UUID 和 XOR 格式不属于 Sony，不能作为未知设备默认协议；无 ID/小序号、多实例 UUID、音频/升级、轮换地址另按真实需求扩展。

## 参考项目中需要审慎继承的地方

SonyBLEManager 的部分回调/延迟任务仍通过全局 connectGatt/设备地址操作，未统一按原连接身份过滤；新签名通知读取 characteristic.value 而非 value；requestMtu 的立即拒绝/缺回调不能靠等待发现服务解决。来源的 gate 没有受理后的回调 watchdog，busy/旧 API false 循环也不能无限重试。移植时保留分工经验，并补这些边界；不将参考代码整体视为已经验证无缺陷。

本轮 Inspector 已在其有限范围内加入双身份、阶段截止和独立 disconnect/close；协议夹具补了早回调、缺回调退役、旧会话过滤的调用位置。它们仍未经过编译或真机验证，也未覆盖完整生产链路。

完整生成文件、组装及声明见 [接入说明](demo-integration.md)，对应 [Spec](demo-files/docs/feature/bledemo/test-ble-demo.md)。
