package com.ai.playtv.ui.test.tvplayback

import com.ai.playback.PlaybackErrorCode
import com.ai.playback.PlaybackFailure

internal const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"

internal fun playbackFailureReason(failure: PlaybackFailure?): String? = when {
    failure == null -> null
    failure.code == PlaybackErrorCode.SOURCE_IO && failure.diagnostic == "http_404" -> SOURCE_UNAVAILABLE
    else -> failure.code.name
}

internal fun resolutionFailureReason(httpStatus: Int?): String =
    if (httpStatus == 404) SOURCE_UNAVAILABLE else "SOURCE_RESOLUTION_FAILED"
