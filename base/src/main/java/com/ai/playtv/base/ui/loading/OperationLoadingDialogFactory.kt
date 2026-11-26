package com.ai.playtv.base.ui.loading

import android.app.Dialog
import androidx.fragment.app.FragmentActivity

/** Create only. User cancellation invokes onCancel; the controller owns show/dismiss/listeners. */
fun interface OperationLoadingDialogFactory {
    fun create(host: FragmentActivity, message: CharSequence, cancellable: Boolean, onCancel: () -> Unit): Dialog
}
