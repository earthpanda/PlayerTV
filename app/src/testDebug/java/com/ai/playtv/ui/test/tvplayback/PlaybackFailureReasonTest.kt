package com.ai.playtv.ui.test.tvplayback

import com.ai.playback.PlaybackErrorCode
import com.ai.playback.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFailureReasonTest {
    @Test fun onlyConfirmed404IsReportedAsUnavailable() {
        assertEquals(SOURCE_UNAVAILABLE, playbackFailureReason(PlaybackFailure(PlaybackErrorCode.SOURCE_IO, "http_404")))
        assertEquals("SOURCE_IO", playbackFailureReason(PlaybackFailure(PlaybackErrorCode.SOURCE_IO, "http_403")))
        assertEquals("SOURCE_IO", playbackFailureReason(PlaybackFailure(PlaybackErrorCode.SOURCE_IO, "timeout")))
        assertEquals("DECODE_FAILED", playbackFailureReason(PlaybackFailure(PlaybackErrorCode.DECODE_FAILED, "http_404")))
        assertNull(playbackFailureReason(null))
    }

    @Test fun sourceResolutionDistinguishes404FromOtherFailures() {
        assertEquals(SOURCE_UNAVAILABLE, resolutionFailureReason(404))
        assertEquals("SOURCE_RESOLUTION_FAILED", resolutionFailureReason(403))
        assertEquals("SOURCE_RESOLUTION_FAILED", resolutionFailureReason(null))
    }
}
