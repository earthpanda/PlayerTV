package com.ai.playtv.view.loading

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.ai.playtv.R
import com.ai.playtv.base.ui.loading.OperationLoadingController
import com.ai.playtv.base.ui.loading.OperationLoadingDialogFactory

/** Project appearance for the existing operation controller; cancellation remains an intent. */
class CustomOperationLoadingDialog(
    host: FragmentActivity,
    message: CharSequence,
    cancellable: Boolean,
    onCancel: () -> Unit,
) : Dialog(host) {
    init {
        val content = LayoutInflater.from(host).inflate(R.layout.dialog_project_operation_loading, null)
        setContentView(content)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        content.findViewById<CustomLoadingView>(R.id.operation_loading_view).showLoading(message)
        content.findViewById<TextView>(R.id.operation_loading_cancel).apply {
            isVisible = cancellable
            setOnClickListener { cancel() }
            if (cancellable) requestFocus()
        }
        setCancelable(cancellable)
        setCanceledOnTouchOutside(false)
        if (cancellable) setOnCancelListener { onCancel() }
    }
}

/** Create a controller with the project's default operation-loading appearance. */
fun createOperationLoadingController(host: FragmentActivity): OperationLoadingController =
    OperationLoadingController(host, factory = OperationLoadingDialogFactory(::CustomOperationLoadingDialog))
