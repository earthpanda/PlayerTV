# PlayTV 项目约定

本文件是技术、代码命名、单位与设计资产政策的唯一来源；工作流程见 [AGENTS.md](../../AGENTS.md)，模块职责见 [数据契约](data-contract-rules.md)。

本项目采用的方案与准则演进方式见 [项目基线](project-context.md)。本文件属于 PlayTV，后续根据本项目已确认的决策维护，不要求与初始化原型保持相同。

业务执行见 [业务开发](business-development-rules.md)，Manager/作用域见 [Manager 规则](manager-lifecycle-rules.md)，文件/KV/数据库见 [存储规则](file-storage-rules.md)，系统授权见 [权限规则](permission-rules.md)，工具与扩展见 [公共能力](common-capability-rules.md)。这些规则随工程交付，具体 API 以源码为准。

公共代码优先复用 [公共基础](common-foundation.md)，标准源码和扩展点以组件目录及能力说明为准；项目差异通过配置/策略接入，修改公共实现须记录原因和边界。

## 技术与页面

接口到 UI 的固定链路见 [网络请求](network-request-rules.md)，运行时图片见 [图片加载](image-loading-rules.md)；各类的明确目录统一查 [数据契约](data-contract-rules.md)。这些默认写法不依赖某个项目的地址、成功码或页面字段；实际 SDK 是否已接入以依赖/组件目录为准。

- Kotlin、XML、ViewBinding、自定义 MVI、手动 Intent/Activity Result；不使用 Compose、MultiType 或自动引入的 DI 框架。
- 普通页面主体默认 ConstraintLayout；具体布局映射只读 [MCP 手册](mcp-implementation-guide.md)。文字操作使用可点击 TextView，不新增业务 Button/MaterialButton。
- 无系统 ActionBar。系统栏、Insets、键盘及页面资源释放的规则与当前实现边界统一见 [UI 生命周期](android-ui-lifecycle-rules.md)。
- 先查 [组件目录](ui-component-catalog.md)。无独立行为的重复结构优先 style/include/merge；有独立状态和交互再抽 View。跨功能业务 UI 在 app/view，基础平台 UI 在 base；具体模块边界按数据契约。
- 可复用组件命名为 Custom + 职责 + 真实类型后缀，例如 CustomSettingsRedirectDialog、CustomReminderEditorView；DialogFragment 必须保留 DialogFragment 后缀。目录按数据契约的“自定义 View、功能弹窗与展示扩展”，布局用 dialog_<purpose>/view_<purpose>。Controller/Factory 不加 Custom。SafeSmartRefreshLayout 是既有补丁类的保留名称；继承关系以源码为准。
- 类、接口、方法和成员字段写明职责、含义与生命周期；可复用类给典型调用方式。阈值、单位、状态机、跨模块流程说明来源，禁止翻译代码字面的废话注释。
- 用户可见文本放 strings.xml；默认中文及 values-en 同步维护，key 为 `s_feature_description`。静态文案和 R 由 UI 解析，不能搬入 model State。

## 公共反馈的命名

名称按“用途 + 职责”表达，不按圆形/方形、颜色或临时业务叫法区分同一能力。Loading、Toast、Dialog 是不同展示职责，不合成 CommonUiManager。行为和构建见 [公共反馈](ui-feedback-rules.md)，真实入口以组件目录为准。

| 角色 | 新增时的统一命名示例 | 说明 |
|---|---|---|
| 操作加载展示 | `CustomOperationLoadingView` / `CustomOperationLoadingDialog` | 按实际承载选 View 或 Dialog；不把局部/首屏加载统称 LoadingDialog |
| 操作加载协调 | `OperationLoadingController` | 只有需要持有展示和协调任务时创建；不加 Custom |
| 短暂消息展示 | `CustomMessageView` | 自定义浮层视图；系统 Toast 不伪装为自定义 View |
| 短暂消息入口 | `MessageController`，确需扩展时用 `MessageRenderer` | 对调用方表达提示职责；Toast 为默认实现时在目录登记别称和限制 |
| 通用二次确认 | `CustomConfirmationDialog` / `ConfirmationDialogFactory` | 真实类型为 DialogFragment 时改用 DialogFragment 后缀 |
| 前往系统设置 | `CustomSettingsRedirectDialog` / `SettingsRedirectDialogFactory` | 沿用已交付角色；不是系统权限弹框 |
| 视觉创建扩展 | `<Purpose>ViewFactory` / `<Purpose>DialogFactory` | 只负责创建；管理展示生命周期的角色使用 Controller |

