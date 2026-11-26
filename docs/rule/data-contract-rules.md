# PlayTV 模块职责与数据契约

本文件是模块职责、技术封装、业务/技术类型归属与 Contract 文件组织的唯一规则来源。

归属确定后，执行行为按 [业务开发](business-development-rules.md)，长寿命对象按 [Manager 规则](manager-lifecycle-rules.md)，持久化按 [存储规则](file-storage-rules.md) 实现；系统授权按 [权限规则](permission-rules.md) 由适当宿主承载。本文件不重复这些行为规则。

| 模块 | 职责 | 工程模块依赖 |
|---|---|---|
| base | 无业务的工具、平台和基础 UI，及它们自身的技术类型 | 无；可直接使用已选基础工具库/AndroidX |
| support | SDK 技术封装、初始化/释放、配置、错误/线程/取消约定及自身技术类型 | 最多按实际需要引用 base；禁止 model/app |
| model | DTO、实体、领域/展示数据、页面契约、业务状态机、Repository、业务管理与转换 | 按需 base/support；禁止 app |
| app | 页面、ViewModel、Adapter、UI 组合、Android 组件与平台业务适配 | 按需 model/base/support |

当前实际 Gradle 只声明实现确实使用的边，不为了固定链条增加无用依赖。SDK 封装在 support 完成，model 在增加账号/缓存/权益等业务语义时才再组合，不机械提供同名转发。纯展示技术可由 app 直接调用 support。通用键盘/屏幕/文件基础操作归 base，会员判断等共用业务仍归 model。

本工程实际依赖方向：app → base/model；base → 无其他工程模块；model → 无其他工程模块；support → 无其他工程模块。只声明实际使用的边，不要求填满允许方向。

优先不在公开 API 暴露 SDK 类型，依赖默认 implementation；公开签名确有需要时登记 api 及耦合原因。仅 api 导出不等于封装。框架继承、Gson/Room 等注解或代码生成允许对应实现/定义模块直接声明，不能为隐藏声明制造空壳。启用的刷新 View 如继承 SDK，应记录类型暴露边界，不能宣称完全隔离。

## 业务角色的唯一判定

按职责选择最少的实际层次，四模块和 MVI 保留；执行链由动作决定，不固定为 ViewModel → UseCase → Repository → 网络。

| 需要承担的职责 | 形式与归属 | 不由什么决定 |
|---|---|---|
| 页面事件校验、任务调度、State/Effect | app 的 ViewModel | 不在其中实现独立领域算法或数据读写 |
| 无 IO 的局部计算/值约束 | 所属类型的函数；领域规则在 model/bean/domain | 不因有多个函数就创建 Manager |
| 独立业务计算、多个规则/步骤的编排、跨依赖业务动作 | model/usecase/<feature> 的 UseCase、Planner 或业务服务 | 不要求多个数据源；不要求名称一定以 UseCase 结尾；简单动作不建空转发 |
| 数据读取/写入、缓存、数据源选择与业务转换 | model/repository/<feature>；持久化 key/schema 在 storage | 不因函数返回结果就叫 Repository，纯算法不伪装成数据源 |
| 持续连接、会话、队列或资源调度 | model/manager/<feature> 的业务 Manager/Controller；平台宿主按专项目录 | 不由 Manager 后缀决定单例或 Application 启动；生命周期见 Manager 规则 |
| SDK 初始化、调用、取消和技术结果 | support 对应能力；无 SDK 平台原语归 base | 不吸收业务账号、课程、提交或冲突决策 |
| 渲染、输入和平台 UI 实现 | app 所属功能的 render/view 等目录 | 技术类型不因 data/enum/sealed 关键字进入 model |

独立算法和编排都可在 usecase；小型纯规则贴近领域类型。不要求为每个具体类另建接口，也不为命名整齐新增模块。各行为规则引用本节，不另维护一份角色选择表。增长后按 [结构复核](architecture-review.md) 重新检查职责。

## 自定义 View、功能弹窗与展示扩展

下列路径从对应模块的主包起算，按实际复用范围放置，不提前创建空目录：

