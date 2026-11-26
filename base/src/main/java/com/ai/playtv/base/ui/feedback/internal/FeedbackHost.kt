package com.ai.playtv.base.ui.feedback.internal

import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Internal host ownership shared by feedback controllers; never stored in application scope. */
internal class FeedbackHost(
    activity: FragmentActivity,
    lifecycleOwner: LifecycleOwner,
    private val onStopFeedback: () -> Unit,
    private val onDestroyFeedback: () -> Unit,
) : DefaultLifecycleObserver, AutoCloseable {
    private var activity: FragmentActivity? = activity
    private var owner: LifecycleOwner? = lifecycleOwner

    init {
        checkMainThread()
        require(lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "Feedback owner is destroyed" }
        lifecycleOwner.lifecycle.addObserver(this)
    }

    /** Null means this instance must not create a window or deliver a user action. */
    fun visibleActivity(): FragmentActivity? {
        checkMainThread()
        val host = activity ?: return null
        val lifecycle = owner?.lifecycle ?: return null
        return host.takeIf {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !it.isFinishing && !it.isDestroyed
        }
    }

    override fun onStop(owner: LifecycleOwner) = onStopFeedback()
    override fun onDestroy(owner: LifecycleOwner) = onDestroyFeedback()

    /** Remove the observer and release all references owned by this helper. Idempotent. */
    override fun close() {
        checkMainThread()
        owner?.lifecycle?.removeObserver(this)
        owner = null
        activity = null
    }
}

/** Controller state and Android View/window calls are confined to Main. */
internal fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Feedback must run on the main thread" }
}
