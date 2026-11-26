# 技术样例需求与实现说明

类型：模板技术预览。用户授权制作四模块骨架与刷新能力包；本例只用于检查结构与调用方式，不包含正式产品业务。

- 设计：无 MCP/品牌设计要求；只用文字、系统进度控件与库自带的经典刷新组件，不生成或下载设计资产。
- 页面：启动 Activity 位于 `com.ai.playtv.ui.test.sample`，无入口参数；普通系统返回退出。正式功能应更换启动页。
- 数据：SampleRepository 的确定性本地数据，每页 10 项，共 3 页；250ms 的可取消示例延迟不是业务超时。无网络、数据库、账号、权限或持久化。
- 定义：`com.ai.playtv.model.contract.sample.SampleContract.kt` 包含 State/Event/Effect；独立 SampleItem/SamplePage 在 model/bean/domain/sample；PageStatus 在 model/state/common。
- 初始：首次 Initialize 加载第 0 页，重建后不重复初始请求；无数据时显示 Loading，成功进入 Content/Empty。
- 刷新：忙时不重复请求；成功替换第一页并重置 nextPage，失败保留已有内容，首次失败显示 Error 与重试。
- 分页：只有 nextPage 非空且非忙时加载，成功追加，最后一页将 nextPage 置空。失败保留已有内容和页码供重试。
- 点击：Event 带 ID，Effect 带同一 ID，Activity 使用字符串资源反馈。没有 View/Context/R 进入 model 契约。
- 能力：`none`。未启用 refresh 时使用文字动作；启用时安全刷新容器回调事件，State 结束动画并控制无更多数据，不引入 PAG 或 MultiType。
- 并发/恢复：同一 ViewModel 忙时拒绝重复刷新/分页，不提供搜索替换或跨请求协调器。ViewModel 存活的配置重建保留内存状态；进程死亡后重新加载确定性本地数据，不恢复页码或关键业务结果。
- Manager/存储/权限：无长期业务资源、持久化和系统权限申请，不涉及账号/环境切换、文件清理、迁移或系统设置返回。
- 生命周期/导航：沿用 UI 基类的生命周期收集和根 systemBars 处理，无弹窗/IME/局部 Insets 策略；Effect 仅作即时反馈，不保证进程重建后重放。
- 准则落地范围：区分首屏/刷新/分页失败，修正刷新动画结束与 Effect 队列边界；保持本地数据、现有布局与模块方向，不增加业务接口或 SDK。
- 失败契约：`SampleLoadFailure` 区分 Initial/Refresh/LoadMore；State 保留最近失败阶段，下一次请求开始时清除。失败 Effect 携带同一阶段，由 UI 映射中英文提示；首屏显示 Error，刷新/追加失败保留原内容与页码，空列表刷新失败仍保留 Empty。
- 取消与并发：刷新/分页串行，LoadMore 只能在已有 Content 时执行；取消恢复请求前的页面阶段和失败标识并复位忙状态，继续抛出 CancellationException，不显示失败提示。
- 刷新反馈：启用 refresh 时，按 SDK 当前动画与最新 State 决定是否结束，首屏/刷新失败返回失败动画，分页失败不置无更多；不要求 StateFlow 消费者看到全部中间 loading。手势请求未进入加载时，也根据当前快照结束对应空转动画。
- Effect 容量：基础类固定最多缓存 64 条瞬时 Effect，`setEffect` 返回是否入队；满/关闭时拒绝新项，不为发送启动协程。样例仅发送可丢弃的 Toast，失败事实仍存 State，关键业务完成不可依赖此队列。
- 验证边界：本轮只做源码/资源/生成结果静态核对；首屏失败、刷新失败、分页失败、快速成功和取消路径列为后续运行验证场景。本地数据源仍不主动制造异常，不声称已经运行覆盖这些分支。
- 实际状态：源文件已生成，未构建/运行验证；仓库可达性、完整构建组合、旋转/返回时序和手势仍需在用户授权后验证。失败分支已表达，但本地样例未人为制造失败，不声称网络异常已验证。
