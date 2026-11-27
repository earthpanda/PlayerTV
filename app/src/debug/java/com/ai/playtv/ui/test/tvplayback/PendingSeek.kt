package com.ai.playtv.ui.test.tvplayback

import kotlin.math.abs

/** Display-only estimate: ordinary SDK seeks have no completion acknowledgement. */
internal data class PendingSeek(
    val sourceId: String?, val targetMs: Long, val startMs: Long, val durationMs: Long,
    val initialError: String?, val playing: Boolean,
) {
    fun appearsReached(positionMs: Long, reportedDurationMs: Long?): Boolean {
        if (reportedDurationMs == null || reportedDurationMs <= 0) return false
        val distance = abs(targetMs - startMs)
        if (distance == 0L) return true
        val movement = abs(positionMs - startMs)
        return movement >= minOf(500L, (distance + 1) / 2) &&
            abs(positionMs - targetMs) <= minOf(5_000L, distance / 2)
    }
}
