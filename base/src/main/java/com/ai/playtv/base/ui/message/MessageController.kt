package com.ai.playtv.base.ui.message

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.ai.playtv.base.ui.feedback.internal.FeedbackHost
import com.ai.playtv.base.ui.feedback.internal.checkMainThread

/** Page-scoped transient messages: replace the previous message, clear on stop, close on destroy. */
class MessageController(
    activity: FragmentActivity,
    owner: LifecycleOwner = activity,
    renderer: MessageRenderer = PlatformToastRenderer,
) : AutoCloseable {
    private var renderer: MessageRenderer? = renderer
    private var current: MessageHandle? = null
    private val host = FeedbackHost(activity, owner, ::clear, ::close)

    /** False for empty text, a stopped/destroyed host or a closed controller; no queue/replay. */
    fun show(message: CharSequence): Boolean {
        checkMainThread()
        val activity = host.visibleActivity() ?: return false
        val renderer = renderer ?: return false
        if (message.isBlank()) return false
        clear()
        current = renderer.show(activity, message)
        return true
    }

    fun clear() {
        checkMainThread()
        val previous = current
        current = null
        previous?.dismiss()
    }

    override fun close() {
        clear()
        renderer = null
        host.close()
    }
}
