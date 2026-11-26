package com.ai.playtv.ui.test.tvplayback

/** Position calculations only; availability and command ownership remain with the player. */
internal object TvPreviewSeekMath {
    /** Lock a gesture only after sufficient travel and a clear directional majority. */
    fun axis(dx: Float, dy: Float, slop: Int): Int {
        if (!dx.isFinite() || !dy.isFinite() || slop <= 0) return 0
        val horizontal = kotlin.math.abs(dx)
        val vertical = kotlin.math.abs(dy)
        val threshold = slop * 2f
        return when {
            horizontal >= threshold && horizontal >= vertical * 1.5f -> 1
            vertical >= threshold && vertical >= horizontal * 1.5f -> 2
            else -> 0
        }
    }

    fun opensPlaylist(axis: Int, deltaY: Float, slop: Int): Boolean =
        axis == 2 && slop > 0 && deltaY.isFinite() && deltaY <= -2f * slop

    fun drag(startMs: Long, deltaPx: Float, widthPx: Int, durationMs: Long): Long? {
        if (widthPx <= 0 || durationMs <= 0 || !deltaPx.isFinite()) return null
        return (startMs.toDouble() + deltaPx.toDouble() / widthPx * durationMs)
            .coerceIn(0.0, durationMs.toDouble()).toLong()
    }

    fun track(x: Float, inset: Float, widthPx: Int, durationMs: Long): Long? {
        val usable = widthPx - 2 * inset
        if (durationMs <= 0 || usable <= 0 || !x.isFinite() || !inset.isFinite()) return null
        return (((x - inset) / usable).coerceIn(0f, 1f).toDouble() * durationMs).toLong()
    }

    fun step(positionMs: Long, deltaMs: Long, durationMs: Long): Long? {
        if (durationMs <= 0) return null
        return (positionMs.toDouble() + deltaMs).coerceIn(0.0, durationMs.toDouble()).toLong()
    }
}
