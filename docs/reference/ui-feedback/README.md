# 公共反馈的接入与登记示例

从 [组件目录](../../rule/ui-component-catalog.md) 按“加载/轻提示/确认/去设置”等描述定位真实入口；行为与新增交付遵循 [公共反馈规则](../../rule/ui-feedback-rules.md)。本文件演示现有 API 和登记方法，选填运行实现由 feedback 包交付。

本项目未启用 feedback；没有生成公共消息、操作 Loading 和通用确认控制器。需要时按模板的 feedback 包逐项接入，不能仅复制下面的文档片段就当已经安装。

## 已有页面状态怎样使用

真实源码：[PageStateLayout](../../../app/src/main/java/com/ai/playtv/view/state/PageStateLayout.kt)、[SampleActivity](../../../app/src/main/java/com/ai/playtv/ui/test/sample/SampleActivity.kt)、[PageStatus](../../../model/src/main/java/com/ai/playtv/model/state/common/PageStatus.kt)。

典型方法只需要已有组件和回调，不建立另一个 LoadingController：

```kotlin
import com.ai.playtv.view.state.PageStateLayout
import com.ai.playtv.model.state.common.PageStatus

// 在宿主安装一次监听；State 更新时只 render。
fun bindPageFeedback(view: PageStateLayout, onRetry: () -> Unit) {
    view.onRetry = onRetry
}

fun renderPageFeedback(view: PageStateLayout, status: PageStatus) {
    view.render(status)
}
```

实际页面按 SampleActivity 的 render(state) 调用。State、加载动作和页面文案不搬进基础 View；刷新和提交也不复用首屏 Loading 状态。

## 已有短暂提示怎样识别

SampleActivity.handleEffect 已展示“业务 Effect → UI 字符串 → 系统 Toast”的真实调用：

```kotlin
import android.content.Context
import android.widget.Toast

// 演示当前平台 API，不是新的公共 showMessage 入口。
fun platformToastExample(host: Context, localizedMessage: String) {
    Toast.makeText(host, localizedMessage, Toast.LENGTH_SHORT).show()
}
```

项目需要统一样式、时长、取消和重复策略时，先查是否已启用 feedback，优先复用 MessageController 及展示扩展；未启用则按实际需求接入。不要直接把这段函数复制到每个页面，或把系统 Toast 登记成已经有自定义成功/失败皮肤。

## 修改现有设置引导框样式

先查组件目录的 `dialog.settings_redirect` 是否已启用，再读本项目 permissions 能力说明和 debug 的 PermissionUsageExample。已启用时的实际扩展为：

```kotlin
import android.app.Dialog
import androidx.fragment.app.FragmentActivity
import com.ai.playtv.base.permission.PermissionActionController
import com.ai.playtv.base.ui.dialog.SettingsRedirectDialogFactory

// 在宿主 onCreate、STARTED 前调用一次；不放在 render 中。
// createDialog 由项目提供，只创建 Dialog 并接入 onConfirm。
fun createPermissionFeedback(
    host: FragmentActivity,
    createDialog: (FragmentActivity, String, () -> Unit) -> Dialog,
): PermissionActionController = PermissionActionController(
    host,
    settingsDialogFactory = SettingsRedirectDialogFactory { activity, message, onConfirm ->
        createDialog(activity, message, onConfirm)
    },
)
```

控制器内部管理展示和宿主退出清理，宿主销毁调用 close；项目的 Dialog 不自行跳设置、不申请权限、不覆盖控制器关闭监听。这里只替换设置引导的外观，不把它改为删除确认。未启用 permissions 的工程不能复制这段依赖其类型的代码。

## 新公共组件怎样登记

例如需求明确需要“保存中可取消的操作加载”：先在功能 Spec 确定动作、阻塞/取消与真实设计；命中 `loading.operation` 后检查实际启用状态，复用、接入或扩展。只有确实缺少能力才新增实现，不预设所有局部加载都使用 Dialog，不默认引入新 SDK。

交付后在组件目录更新原场景行，并增加以下字段；所有“真实路径/API/调用”都要填已存在的文件，不能把本段描述当已实现组件：

```text
场景 ID：loading.operation
常用说法：保存中 / 提交中 / 操作 loading / 操作遮罩
默认入口：实际公开类型、import、方法签名
实现：真实源码和布局路径
真实调用：页面或 debug 示例文件及方法
适用范围：操作加载；不代替首屏或列表刷新
配置：文案、阻塞、可取消、样式或实际存在的创建工厂
所有者：具体 Activity 或 Fragment view；停止/销毁如何处理
取消与并发：关闭展示是否取消任务、旧动作如何失效
状态：已交付/未启用/仅参考/尚未实现之一
验证：分别填写静态、构建、运行，未执行就写未执行
```

以后用户说“保存时用公共 loading”，AI 按这个 ID 找唯一默认入口；换样式更新同一入口的扩展实现和登记，不新增另一个全局 LoadingManager。
