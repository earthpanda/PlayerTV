package com.ai.playtv.base.ui.dialog

import android.app.Dialog
import androidx.fragment.app.FragmentActivity

/** Create only; a user dismissal must invoke onCancel, not pretend to confirm the action. */
fun interface ConfirmationDialogFactory {
    fun create(host: FragmentActivity, message: CharSequence, onConfirm: () -> Unit, onCancel: () -> Unit): Dialog
}
