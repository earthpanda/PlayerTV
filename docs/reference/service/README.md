# 可选 Service 组织与共享通知源码参考

**用到 Service 时再接入。** 本目录只是可查阅的 Kotlin 参考，不属于 app/base/model/support 源集，没有注册 Manifest、申请权限、初始化或运行任务。它不是已经启用的 service capability；不需要 Service 的工程跳过接入。参考代码随项目文档交付，不要求后续 AI 再寻找 Sony 工程。

使用范围：minSdk 26、compileSdk 35，同应用同进程，多个独立 started 前台 Service，每个 Service 一个运行会话和固定前台类型。只使用 Android 平台与项目已有 AndroidX Core/annotation 能力，无新增第三方 SDK。代码尚未经过 Android 编译、lint 或设备验证，接入时须按真实业务完成这些验证。

## 先从这三个文件看起

| 文件 | 解决的问题 | 接入后的归属 |
|---|---|---|
| [RuntimeNotificationCoordinator.kt](RuntimeNotificationCoordinator.kt) | 登记每个真实 Service、汇总展示、统一 ID、注销一个保留其他，最后一个退出取消通知 | app/service/runtime，纯技术部分有真实复用需求再提取 base |
| [CoordinatedForegroundService.kt](CoordinatedForegroundService.kt) | 基础前台登记/更新/清理；具体 Service 实现业务和命令策略 | app/service/runtime |
| [RuntimeNotificationFactory.kt](RuntimeNotificationFactory.kt) | 同一渠道和通知样式，app 传文案、合法小图标与明确 PendingIntent | app/notification/runtime；引用相应协调器类型，调整 package/import |

当前源码放在同一参考包中方便连贯阅读。真正接入时复制至上表位置并调整包名；Service、通知策略和业务控制器仍按 [数据契约](../../rule/data-contract-rules.md) 分工。完整边界见 [Service 与通知规则](../../rule/android-service-notification-rules.md)。

## 与参考项目的关系

审阅来源为 SonyEarphone 的 `model/.../service/AppRuntimeService.kt`、`AppRuntimeServiceManager.kt`、`RuntimeBusiness.kt` 以及 app 中的录音/消息通知管理器。保留统一前台入口、共享稳定通知身份、状态驱动内容、业务分别停止的做法；下述参考和专题规则已经包含接入所需的组织说明，无需访问原工程。

来源主要是单个 Service 承载多种业务；这里补充了用户关注的“多个独立 Service，共用一套通知机制”。每个 Service 仍独立 startForeground，协调器统一内容，Service 之间不直接控制对方。参考不复制 Sony 的蓝牙 SDK、录音逻辑、权限集合、全局 Binder 服务定位、硬编码文案或图标回退。

## 共享通知的完整流程

1. 用户在允许启动的场景开始 A；A 先以“正在准备”登记并及时提升前台，再初始化业务。
2. A 的业务确认启动后，更新自己的状态。B 启动时以同一个协调器登记；B 单独提升前台，显示汇总后的 A+B。
3. A 结束先清理自己的资源，再释放登记；协调器 detach A 的前台关联，并将共享通知更新为 B。
4. B 结束后登记为空，协调器取消这一条共享通知。旧登记重复 release 不会移除新 Service 实例。
5. 新增服务 C 复用基类与同一协调器，不自行创建另一份全局通知状态。

这组 Service 使用相同非零 notificationId 和同一渠道；普通消息/提醒使用其他身份。全部协调操作在主线程串行；耗时工作依旧放在 IO/SDK 所需执行器。多个进程不能直接共用这份内存集合。

## 组装入口：一组服务只持有一份协调器

在实际启用该能力的 app 容器中增加懒属性：

```kotlin
val runtimeNotifications by lazy {
    createRuntimeNotificationCoordinator(
        applicationContext,
        RuntimeNotificationConfig(
            notificationId = runtimeNotificationId,
            channelId = runtimeChannelId,
            channelName = applicationContext.getString(runtimeChannelNameRes),
            title = applicationContext.getString(runtimeTitleRes),
            smallIconResId = confirmedNotificationSmallIcon,
            contentIntent = openRuntimePagePendingIntent,
        ),
    )
}
```

上面的 ID、资源和点击目标由具体项目明确提供，不是已经存在的 R/API。点击使用显式 Activity、IMMUTABLE PendingIntent，按需求区分不同动作身份。不得用系统占位图或 launcher 图标补缺失设计资源。中文/英文通知文案按项目约定同步维护。

Service 由系统创建，不能给它添加普通构造注入参数；在它的创建入口从 app 组合根取得这个明确属性及自己的业务控制器。业务控制器只接收自己的依赖，不经 Binder 查找全部实例，也不持有 Activity。

## 两个具体 Service 的写法

以下是**接入编排片段**。`appDependencies`、`recordingRuntime`、`deviceRuntime`、notice 和错误上报均由具体功能提供；片段不假装已经实现录音/设备业务，也不作为可直接启动的完整样例。