| 使用范围/职责 | 唯一目录 | 命名示例 |
|---|---|---|
| 单个功能独有的内嵌 View / 弹窗 | app 的 `ui/<feature>/view/` / `ui/<feature>/dialog/` | `CustomReminderEditorView` / `CustomReminderTimeDialog` |
| 本项目多功能共用的 View / 弹窗 | app 的 `view/<purpose>/` / `view/dialog/` | `CustomEmptyStateView` / `CustomSettingsRedirectDialog` |
| 无业务的平台基础 View / 默认弹窗 | base 的 `ui/<purpose>/` / `ui/dialog/` | permissions 选填包的默认 `CustomSettingsRedirectDialog` |
| 系统权限查询、请求及生命周期调度 | base 的 `permission/` | `PermissionController`、`PermissionActionController` |
| 无业务的设置引导展示接口 | base 的 `ui/dialog/` | `SettingsRedirectDialogFactory`；不加 Custom 前缀 |
| 项目共用操作加载 / 短暂消息展示与协调 | app 的 `view/loading/` / `view/message/` | `CustomOperationLoadingView`、`OperationLoadingController` / `CustomMessageView`、`MessageController`；只在实际需要时创建 |
| 无业务的加载 / 消息原语与默认实现 | base 的 `ui/loading/` / `ui/message/` | 不引用项目 State、R、主题或 app；SDK 专属适配确有需要时才放 support |
| 项目共用确认框及其工厂 | app 的 `view/dialog/`；无业务默认实现归 base 的 `ui/dialog/` | `CustomConfirmationDialog` / `ConfirmationDialogFactory`；宿主注入，工厂不持有全局 Activity |

可复用组件的统一命名格式见项目约定：Custom + 明确职责 + 真实类型后缀；继承 Dialog/AlertDialog 用 Dialog，继承 DialogFragment 用 DialogFragment，内嵌视图用实际 View 类型。不能只叫 CommonView、PermissionView 或 Utils，让文件名隐藏实际职责。布局资源统一小写下划线：`view_<purpose>.xml`、`dialog_<purpose>.xml`；例如 `dialog_settings_redirect.xml`，由归属模块持有。

功能性 UI 仍属于展示层，不因为“权限”就放进 model，也不因共用就放 support。项目颜色、布局、文案和交互样式留在 app；可选包可提供无业务默认样式。底层控制器通过展示工厂接收项目 Dialog，不反向引用 app；Dialog 仅展示和回传确认/取消，不直接申请权限、启动业务或跳转设置。

设置引导采用 `SettingsRedirectDialogFactory` 的实例注入：创建后由 PermissionActionController 统一 show/dismiss/宿主退出清理，禁止工厂全局持有 Activity、自己 show 或覆盖控制器的关闭监听。`CustomSettingsRedirectDialog` 表达“用户确认后前往系统设置”，不是 Android 权限申请框；权限流程在系统不再提供授权弹框等场景决定展示。也可用于通知渠道、特殊访问等设置引导。系统授权弹框本身不能自定义。

上表新增候选角色的名称在项目约定维护，按需构建与展示契约见 [公共反馈](ui-feedback-rules.md)。同一能力的 Controller、Renderer、Factory 与其实现按职责放在所属用途目录，不为统一另建 common/manager 或全局 UiFeedbackManager。仅在一个功能使用时，仍归该功能的 view/dialog 目录；model 只持有状态和意图，宿主负责把语义映射为展示。

## Android Service 与通知的类归属

下表是功能接入时的目录约定，不是已交付的 API。路径从各模块的主包起算；只有真实功能需要时创建。生命周期与通知行为统一见 [Service 与通知](android-service-notification-rules.md)。

