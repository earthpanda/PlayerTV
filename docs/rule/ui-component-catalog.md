# PlayTV 当前可用组件

本表只登记实际随工程提供的能力，API 以源码为准；新增 UI 前核对真实调用，不按原工程的类名猜测。

## 按使用场景查找

这里是自然语言描述到真实入口的唯一检索索引。场景 ID 保持稳定；名称、别称不是多套 API。命中后读对应源码/调用例；新增命名见项目约定，构建与交互见 [公共反馈](ui-feedback-rules.md)。

| 场景 ID | 常用说法 | 当前状态与推荐入口 | 边界 |
|---|---|---|---|
| `loading.page` | 首屏加载、页面转圈、空态、加载失败重试 | `PageStateLayout.render(PageStatus)`，加载态使用 `CustomLoadingView`；SampleActivity.render 调用 | 由 State 渲染首屏阶段；不代替提交遮罩；本次样式迁移已构建安装，未功能验收 |
| `loading.refresh` | 刷新、加载更多 | 未启用 refresh；SampleActivity.render 以 State 控制文字动作；无手势容器 | 需要手势刷新时按需启用，不调用未生成 SDK |
| `loading.operation` | 保存中、提交中、公共 loading、操作遮罩 | 用 `createOperationLoadingController(host)` 创建项目样式的 `OperationLoadingController`，再调用 `render`；`SourceEntryActivity` 使用 | 随宿主关闭窗口；取消通过回调交给任务所有者；本次样式迁移已构建安装，未功能验收 |
| `message.transient` | toast、轻提示、操作成功/失败提示 | `MessageController` 默认实现用于 debug 来源解析和选台 HTTP 403；SampleActivity 仍使用系统 Toast | 设备验收待完成 |
| `dialog.confirmation` | 二次确认、删除前确认、确定取消 | 已接入 `ConfirmationController` 默认实现 | 尚无项目业务调用；设置引导不代替确认 |
| `loading.inline` | 按钮加载、卡片局部转圈 | `CustomLoadingView`；`SourceEntryActivity` 用于历史读取状态 | 宿主按 State 调用 `showLoading` / `hide`；`setSpinnerOnlyMode` 去除卡片；不阻塞其他区域；未运行验证 |
| `dialog.settings_redirect` | 不再弹权限、去设置、设置引导 | 未启用 permissions；未生成设置引导框与权限控制器 | 按需启用后读能力说明，不自行猜 API |
| `dialog.bottom_sheet` | 底部弹窗、底部菜单、操作面板 | 尚未交付公共底部组件 | 按实际窗口类型、Insets 与关闭行为实现，不猜参考类名 |

## 组件登记方式

每个场景指定一个默认入口；有多个合法变体时写清选择条件。状态用“已交付/已启用”“未启用”“仅参考”“尚未实现”区分，构建与运行验收另列；未启用包不得写成当前可调用。下表登记真实组件；缺项只列在上方场景表，不伪装为源码。

新增公共组件时，在本文件补全：场景 ID 与常用说法；真实文件/API/import 与推荐入口；至少一个真实调用；适用/不适用场景；参数/样式替换；所有者、关闭/取消、重复操作；实际验证状态。同一事实在本文件维护，Spec 只链接并记录本功能差异。

实际 API 片段与完整登记字段见 [接入与登记示例](../reference/ui-feedback/README.md)。该文档是使用说明，不表示已经交付新的反馈控制器。

替换公共入口时更新场景目标和使用依据，并说明尚未迁移的调用范围。已有组件改名或扩展先检查调用，不为符合新命名制造第二套 Toast/Loading/Dialog。

## 已交付组件

