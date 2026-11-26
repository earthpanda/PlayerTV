package com.ai.playtv.base.ui.dialog

import android.app.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.ai.playtv.base.ui.feedback.internal.FeedbackHost
import com.ai.playtv.base.ui.feedback.internal.checkMainThread

/** Host-scoped confirmation rendering. The action lives in State, not in a global Dialog singleton. */
class ConfirmationController(
    activity: FragmentActivity,
    owner: LifecycleOwner = activity,
    factory: ConfirmationDialogFactory = ConfirmationDialogFactory(::CustomConfirmationDialog),
) : AutoCloseable {
    private var factory: ConfirmationDialogFactory? = factory
    private var dialog: Dialog? = null
    private var actionId: Long? = null
    private var message: String? = null
    private var generation: Any? = null
    private val host = FeedbackHost(activity, owner, ::clear, ::close)

    /** Same current ID/text keeps one window. A stopped host cannot deliver stale confirmations. */
    fun render(id: Long?, text: CharSequence, onConfirm: (Long) -> Unit, onCancel: (Long) -> Unit): Boolean {
        checkMainThread()
        val activity = host.visibleActivity()
        val factory = factory
        if (id == null || activity == null || factory == null) {
            clear()
            return false
        }
        val snapshot = text.toString()
        if (actionId == id && message == snapshot && dialog?.isShowing == true) return true
        clear()
        val token = Any()
        generation = token
        actionId = id
        message = snapshot
        fun finish(callback: (Long) -> Unit) {
            if (generation !== token || host.visibleActivity() == null) return
            clear()
            callback(id)
        }
        val created = factory.create(activity, snapshot, { finish(onConfirm) }, { finish(onCancel) })
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

    /** Host cleanup never reports business success or user cancellation. */
    fun clear() {
        checkMainThread()
        generation = null
        actionId = null
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
