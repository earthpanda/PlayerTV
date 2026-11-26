package com.ai.playtv.base.ui.loading

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.ai.playtv.base.R

/** Neutral platform-style operation indicator. Cancellation is an intent, never direct task access. */
class CustomOperationLoadingDialog(
    context: Context,
    message: CharSequence,
    cancellable: Boolean,
    onCancel: () -> Unit,
) : AlertDialog(context) {
    init {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_operation_loading, null)
        content.findViewById<TextView>(R.id.operation_loading_message).text = message
        setView(content)
        setCancelable(cancellable)
        setCanceledOnTouchOutside(false)
        if (cancellable) {
            setButton(BUTTON_NEGATIVE, context.getString(R.string.s_feedback_cancel)) { _, _ -> onCancel() }
            setOnCancelListener { onCancel() }
        }
    }
}
