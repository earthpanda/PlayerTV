package com.ai.playtv.view.loading

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.ai.playtv.R

/** Project loading card; its host owns visibility and may supply a message or result. */
class CustomLoadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val icon: ImageView
    private val message: TextView
    private val card: View
    private var spinning = false
    private var transientResult = false
    private var animator: ObjectAnimator? = null
    private val dismissResult = Runnable { hide() }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_custom_loading, this, true)
        icon = findViewById(R.id.loading_icon)
        message = findViewById(R.id.loading_text)
        card = findViewById(R.id.loading_card)
        clipChildren = false
        clipToPadding = false
        visibility = GONE
    }

    /** Render a persistent operation; repeated calls do not restart its animation. */
    fun showLoading(text: CharSequence? = null) {
        removeCallbacks(dismissResult)
        transientResult = false
        spinning = true
        icon.setImageResource(R.drawable.page_loading_toast)
        icon.imageTintList = ColorStateList.valueOf(context.getColor(R.color.loading_label))
        setMessage(text)
        visibility = VISIBLE
        updateAnimation()
    }

    /** Result feedback stays visible unless the transient variant is requested. */
    fun showSuccess(text: CharSequence) = showResult(R.drawable.checkbox_circle_filled, text, null, false)

    fun showTransientSuccess(text: CharSequence) = showResult(R.drawable.checkbox_circle_filled, text, null, true)

    fun showTransientFailure(text: CharSequence) = showResult(R.drawable.warning_filled, text, R.color.loading_error, true)

    /** Remove the card surface when the host already provides a suitable background. */
    fun setSpinnerOnlyMode() {
        card.background = null
        card.elevation = 0f
        card.setPadding(0, 0, 0, 0)
    }

    fun hide() {
        removeCallbacks(dismissResult)
        transientResult = false
        spinning = false
        visibility = GONE
        updateAnimation()
    }

    private fun showResult(drawable: Int, text: CharSequence, tint: Int?, transient: Boolean) {
        removeCallbacks(dismissResult)
        transientResult = transient
        spinning = false
        updateAnimation()
        icon.setImageResource(drawable)
        icon.imageTintList = tint?.let { ColorStateList.valueOf(context.getColor(it)) }
        setMessage(text)
        visibility = VISIBLE
        if (transient) postDelayed(dismissResult, RESULT_DURATION_MS)
    }

    private fun setMessage(text: CharSequence?) {
        message.text = text
        message.isVisible = !text.isNullOrBlank()
    }

    private fun updateAnimation() {
        if (spinning && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            if (animator?.isRunning == true) return
            animator = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
                duration = ROTATION_DURATION_MS
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            animator?.cancel()
            animator = null
            icon.rotation = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dismissResult)
        if (transientResult) {
            transientResult = false
            visibility = GONE
        }
        animator?.cancel()
        animator = null
        icon.rotation = 0f
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ROTATION_DURATION_MS = 800L
        const val RESULT_DURATION_MS = 1_500L
    }
}
