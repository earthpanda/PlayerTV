package com.ai.playtv.base.ui.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.ai.playtv.base.R

/** Default confirmation view; knows neither the business operation nor its data. */
class CustomConfirmationDialog(
    context: Context,
    message: CharSequence,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) : AlertDialog(context) {
    init {
        setMessage(message)
        setButton(BUTTON_POSITIVE, context.getString(R.string.s_feedback_confirm)) { _, _ -> onConfirm() }
        setButton(BUTTON_NEGATIVE, context.getString(R.string.s_feedback_cancel)) { _, _ -> onCancel() }
        setOnCancelListener { onCancel() }
        setCanceledOnTouchOutside(false)
    }
}
