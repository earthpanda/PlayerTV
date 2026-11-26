package com.ai.playtv.base.util

import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import android.widget.TextView
import com.blankj.utilcode.util.AdaptScreenUtils
import com.ai.playtv.base.BuildConfig
import kotlin.math.roundToInt

/**
 * Design-point adaptation shared by XML and code; independent of business modules.
 * BaseActivity calls adaptDesignPoints; code uses `16.pt` or `text.setTextPtSize(16f)`.
 */
object ScreenUnits {
    /**
     * Read the build-time project setting, using the shorter design axis in landscape.
     * An unset width uses the current system dp span for technical previews only;
     * it does not infer or confirm a product design width. xdpi changes leave density intact.
     */
    fun adaptDesignPoints(resources: Resources): Resources {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = BuildConfig.DESIGN_WIDTH.takeIf { it > 0 } ?: run {
            val metrics = resources.displayMetrics
            val span = if (landscape) metrics.heightPixels else metrics.widthPixels
            (span / metrics.density).roundToInt().coerceAtLeast(1)
        }
        return if (landscape) {
            AdaptScreenUtils.adaptHeight(resources, width)
        } else {
            AdaptScreenUtils.adaptWidth(resources, width)
        }
    }
}

/** Convert an integer design-point value after the Activity has adapted resources. */
val Int.pt: Int get() = AdaptScreenUtils.pt2Px(toFloat())

/** Convert fractional design points without adding another conversion entry point. */
val Float.pt: Float get() = AdaptScreenUtils.pt2Px(this).toFloat()

/** Set a design-point text size; e.g. `title.setTextPtSize(18f)`. */
fun TextView.setTextPtSize(size: Float) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, size.pt)
}
