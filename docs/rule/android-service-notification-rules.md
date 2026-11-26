# Android Service、多任务协调与通知

本文件维护 Android Service 的承载选择、连接、前台状态与系统通知规则。模块与类的唯一归属见 [数据契约](data-contract-rules.md)，资源所有权和组装见 [Manager](manager-lifecycle-rules.md)，权限申请见 [权限规则](permission-rules.md)。`network/service` 中的 Retrofit 接口不属于 Android Service。

这是新增实现的写法约定，不代表已经提供通用 Service 宿主、连接客户端或通知管理器。实际可调用能力以 [组件目录](ui-component-catalog.md) 和源码为准；不按本文的角色名预建空壳或默认启动后台能力。

Service 是选填能力：项目没有持续服务需求时跳过接入，不生成运行源码、注册 Manifest、添加权限或初始化。需要时先确定具体功能，再按 [可选源码参考](../reference/service/README.md) 构建。参考材料随文档交付，位于 docs，不参加 Android 编译，也不是已注册的 service capability。

## 先确定是否需要 Service

| 工作 | 优先承载与边界 |
|---|---|
| 页面内请求、短计算 | 页面所属协程；跨页资源按真实所有者管理 |
| 可延迟且需持久恢复的任务 | 已启用的 WorkManager 等调度能力；说明重试、幂等和约束 |
| 用户指定时刻提醒 | AlarmManager 与短时 Receiver；持久补偿另行调度 |
| 持续录音、符合条件的设备连接等用户可感知任务 | 按系统准入选择前台 Service，声明真实类型与结束条件 |
| 仅当前进程需要的连接 | 有作用域的 Manager；确需绑定宿主时才使用 bound Service |

Service 默认在应用主线程回调，不提供后台线程。数据库、网络和重计算仍由所属执行器调度。普通绑定和前台服务都不能承诺进程永久存活；不以“保活”为由挂空通知、借用不匹配的类型或无限重启。

## 多个服务怎样共用通知机制

统一管理的核心是一个通知协调器：服务开始时登记、状态变化时更新、结束时只注销自己的登记。协调器按当前有效服务汇总一份通知内容，集中维护渠道、通知 ID、样式和点击入口。业务 Service 不各自直接 notify/cancel 共享通知。

| 组织方式 | 使用同一通知的做法 |
|---|---|
| 一个 Android Service，多个业务运行单元 | 宿主分别管理运行单元，汇总状态和需要的前台类型；宿主负责一次前台生命周期 |
| 多个独立 Android Service | 每个 Service 分别负责自己的启动、前台类型和清理；通过同一进程内的协调器，以同一通知 ID、同一汇总内容进入前台并统一更新 |

多个真实前台 Service 可以共享同一应用内的一条通知，但每个 Service 仍须分别调用 startForeground 并满足自己的时限、权限与类型要求；共享通知不能把普通后台 Service 自动变成前台 Service。共享渠道只统一渠道设置，不会自动合并通知；单纯复用 ID 而各自生成内容会造成互相覆盖。

参考代码采用同进程、多独立 Service 的方式：A 开始显示 A；B 开始显示 A+B；A 停止后显示 B；最后 B 停止后移除通知。每个 Service 持有独立登记令牌；移除时 detach 自己的前台关联，由协调器决定更新或取消共享通知。普通消息通知保持独立身份，不参与这个引用集合。

跨进程服务需要单独设计 IPC 和唯一通知所有者，不能复制进程内单例。共享场景还需在目标系统验证停止、重启、权限撤销和用户停止行为；不承诺所有 OEM 的展示完全一致。Android 对共享 ID 的处理可参考 [AOSP ActiveServices](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java) 的 cancelForegroundNotificationLocked。

## 同一宿主内的运行单元

生命周期和恢复策略兼容、允许共同故障边界的业务，可以共用一个 Service 宿主。宿主只协调平台生命周期、运行单元和前台通知；各业务仍有独立控制器、状态和清理入口。生命周期、系统时限、独立停止或隔离需求明显不同的业务可拆开承载，不能强制所有业务进入一个 AppRuntimeService。