```kotlin
class RecordingService : CoordinatedForegroundService() {
    override val notificationCoordinator
        get() = appDependencies.runtimeNotifications
    override val requiredForegroundServiceType =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 本示例只接受明确的开始命令；业务结束由自己的控制器通知 finishRuntime。
        if (intent?.action != ACTION_START_RECORDING) {
            finishRuntime(startId)
            return START_NOT_STICKY
        }
        if (recordingRuntime.isStartingOrRunning) return START_NOT_STICKY
        val error = enterForeground(preparingRecordingNotice()).exceptionOrNull()
        if (error != null) {
            reportStartFailure(error)
            finishRuntime(startId)
            return START_NOT_STICKY
        }
        // 请求已被 Service 接收不代表录音已成功。控制器必须设置 starting 状态、
        // 处理初始化异常，并在真实成功后更新通知/发布成功；失败进入 finishRuntime。
        startRecordingWithSessionCallbacks()
        return START_NOT_STICKY
    }

    override fun releaseRuntime() = recordingRuntime.close()
}

class DeviceService : CoordinatedForegroundService() {
    override val notificationCoordinator
        get() = appDependencies.runtimeNotifications
    override val requiredForegroundServiceType =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_DEVICE) {
            finishRuntime(startId)
            return START_NOT_STICKY
        }
        if (deviceRuntime.isStartingOrRunning) return START_NOT_STICKY
        val error = enterForeground(preparingDeviceNotice()).exceptionOrNull()
        if (error != null) {
            reportStartFailure(error)
            finishRuntime(startId)
            return START_NOT_STICKY
        }
        startDeviceWithSessionCallbacks()
        return START_NOT_STICKY
    }

    override fun releaseRuntime() = deviceRuntime.close()
}
```

`startRecordingWithSessionCallbacks` 和 `startDeviceWithSessionCallbacks` 是具体功能必须实现的编排角色，不在参考代码中伪造 SDK 成功。异步回调带当前会话代次，回到主线程后检查会话仍有效，成功才调用 updateForegroundNotice；失败/结束调用 finishRuntime。通知更新失败要记录并按 Spec 决定重试/降级，不能把展示失败等同业务失败。close 必须幂等，取消 Job/监听，阻断关闭后的迟到回调；非阻塞关闭无法满足时应按 SDK 契约单独设计。

用户停止某个服务时，在进程仍可处理该操作的入口用显式 `context.stopService(Intent(context, RecordingService::class.java))` 结束该服务，由 onDestroy 清理自己的运行资源和登记。无需为传 STOP 而从后台新启动一个 Service。业务内部完成/失败使用 finishRuntime；进程被杀不保证 onDestroy，恢复由具体业务定义。

started 开始命令及其异步完成路径保留本次 startId，调用 `finishRuntime(startId)`，避免旧命令结束排队中的新请求。每个新会话重新创建已经取消的 Scope，回调同时校验会话代次；不能复用取消后的 Scope。

前台类型按系统版本选择：API 34+ 必须传真实、已声明且符合准入的非零类型；旧系统确实不使用新类型时可传 0。例如有界短任务仅在 API 34+ 使用 SHORT_SERVICE，旧系统传 0；不能将此兼容分支用于规避新系统的类型要求。

## Manifest 与启动端要一起补

仅在启用具体服务的工程声明对应组件、`exported=false`、真实 foregroundServiceType、FGS 基础/类型专属权限及业务运行权限。录音和设备只是两种组织示例，不表示项目默认需要两类权限，也不表示所有设备任务都需要扫描权限。

UI 先满足该动作的权限与启动条件，再用显式 Intent 和 ContextCompat.startForegroundService 启动相应 Service。捕获启动端拒绝，Service 端仍处理提升前台失败；成功回执来自真实业务控制器，不是 ContextCompat 返回。UI/页面结束只解绑自己的观察，不能把共享通知取消掉。

本参考无需绑定，所以没有复制参考项目的全局 ServiceConnection 管理器。后续确需绑定再按专题规则补齐连接注册、等待者完成、超时/取消、断连/空绑定/绑定死亡和重连，不能用一个 isBound 覆盖所有状态。

## 接入前的必要差异与验证

- 确定本功能使用多个真实 Service，还是单宿主内多个运行单元；前者每个服务独立系统类型，后者宿主按有效任务汇总类型。
- 本基类是单会话 started 模式，未提供任务队列、绑定、持久恢复、多进程、动态类型切换或异步资源关闭；这些需求出现时先扩展契约，再实现，不把参考当作现成支持。
- 至少验证 A/B 同时运行、分别停止、启动失败保留其他、初始化失败、重复开始/释放、迟到回调、停止后重启、进程重建、后台启动拒绝、通知权限/渠道关闭及系统类型超时。
- 渠道关闭不一定使系统调用抛错；通知协调器返回成功只代表调用成功，不保证用户可见/可听。状态文案、前台系统限制与业务恢复仍由具体功能负责。
- Android 构建、测试和设备验证由使用者明确授权后执行。本轮仅交付源码参考与静态核对，不修改已确定的构建栈，不在当前记事本开启 Service。