| 能力 | 源码与使用依据 | 边界 |
|---|---|---|
| BaseActivity / ScreenUnits | base/src/main/java/com/ai/playtv/base/ui、util；BaseMviActivity 使用 | pt 宽度读取根目录 design-config.properties；空值仅供技术样例；透明系统栏和单次根 inset；无账号/统计 |
| BaseMviActivity / BaseMviFragment / BaseMviViewModel | app/src/main/java/com/ai/playtv/ui、domain；技术样例 Activity 使用前者 | 强类型契约和生命周期收集；Fragment 的 Binding 随 view 销毁；Effect 固定容量 64，setEffect 返回入队结果，满/关闭时拒绝新项并逐次记录 WARN（ViewModel/Effect 类型、原因、容量，不含载荷），不启动发送协程 |
| ViewModel Factory | app/src/main/java/com/ai/playtv/di/ViewModelFactories.kt | 显式创建，没有业务单例或 DI SDK |
| AppContainer / StartupStep | app/di/AppContainer、AppApplication；base/diagnostics/StartupStep；样例 Factory 实际使用 | 明确属性和初始化依赖，成功后发布，无 ViewModel 缓存；成功耗时需启用 INFO |
| PageStateLayout | app/src/main/java/com/ai/playtv/view/state；样例页面使用 | model PageStatus 的 UI 渲染，不把状态定义放回 base |
| CustomLoadingView | app/src/main/java/com/ai/playtv/view/loading；页面状态、来源历史和项目操作加载使用 | 半透明深色卡片、蓝色圆环及可选文字，也支持纯圆环、成功和短暂成功/失败；可见时循环旋转，隐藏或脱离窗口停止；最新外观未构建及功能验收 |
| OperationLoadingController | base/src/main/java/com/ai/playtv/base/ui/loading；项目外观通过 app 的 `createOperationLoadingController` 注入；SourceEntryActivity 的解析操作 | Main 线程 render 操作 ID；宿主停止时隐藏、销毁时关闭；可取消操作通过返回键回调给 ViewModel，不显示取消按钮；base 默认样式仍供未注入的调用使用；最新外观未构建及功能验收 |
| MessageController / ConfirmationController | base/src/main/java/com/ai/playtv/base/ui/message、dialog | MessageController 已用于 debug 来源入口与播放页的 HTTP 403 提示；ConfirmationController 暂无本项目业务调用，设备验收待完成 |
| AndroidX ListAdapter | ui/test/sample/SampleAdapter.kt、item_sample.xml | 技术示例，正式列表按业务定义 Adapter，不复制数据模型或引入 MultiType |
| DiagnosticLog | base/diagnostics；MVI、网络/图片失败和文件清理调用 | 默认 WARN/ERROR，结构化标量，不输出原始消息/URL/body，不上传 |
| AppDirectories / AtomicFileStore / UriAccess | base/storage；base/src/debug/example/FoundationUsageExamples 调用 | 路径解析及 Uri 授权是挂起方法，IO 执行；流式限额、新文件原子提交及本任务临时清理；无跨进程写保证 |
| KeyboardController / SystemBarInsets | base/ui；BaseActivity 和 debug 示例调用 | 宿主关闭待执行任务；系统栏逐边消费、首次 padding，编辑页可显式 includeIme=true |
| TimeFormats / TextViewExtensions | base/util；base/src/debug 示例调用 | 明确时间单位/时区，pt 字号/行高，无业务设置依赖 |


本项目未启用 refresh；没有生成刷新容器及相关 SDK 依赖，列表通过文字动作刷新和加载。

本表按本项目实际源码维护；`.template-lock.json` 只记录初始化选择，后续增删能力时不能用旧 lock 覆盖当前事实。

尚未提供：公共业务导航栏、BottomDialog/OverlayMenu、菜单、下载器、分享、Widget、账号/支付等。普通确认与操作 Loading 按 feedback 的实际启用状态使用；permissions 的设置引导仍单独负责。新增时先明确业务与技术边界；Loading 使用已登记的本地资源。

公共实现与按需启用状态如下；源码与 src/debug 调用示例已交付，未编译运行。启用 mmkv 会提供独立存取演练页面，网络标准调用示例见对应能力说明。

- `network`：已接入模板公共源码及依赖，debug 地址解析实际使用；运行验证见功能 Spec。
- `image`：未启用，不生成其源码或 SDK 依赖。
- `sse`：未启用，不生成其源码或 SDK 依赖。
- `websocket`：未启用，不生成其源码或 SDK 依赖。
- `permissions`：未启用，不生成其源码或 SDK 依赖。
- `feedback`：已接入 base 公共源码与资源，地址输入页使用操作 Loading；未引入模板演练页。
- `mmkv`：未启用，不生成其源码或 SDK 依赖。
- `preferences`：未启用，不生成其源码或 SDK 依赖。
- `room`：未启用，不生成其源码或 SDK 依赖。
- `reminders`：未启用，不生成其源码或 SDK 依赖。

本项目未启用 room，没有 Room SDK、数据库工厂或同步器源码。需要时先按数据库与同步规则补齐能力。

本项目未启用 reminders，未生成提醒平台入口及对应权限声明。