- 页面调用业务语义接口；业务请求运行保障，app 实现平台入口。Binder 留在平台适配内部，不让 ViewModel/model 经 Binder 取得所有业务实例。
- AppContainer 组装并持有连接客户端。Service 只构造最小必要部分，其他运行单元按需启动；不能每次绑定消息连接都顺便初始化录音、蓝牙等全部 SDK。
- 连接、前台状态、业务就绪分别建模：Bound 不等于 Foreground，Foreground 不等于业务动作成功。start/bind 系统调用返回只说明请求被接受；对外成功必须有实际执行确认或权威状态。
- 通知由已确认的运行状态生成。发送请求、权限检查失败或初始化未完成时，不能先显示“正在录音”等成功状态。

## 任务所有权、并发与停止

- 单一业务的唯一所有者可以使用幂等 start/stop；同一业务存在多个所有者时使用带 taskId/ownerId 的租约或等价机制。重复 acquire/release 幂等，释放一个任务不影响另一个任务。
- 前台需求从有效任务集合汇总；位掩码可作为派生值，不能替代所有者记录。业务状态、所需系统类型、通知展示模型各有明确含义。
- 请求、确认、停止串行执行，明确在主线程还是串行调度器。监听器使用线程安全集合不等于其他共享字段已安全。
- 开始失败或等待被取消时回滚本次持有的资源；已发给系统且无法撤回的命令按请求标识处理迟到回执，不能留下无主任务。一个等待者取消不能取消其他调用者共用的连接等待。
- 新增运行单元失败只撤销本次请求，尽量保留已运行任务；若平台状态无法安全维持，进入明确失败/结束流程并通知受影响业务，不能回滚内存后假装系统仍处于原状态。
- 最后一个前台任务结束后移除对应 ongoing 通知，释放 started 部分；是否继续绑定由连接所有者决定。业务结束、stopForeground、stopSelf、unbindService 是不同操作，不能用其中一个代替其他清理。
- 页面离开只释放页面自己的观察和租约；明确允许后台继续的任务转交会话/任务所有者。某业务不得直接解绑其他业务共享的进程连接。

## 连接与异常退出

- 一份客户端合并并发 bind；记录 bindService 接受的连接注册，包括回调尚未到达的阶段。关闭时清理注册，不能只依据“已收到 Binder”决定是否 unbind。
- 区分系统仍保留连接注册的 onServiceDisconnected、必须解绑后才能重新绑定的 onBindingDied，以及不会交付可用 Binder 的 onNullBinding。按各自语义处理，不能一律清空布尔值后重复 bind。
- 连接失败、超时、主动关闭、Binder 失效必须让当前等待者得到明确结果；清空 Deferred 引用不能代替完成它。关闭后来的连接回调/业务确认用代次丢弃，并释放该代次资源。
- Binder 发布后才触发依赖它的业务回调；更优先通过构造注入消除“Service 初始化 → 客户端取 Binder → 等待 Service 初始化”的环。
- Service 销毁逐项尽力释放运行单元、Job 和监听，单项失败不阻止后续释放。进程被杀时不保证 onDestroy/onTerminate，持久恢复不能依赖这两个回调。
- START_NOT_STICKY、START_STICKY 或重投递必须由业务恢复需求决定。空 Intent、重复命令、系统停止后的行为写入 Spec；不默认恢复录音等有副作用操作。

## 前台服务的系统准入

- 按当前设备版本、compile/targetSdk 核对 Manifest 的 Service、exported、foregroundServiceType、基础与类型专属 FGS 权限、运行时权限及后台启动条件。只声明实际需要的类型。
- Android 12+ 后台启动限制、Android 14+ 类型/使用期间权限条件，以及后续版本的类型时限和启动来源限制需要分别核对。拥有 RECORD_AUDIO 等权限不等于当前允许从后台启动对应 FGS。
- 使用 startForegroundService 后及时按系统要求调用 startForeground；不能先等待耗时 SDK、网络或数据库初始化。处理启动端和提升前台端的拒绝，并反馈实际原因。
- 多任务聚合仅提交真实需要、已声明且符合准入的类型；任务增减需核对平台实际状态，不能只修改客户端缓存。
- Android 13+ POST_NOTIFICATIONS 被拒绝不等于平台一概禁止 FGS；仍须提供合法前台通知，系统展示位置可能受限。业务是否允许在该情况下继续由 Spec 明确，不能把通知权限和 FGS 准入合成一个 Boolean。
- 对有系统时限的类型落实对应版本的超时回调和终止，不将有限时任务塞入长寿命宿主规避限制。