| 角色 | 唯一目录 | 边界 |
|---|---|---|
| 具体 Android Service、内部 Binder、连接客户端 | app 的 `service/<runtime>/` | 系统启动/绑定/前台与类型适配；不得放到 model/network/service |
| 业务运行控制器、平台运行需求接口 | model 的 `manager/<feature>/` | 业务开始/结束、所有权和持续状态；接口不暴露 Service/Binder/Intent，app 实现平台入口 |
| 独立运行状态/业务类型 | model 的 `state/<feature>/`、`bean/domain/<feature>/` | 按现有类型归属规则选择，不携带通知资源、系统类型常量或 Android 组件 |
| 前台通知渲染、普通业务通知、渠道和身份策略 | app 的 `notification/<feature>/`；跨运行单元的宿主通知用 `notification/runtime/` | 从业务事实生成系统表现，持有本应用资源与路由；现有 reminder 适配不为目录形式机械拆分 |
| 通知点击路由、操作 Receiver | app 的 `notification/<feature>/` 或已存在的功能平台目录 | 显式目标、输入校验、冷热启动/操作入口；不能从 base/model 引用 app 页面 |
| 无业务的连接/通知平台原语（确需复用时） | base 的 `service/`、`notification/` | 技术状态、取消与系统差异；不认识业务枚举、页面、渠道文案；不预建万能管理器 |
| 第三方服务/通知 SDK 适配（实际启用时） | support 对应 SDK 的技术目录 | 仅 SDK 生命周期和技术能力，不吸收本应用业务 |
| 运行依赖与平台接口实现的组装 | app 的 `di/` | 显式构造、所有权和关闭；页面不通过全局 Binder 获取全部依赖 |

`network/service/<feature>` 仅承载 Retrofit HTTP 接口。Android Service 承载运行生命周期，两者不因同名而放在同一目录。

## model 目录

```text
bean/net/<feature>/       请求/响应 DTO
bean/local/<feature>/     持久化实体
bean/domain/<feature>/    独立业务模型
bean/ui/<feature>/        展示项数据
contract/mvi/             UiState/UiEvent/UiEffect
contract/<feature>/       <Page>Contract.kt
state/common/             公共页面/请求状态
state/<feature>/          独立或跨页面业务状态机
enums/<feature>/          独立复用业务枚举
repository/<feature>/     数据动作与业务数据转换
network/service/<feature>/  Retrofit Service 接口声明
network/protocol/<backend>/ 当前后端的包络判定与统一请求结果处理
network/error/             对调用方稳定的请求/业务错误类型
mapper/<feature>/          有独立语义或复用需求的数据映射
usecase/<feature>/        独立业务计算、共享规则与必要编排
manager/<feature>/        确有持续资源/会话调度时的业务管理
storage/<feature>/        业务 Store、作用域、key/schema 与文件组织
database/<database>/      Room 数据库与 Migration
database/dao/<feature>/   SQL DAO 与查询投影
```

- 业务/页面 data、enum、sealed 定义全部归 model（不含下述实现内技术类型），新增先检索已有定义；不能在 ViewModel、Activity 或 Adapter 内随意新增业务契约。
- 一个页面或完整交互单元一个 `<Page>Contract.kt`：同文件的顶层类型带前缀，如 SampleState/SampleEvent/SampleEffect；不套无行为的 object，也不把整个功能所有页面塞进一份 Contract。
- sealed 分支可嵌套在对应 Event/Effect，小型专属类型可随 Contract。独立 DTO、实体、领域/共享模型单独放 model 对应业务目录，不塞入页面 Contract，不在 state 目录重复声明页面 State。
- 状态定义集中不等于全局 State 单例；实例由 ViewModel/业务会话持有。State 描述数据，执行、Job、Repository 实例不放入 State。
- 业务数据不携带 View、Context、Binding、Drawable、Intent、宿主工厂或 UI 回调，不引用 app 的 R/类。导航 Effect 带业务 ID/目标语义，UI 构造 Intent 并解析静态资源。
- 互斥阶段优先 enum/sealed，真正正交条件可用布尔值；刷新、初次失败、分页失败的内容保留策略写入 Spec，不由统一状态类猜测。
- base/support 可定义各自能力必要的纯技术参数/配置/结果；app 也可在所属渲染/输入实现定义相机、顶点、命中点和拖动预览等技术类型。按职责内聚维护，不因关键字是 data/enum/sealed 就搬入 model。技术类型不能成为隐藏的业务 Contract；业务事实/意图仍归 model，不产生反向依赖。
- DTO/实体/领域/UI 数据有不同语义时才拆，不强制每条数据复制四份。持久化/协议枚举使用稳定 code，不能依赖可重命名的 name。

