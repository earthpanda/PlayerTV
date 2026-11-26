# test-ble-demo：BLE 通用连接与协议夹具

## 目标与范围

用户授权在 PlayTV 生成 BLE 演练，并与 SonyEarphone 比较差距。本样例来源于模板 BLE 参考：真实扫描/连接/服务查看 + 独立本地协议夹具。没有指定真实设备端点或协议，因此不自动向未知设备订阅或写命令，不把服务发现当协议 Ready。模板资料和实现先维护，再逐项生成到业务项目。

## 设计、承载与导航

debug Activity `ui/test/bledemo/BleDemoActivity`；从本项目 debug 验证目录进入，返回不带业务结果，release 不包含该页面或 BLE 源码/权限。技术样例，无 Figma、图片或外部资产；沿用既有背景、文字操作 style、ConstraintLayout、RecyclerView、系统栏与 pt 单位；实施前读取目标 design.width，不推断正式设计。模板源文件在 docs/reference/ble/demo-files，默认只作为资料交付。

## 状态和交互

真实部分：进入页首次请求所需 List 权限，只准备授权，不自动扫描/连接。点击扫描后开启最多 15 秒，结果按本轮地址去重、最多 100 项；扫描观察入队最多 128 项，超量观察可丢，控制失败回调保留。点击当前列表设备先结束扫描，再连接，连接 15 秒/发现 10 秒截止。GATT_SUCCESS + STATE_CONNECTED 后才发现服务；结果为 DISCOVERED（明确尚未订阅/握手），显示服务、特征、属性和描述符 UUID。断线/失败关闭原 GATT，手动重新扫描连接；真实链路本轮不自动重连。

本地夹具：无权限/蓝牙也可使用，四项操作分别演示正常写回调+分片/粘包响应、断线退避后新会话、受理后回调缺失超时、同逻辑设备重连的旧回调隔离。界面始终标明本地模拟；握手/订阅是夹具阶段，不验证真实 CCCD。使用独立示例格式和请求语义校验，展示完整帧数、代次、重试/忽略旧回调数；重复点击同一夹具任务不重入。

夹具现调用 BleDeviceSessionManager → ExampleBleProtocol/BleProtocolSession → ExampleBlePeer（实现 BleTransport），Hello/Ping 经过同一请求等待、组帧和解码接口，不再手工拼阶段代替会话管理。peer 注入重复响应、先于本地完成的通知；缺回调触发协程期限，断线触发 manager 退避重开/重新握手，旧回调保留原 link 并实际计数。人工验收应确认四项分别 COMPLETED/COMPLETED/TIMED_OUT/COMPLETED，重连代次增加、重试1、旧回调数1；当前未执行这些验收。

另交付 AndroidBleTransport 源码供已知协议显式组合：发现/MTU/CCCD/读写共用有界队列，API33 回调副本、原连接身份和超时退役。页面现有 Inspector 仍只显示 GATT，不会自动使用教学 UUID。新协议资料/源码/组合步骤见工程文档 `docs/reference/ble/protocol-adaptation.md`（本 Spec 参考副本与激活位置不同，因此使用工程根路径）。

“停止”取消权限待动作、扫描、连接和夹具；重新选择不同设备退役旧连接，同地址仍活动时不重复连，退休后同地址也是新实例。旧 callback/deadline 不得改变新会话。超时/失败状态保留；取消不作为失败 Toast。进入设置、退后台、返回、旋转均停止页面资源，不恢复旧扫描/连接/命令；回到页面可手动开始。进程重建回到空闲，不持久恢复硬件动作。

## 数据契约与公共反馈

平台技术类型在 base/debug/bluetooth/ble，公开 StateFlow 只读；无业务类型的设备地址仅限本轮扫描，不持久当旋转地址设备的稳定 ID。页面 Contract 在 model/debug/contract/bledemo，夹具状态在 state/bledemo，夹具管理在 manager/bledemo。app ViewModel 显式接收 Scanner/Inspector/Fixture，Activity Factory 使用 applicationContext 构造技术依赖，ViewModel 不持有 Activity。

权限复用 PermissionActionController：拒绝 Toast、再次触发重新请求、部分拒绝阻止硬件动作、需要设置时复用 SettingsRedirectDialogFactory，返回设置不自动执行旧动作。其他错误在 State 内联显示；仅设置启动失败用 MessageController（message.transient），无操作遮罩/确认框。无图片、HTTP、存储、账号、数据库、通知或 Android Service。

## 权限与依赖

仅 debug Manifest 增加 BLE uses-feature required=false；BLUETOOTH/BLUETOOTH_ADMIN/FINE_LOCATION 的 maxSdkVersion=30；31+ SCAN（neverForLocation）和 CONNECT。demo 明确不使用扫描结果推导位置，neverForLocation 可能过滤部分 beacon；定位产品/后台扫描另行设计，不能照用。API <31 请求 FINE_LOCATION 并检查位置开关。权限在使用 API 时可能撤销，捕获 SecurityException 并清理；蓝牙关机广播退役当前运行。

不新增 SDK 或版本。已有 Kotlin/coroutines、AndroidX Core、RecyclerView、MVI/ViewModel、permissions 和 feedback 足够；目标必须已交付相应实现。单写 Gate/Turn 保留参考，新的 transport 使用统一队列，夹具调用 transport 契约；源码参数为纯技术类型，实际模块显式依赖仍须核对。

## 生命周期、失败和验证

所有平台入口/回调状态处理在主线程 owner；每次尝试捕获会话 + 原始 GATT 双身份，超时、radio receiver、扫描 callback 都有代次过滤。停止先使身份失效再移除任务/监听、disconnect/close；清理两步分别捕获，不依赖系统回调。ViewModel onCleared 关闭依赖，Activity onStop 停止本页任务，onDestroy 清理适配器/控件监听/Insets/反馈与权限宿主。

完成状态：模板与业务源码生成及静态核对。未执行 Kotlin/Android 编译、单元/集成测试、lint、扫描/连接或真机验收。人工授权后验证权限部分拒绝/设置返回、旧版本位置开关、蓝牙关闭、重复点击、扫描截止/空、无效设备/断线、发现失败/超时、同地址重连旧回调、离开/旋转、四种夹具和停止后重开。Sony 的协议/音频差距单独按对照记录评估，不能由本样例的完成推导已适配 Sony。