平台核对入口：[前台服务](https://developer.android.com/develop/background-work/services/fgs)、[绑定服务](https://developer.android.com/develop/background-work/services/bound-services)。链接是后续接入依据，本轮规则整理不代表已验证所有 Android 版本。

## 通知分工与渠道

| 通知角色 | 负责的内容 | 默认处理方向，具体值由 Spec 确定 |
|---|---|---|
| 宿主前台状态 | 当前持续任务及必要操作 | 合并展示、ongoing、低打扰；稳定 ID，状态变化更新 |
| 业务提醒/消息 | 某条提醒、消息或任务结果 | 独立业务渠道；按业务 ID 更新/取消，点击进入对应业务 |
| 特殊任务展示 | 锁屏任务状态、确有需要的自定义布局 | 单独评估与宿主通知的重复、生命周期和平台模板限制 |

- 渠道按稳定用途隔离，名称、描述和通知文案使用 strings.xml。用户控制的 importance、声音、振动不能靠重复建渠道覆盖；渠道版本迁移有明确产品原因，不通过删建渠道或不断换 ID 绕过用户关闭。
- 普通通知分别查询运行时权限、应用开关与目标渠道，处理检查后撤权/notify 异常。返回可辨认的 Posted/Updated/AlreadyHandled/Blocked/Failed 等语义；系统接受发送不等于用户已看到或听到。
- Android 8+ 提醒强度以渠道设置为主，不能只提高 NotificationCompat priority 就承诺横幅/声音；免打扰、音量、锁屏策略也会影响实际表现。
- 独立通知组各有稳定、非零 ID；明确共享前台通知的一组 Service 统一使用组内 ID 并由同一协调器维护，避免与普通无 tag 通知撞号。普通业务通知优先稳定的业务 tag 加固定非零 ID 等无损身份方案，不将 Long 简单 hash 成唯一 Int。
- 功能只取消自己的 tag/ID；功能级 cancelAll 也必须限定自己的范围，禁止顺手调用全应用 NotificationManager.cancelAll 清掉其他功能通知。
- 更新同一通知使用相同身份，通常配合 setOnlyAlertOnce。计时用系统 Chronometer 与单调时间基准，不每秒 notify；暂停/继续正确重算累计时长。
- 限频必须保留并最终展示最新状态；不能简单丢弃时间窗内最后一次暂停/结束更新。结束和关键失败优先及时呈现。
- 通知展示记录与消息已读/业务去重分开。通知被划走、点击 autoCancel、进程重建后不能仅因本地“发过”集合就误判仍在展示；数据保留和去重窗口由业务定义。

## 图标、点击与操作

- 小图标使用已确认、符合系统通知要求的本地资源，由 app 配置并验收；不能默认拿 launcher/adaptive icon 或系统占位图替代缺失设计资产。
- 点击使用显式 Activity Intent 或已明确限定的目标；业务标识进入稳定 data/action/requestCode 身份。仅修改 extras 不会产生不同 PendingIntent，不能导致 A 通知打开 B。
- 默认 FLAG_IMMUTABLE，确有 RemoteInput 等需要时才使用可变 PendingIntent。点击导航直接到 Activity，避免 Receiver/Service 充当通知跳转中转站；停止/暂停等后台动作可使用显式且受控的 Receiver/Service 入口。
- 冷启动、已有任务栈、onNewIntent、业务对象已删除和账号变化都要定义去向；点击通知不恢复过期会话。平台回调按最小可信输入校验，不能凭 Intent 标记跳过权限/业务校验。
- 锁屏默认最小必要信息；正文、设备名等是否公开由功能决定，不复制来源项目的 VISIBILITY_PUBLIC。RemoteViews/MediaStyle 仅在语义和平台行为适合时采用，不为特殊显示效果伪装媒体任务。

## 接入与验收

功能 Spec 说明：承载理由、任务所有者、单/多宿主、启动来源与系统类型、连接/业务/前台状态、通知渠道与身份、操作与导航、拒绝降级、结束和恢复。无 Service 的提醒仍须填写实际通知规则。

按实际范围核对：A/B 同时运行后停止 A、重复开始/释放、绑定中关闭、断连/绑定死亡/空绑定、启动或提升前台被拒、取消后迟到确认、通知关闭/渠道关闭/撤权、冷启动点击、状态限频末次更新、用户/环境切换与进程重建。构建、设备交互和系统版本覆盖按 AGENTS 授权并分别记录；静态文档核对不作为运行通过。