例：样例定义位于 `com/ai/playtv/model/contract/sample/SampleContract.kt`，独立 SampleItem/SamplePage 在 bean/domain/sample，执行位于 app/ui/test/sample。基于这些路径检索已有定义，不能为新页面复制同名契约。

## 请求到图片展示：类放置与命名

下表是后续新增能力的确定目录，不表示这些类已经生成。路径从工程根目录起，短路径沿用同一行前项的包前缀；`<feature>` 是功能，`<backend>` 是确有协议差异的后端，不能把两者都叫 common。

| 类/文件 | 唯一放置位置 | 职责 |
|---|---|---|
| NetworkConfig、NetworkClientFactory | `support/src/main/java/com/ai/playtv/support/network/` | 无业务的配置、OkHttp/Retrofit 构造与复用入口 |
| 技术 Header 提供接口、网络拦截器 | `support/src/main/java/com/ai/playtv/support/network/header/`、`network/interceptor/` | 注入技术参数、传输及脱敏诊断；不引用业务单例 |
| 技术网络错误/诊断类型 | `support/src/main/java/com/ai/playtv/support/network/error/`、`network/`（NetworkDiagnostics） | 网络/HTTP/解析技术信息，无登录跳转或业务 code 判定 |
| `<Feature>Service` | `model/src/main/java/com/ai/playtv/model/network/service/<feature>/` | 路径、HTTP 注解、带类型的请求与响应；不用一个巨型 ApiService 汇总所有业务 |
| `<Action>RequestDto`、`<Action>ResponseDto` | `model/src/main/java/com/ai/playtv/model/bean/net/<feature>/` | 接口字段与序列化名；独立 DTO 不放 Service/Contract 内 |
| 共享 `<Backend>EnvelopeDto` | `model/src/main/java/com/ai/playtv/model/bean/net/common/<backend>/` | 仅该后端确实共享的响应包络；不预建全项目万能 BaseResponse |
| `<Backend>ResponsePolicy`、请求结果处理入口 | `model/src/main/java/com/ai/playtv/model/network/protocol/<backend>/` | HTTP/包络处理、业务成功校验和统一结果转换；不放 support |
| RepositoryException 等调用方错误 | `model/src/main/java/com/ai/playtv/model/network/error/` | 保留技术 cause/code/requestId，向上表达业务/协议错误；不携带 UI 文案 |
| `<Feature>Repository` | `model/src/main/java/com/ai/playtv/model/repository/<feature>/` | 数据动作、结果校验、源选择和业务转换；不再建另一套 http/repository |
| 必需的 `<Feature>RemoteDataSource` / LocalDataSource | `model/src/main/java/com/ai/playtv/model/repository/<feature>/remote/`、`local/` | 多源/替换需求出现时增加，不在 app 放业务数据源 |
| `<Feature>Mapper` 或同名映射函数文件 | `model/src/main/java/com/ai/playtv/model/mapper/<feature>/` | DTO → 领域/展示数据；简单且私有的转换可留 Repository，避免空转发文件 |
| `<Feature>Item` / `<Feature>Page` | `model/src/main/java/com/ai/playtv/model/bean/domain/<feature>/`，纯展示数据用 `bean/ui/<feature>/` | 输出数据不携带 Retrofit Response、Bitmap、Drawable 或 R |
| `<Page>State/Event/Effect` | `model/src/main/java/com/ai/playtv/model/contract/<feature>/<Page>Contract.kt` | 页面状态与意图；不塞 Job/Service/Repository |
| `<Page>Activity/Fragment/ViewModel`、`<Feature>Adapter` | `app/src/main/java/com/ai/playtv/ui/<feature>/` | 页面编排、状态渲染与目标绑定；test- Spec 使用 ui/test/<feature> |
| AppContainer、NetworkDependencies、Factory | `app/src/main/java/com/ai/playtv/di/` | 构造并持有客户端/Service/Repository，注入 ViewModel；按规模拆分，不建全局服务定位器 |
| NetworkEnvironmentConfig | `app/src/main/java/com/ai/playtv/config/` | 从本项目构建配置读取环境地址等参数，传给 support；不存生产秘密 |
| MmkvStorage / MmkvStore / MmkvKey / StorageScope | `support/src/main/java/com/ai/playtv/support/storage/mmkv/` | MMKV 技术适配、类型化原语、固定作用域租约；不认识业务 key、登录和页面 |
| `<Feature>Store` | `model/src/main/java/com/ai/playtv/model/storage/<feature>/` | 功能 key、默认值、schema、序列化、IO、读写/迁移；只在成功后发布变更 |
| `<Feature>Dependencies` / 会话子容器 | `app/src/main/java/com/ai/playtv/di/` | 组装功能的 Service/Repository/Store；明确资源所有权与 close，不能缓存 ViewModel |
| 会话 Header/鉴权策略的业务实现 | `model/src/main/java/com/ai/playtv/model/network/auth/` | 实现注入契约、读取所属会话的业务信息；app 只负责组装 |
| ImageLoader、ImageLoadOptions、图片技术结果/监听 | `support/src/main/java/com/ai/playtv/support/image/` | SDK 图片适配、加载/clear、形状、目标生命周期、缓存与技术诊断 |
| `ImageView.loadImage` 等可选薄扩展 | `support/src/main/java/com/ai/playtv/support/image/ext/` | 仅委托统一入口，不再维护第二套缓存和请求策略 |
| 业务图片地址/资源解析器 | `model/src/main/java/com/ai/playtv/model/media/<feature>/` | 仅在签名、相对地址或资源解析确有业务需求时创建；简单地址转换留 Mapper |

