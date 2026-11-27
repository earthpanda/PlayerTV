package com.ai.playtv.view.state

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.ai.playtv.R
import com.ai.playtv.model.state.common.PageStatus
import com.ai.playtv.view.loading.CustomLoadingView

/**
 * App-owned renderer of model PageStatus; base stays independent of business/page contracts.
 * Call render(status) and set onRetry = { viewModel.onEvent(Refresh) } in the screen.
 */
class PageStateLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val stateBackground = background
    /** UI callback remains in the renderer; never serialized into a page State. */
    var onRetry: (() -> Unit)? = null
    /** Progress indicator belonging to the renderer's layout. */
    private val progress: CustomLoadingView
    /** Localized phase message. */
    private val message: TextView
    /** Retry is a text action, following the project's button convention. */
    private val retry: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_page_state, this, true)
        progress = findViewById(R.id.stateProgress)
        message = findViewById(R.id.stateMessage)
        retry = findViewById(R.id.stateRetry)
        retry.setOnClickListener { onRetry?.invoke() }
    }

    /** Update a mutually exclusive first-page phase without deciding business recovery. */
    fun render(status: PageStatus) {
        isVisible = status != PageStatus.Content
        val loading = status == PageStatus.Loading || status == PageStatus.Initial
        background = if (loading) null else stateBackground
        if (loading) progress.showLoading(context.getString(R.string.s_common_loading)) else progress.hide()
        message.isVisible = !loading
        retry.isVisible = status == PageStatus.Error
        message.setText(when (status) {
            PageStatus.Empty -> R.string.s_common_empty
            PageStatus.Error -> R.string.s_common_load_failed
            else -> R.string.s_common_loading
        })
    }
}