表中名称是新增实现的约定，不代表所有类都已存在，不预建整套角色。`PageStateLayout`、`SafeSmartRefreshLayout` 为已有名称，登记场景别称即可发现，不为命名整齐破坏现有调用。功能专用组件按同一规则带明确功能名，如 `CustomReminderTimeDialog`；不因为共享同一基类就改叫通用确认框。

代码文件与类型同名；新增布局用 `view_operation_loading.xml`、`view_message.xml`、`dialog_confirmation.xml` 等用途名，style 用明确组件/变体职责。时长/大小/颜色是参数或样式，不另造 `SquareLoading`、`ToastUtils2` 等平行入口。目录唯一约定见数据契约，实际资产仍保持正式资源名规则。

## 尺寸单位

- 设计宽度唯一配置入口为根目录 [design-config.properties](../../design-config.properties) 的 `design.width`，base 在构建时生成 BuildConfig 字段供 ScreenUnits 使用；BaseActivity 进行 xdpi 适配，设计坐标统一理解为 pt。修改配置后重新构建并重启，不改 Kotlin、不重跑初始化器。
- 宽度填写设计画板的逻辑宽度（整数 100–2000），不是导出位图的像素宽度。留空表示未确认，技术样例暂按当前系统 dp 跨度适配；不能视为设计稿宽度，也不能据此实现正式设计。正式页面开发前确认并填写宽度；变更后核对所有普通页面的尺寸和文字。模板 lock 只记录初始化历史，不是当前宽度来源。
- 普通页面 XML 使用 pt；代码从 `com.ai.playtv.base.util.pt` 导入 Int.pt/Float.pt，文字复用 TextView.setTextPtSize，不自行新增换算器。`0dp` 可以表示约束/权重语义值。
- 样式使用已有 token；一次性尺寸可直接写 pt，不为每个数值新增 dimen。新 UI Kit 替换样例 token 后同步设计系统。
- App Widget/RemoteViews 是单位例外：使用 dp 和宿主 SizeF，以单一等比系数缩放设计画布、文字及圆角。本工程尚未提供 Widget 实现。

## 设计资源提供与缺失处理（强制）

1. SVG、图标、图片、动效及其他外部设计资产全部由用户手动下载/导入。AI 只核对和引用本地文件，不从 MCP、浏览器、URL 下载或导出，不通过 PATH、截图裁切、生图、系统/相似图标、占位图补齐。
2. 本地资源名与 MCP 正式名称一致。只做确定性 Android 规范化：去扩展名、转小写、空格/斜杠/连字符转下划线并合并连续下划线。不加业务前缀、编号或猜测别名。
3. 无正式名称、非法名称、规范化冲突、同名身份不明时停止并请用户明确。相似外观不能证明可互换，异名映射需明确授权并限定场景。
4. 默认位置为 app/res 下 drawable 的同名 VectorDrawable、drawable-nodpi 的同名位图，以及加载链支持的 assets/raw 文件。仅有 SVG 源文件不等于当前 Android 可渲染；不支持时提示用户手动导入同名 VectorDrawable 或提供可用格式，AI 不自动转换或追加 SVG 库。
5. 实现前核对全部已知状态所需资产及格式、身份、单色/多色、tint。缺任一必需项直接停止当前功能实现，一次列出“MCP 原名、fileKey/nodeId（未知如实写未知）、期望本地路径、缺失/不支持/冲突原因与需要格式”。不得交付带占位的业务页面。
6. 保留用户文件的路径、viewport、填充及多色效果，不自动白色化或重绘。普通单色图标可在使用处按 token tint；多色/品牌图保留原色。更新先核对影响范围，删除仍需授权。
7. 数值 token、普通 shape/selector/布局可按设计编写，但不得借此重绘图标或插画。业务运行时图片加载与本地设计资产缺失是不同事项。

资源事实只在 [资产登记](design-resource-map.md) 维护。当前技术样例没有 MCP 资产要求；其纯文字布局不是正式业务设计或缺失资产的替代方案。

## 数据、依赖与收尾

数据和类型定义归属只在数据契约维护；库与版本只在依赖目录/Gradle 维护。当前没有网络协议和业务成功码，不得凭旧项目经验补入 Sony 域名、鉴权、默认分页大小或错误码。

新增 JSON/反射入口时明确序列化字段名、泛型/注解与有针对性的 R8 保护，不能用全包 keep 掩盖定义混乱。当前样例模型不序列化。完成后检查本功能旧分支、无效字段/资源与兼容处理，记录待决策项；删除和实际验证仍按 AGENTS 授权。
