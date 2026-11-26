package com.ai.playtv.ui.test.tvplayback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ai.playtv.R
import com.ai.playtv.base.util.pt

/** Debug preview progress track. It reports targets; the player remains the source of truth. */
class CustomPlaybackProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    var durationMs: Long? = null
    var positionMs: Long = 0
        set(value) { field = value; invalidate() }
    var canSeek: Boolean = false
        set(value) { field = value; invalidate() }
    var onTracking: ((Long?) -> Unit)? = null
    var onFinishTracking: ((Long?) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tracking = false

    init { contentDescription = context.getString(R.string.s_tv_preview_progress_description) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 4.pt.toFloat()
        val right = (width - left).coerceAtLeast(left)
        val radius = 2.pt.toFloat()
        val center = height / 2f
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawRoundRect(left, center - radius, right, center + radius, radius, radius, paint)
        val duration = durationMs ?: return
        if (duration <= 0) return
        val fraction = (positionMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
        paint.color = if (canSeek) Color.rgb(0, 145, 255) else Color.rgb(120, 120, 120)
        canvas.drawRoundRect(left, center - radius, left + (right - left) * fraction, center + radius, radius, radius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onTracking?.invoke(target(event.x))
                return true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) { onTracking?.invoke(target(event.x)); return true }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                onFinishTracking?.invoke(target(event.x))
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                onFinishTracking?.invoke(null)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun target(x: Float): Long? {
        val duration = durationMs ?: return null
        if (!canSeek || duration <= 0 || width <= 0) return null
        val inset = 4.pt.toFloat()
        return TvPreviewSeekMath.track(x, inset, width, duration)
    }
}