新文件从表中定位，不先创建所有候选类或空目录；文件名使用表中的类型名加 `.kt`，同页 State/Event/Effect 按现有规则合为 Contract。请求执行细则见 [网络请求](network-request-rules.md)，图片绑定细则见 [图片加载](image-loading-rules.md)。

## 收到需求后的固定落地顺序

1. 接口资料确定后，复用 NetworkDependencies 和 RequestExecutor；补 DTO、Service、当前后端的包络校验、Repository。已有包络策略沿用，ViewModel 不拆包络。字段映射确有语义变化才独立 Mapper，不机械增加 RemoteDataSource/接口/UseCase。
2. 需要 KV 时，默认采用已启用 MMKV，新增 model/storage/<feature>/<Feature>Store。先确定安装/用户和环境作用域，再集中 key/类型/默认值，公开 `get/set/reset` 等语义方法；读写在 IO，UI 不碰 SDK。未启用先补能力及依赖，不能只按类名调用。
3. 页面定义 model Contract、app ViewModel 和 View，列表再加 Adapter。业务计算、编排和持续资源按本文件的“业务角色的唯一判定”选择，不以数据源数量作为 UseCase 门槛，不为普通接口建立 Manager。
4. 在 app/di 的所属容器组装构造依赖，Factory 只在创建 ViewModel 时注入。应用依赖由 AppContainer 持有；会话/功能资源有自己的子容器。Application、页面各自只启动所属动作，顺序与关闭见 Manager 规则。
5. Spec 列明本功能新增文件、复用入口、初始化和释放位置。技术样例可查网络 ExampleDocumentRepository、KV DemoSettingsStore/StorageViewModel；样例不决定真实接口字段或业务设置。

## 技术样例、源集与夹具

上表的 `src/main` 对应可随产品交付的公共实现和正式功能。新增仅用于开发验证的交互技术样例默认使用 `src/debug`，其余包目录不变：app 的页面仍在 ui/test/<feature>，演示专属 DTO/Service/Repository/Contract 在 model 的对应 debug 目录；公共网络与图片工具仍在 support/main。不能只用 test 包名就认定不会进入 release。

debug 专属布局、字符串、Manifest、网络安全配置放同模块的 `src/debug`；仅该变体使用的库/工程依赖用 debugImplementation。普通单元测试源码属于 src/test，设备自动化测试属于 src/androidTest，二者不承载供开发者手动打开的 Activity。模块变体匹配和 Manifest 合并结果在授权构建时核对。

桌面 HTTP 服务、JSON/图片协议夹具与使用说明统一放 `tools/fixtures/<feature>/`，不放产品 assets/res，不由 Application 启动，不打包进 APK。夹具不调用真实业务服务、不使用生产凭据、不替代缺失设计资产。既有 main 技术预览是历史现状，不依据本规则自动批量迁移；变更入口与构建范围时在对应 Spec 说明。
