package com.ai.playtv.base.ui.loading

import android.app.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.ai.playtv.base.ui.feedback.internal.FeedbackHost
import com.ai.playtv.base.ui.feedback.internal.checkMainThread

/** State renderer of a single active operation. Pass a new ID when explicitly replacing a task. */
class OperationLoadingController(
    activity: FragmentActivity,
    owner: LifecycleOwner = activity,
    factory: OperationLoadingDialogFactory = OperationLoadingDialogFactory(::CustomOperationLoadingDialog),
) : AutoCloseable {
    private var factory: OperationLoadingDialogFactory? = factory
    private var dialog: Dialog? = null
    private var generation: Any? = null
    private var operationId: Long? = null
    private var message: String? = null
    private var cancellable = false
    private val host = FeedbackHost(activity, owner, ::clear, ::close)

    /**
     * Idempotent Main-thread rendering; null clears. onCancel receives only the still-current ID.
     * Stop hides the window, not the task; the UI re-renders its latest State after returning.
     */
    fun render(id: Long?, text: CharSequence, cancellable: Boolean, onCancel: (Long) -> Unit): Boolean {
        checkMainThread()
        val activity = host.visibleActivity()
        val factory = factory
        if (id == null || activity == null || factory == null) {
            clear()
            return false
        }
        val snapshot = text.toString()
        if (operationId == id && message == snapshot && this.cancellable == cancellable && dialog?.isShowing == true) {
            return true
        }
        clear()
        val token = Any()
        generation = token
        operationId = id
        message = snapshot
        this.cancellable = cancellable
        val created = factory.create(activity, snapshot, cancellable) {
            if (generation === token && host.visibleActivity() != null && cancellable) {
                clear()
                onCancel(id)
            }
        }
        dialog = created
        created.setOnDismissListener {
            if (generation === token) clear()
        }
        try {
            created.show()
        } catch (error: RuntimeException) {
            clear()
            throw error
        }
        return true
    }

    /** Invalidate callbacks before dismissing. This does not cancel business work. */
    fun clear() {
        checkMainThread()
        generation = null
        operationId = null
        message = null
        val previous = dialog
        dialog = null
        previous?.setOnDismissListener(null)
        previous?.dismiss()
    }

    override fun close() {
        clear()
        factory = null
        host.close()
    }
}
