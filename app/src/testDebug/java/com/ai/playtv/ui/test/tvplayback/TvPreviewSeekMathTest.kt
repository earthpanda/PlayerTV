package com.ai.playtv.ui.test.tvplayback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPreviewSeekMathTest {
    @Test fun swipeDirectionRequiresTravelAndClearDominance() {
        assertEquals(0, TvPreviewSeekMath.axis(15f, 0f, 10))
        assertEquals(0, TvPreviewSeekMath.axis(30f, 25f, 10))
        assertEquals(1, TvPreviewSeekMath.axis(45f, 20f, 10))
        assertEquals(2, TvPreviewSeekMath.axis(20f, 45f, 10))
        assertEquals(2, TvPreviewSeekMath.axis(-20f, -45f, 10))
        assertEquals(0, TvPreviewSeekMath.axis(Float.NaN, 100f, 10))
    }

    @Test fun onlyUpwardVerticalSwipeOpensPlaylist() {
        assertTrue(TvPreviewSeekMath.opensPlaylist(2, -30f, 10))
        assertFalse(TvPreviewSeekMath.opensPlaylist(2, 30f, 10))
        assertFalse(TvPreviewSeekMath.opensPlaylist(2, -10f, 10))
        assertFalse(TvPreviewSeekMath.opensPlaylist(1, -30f, 10))
    }

    @Test fun swipeUsesStartPositionAndClampsToMediaBounds() {
        assertEquals(60_000L, TvPreviewSeekMath.drag(30_000, 300f, 1200, 120_000))
        assertEquals(0L, TvPreviewSeekMath.drag(30_000, -900f, 1200, 120_000))
        assertEquals(120_000L, TvPreviewSeekMath.drag(30_000, 1800f, 1200, 120_000))
    }

    @Test fun trackMapsVisibleEndpointsAndMiddle() {
        assertEquals(0L, TvPreviewSeekMath.track(5f, 10f, 220, 100_000))
        assertEquals(50_000L, TvPreviewSeekMath.track(110f, 10f, 220, 100_000))
        assertEquals(100_000L, TvPreviewSeekMath.track(220f, 10f, 220, 100_000))
    }

    @Test fun unknownDurationAndInvalidGeometryDoNotCreateTargets() {
        assertNull(TvPreviewSeekMath.drag(0, 20f, 100, 0))
        assertNull(TvPreviewSeekMath.drag(0, 20f, 0, 100))
        assertNull(TvPreviewSeekMath.drag(0, Float.NaN, 100, 100))
        assertNull(TvPreviewSeekMath.track(1f, 10f, 20, 100))
        assertNull(TvPreviewSeekMath.step(0, 10_000, 0))
    }

    @Test fun repeatedRemoteStepsStopAtEitherEnd() {
        assertEquals(0L, TvPreviewSeekMath.step(3_000, -10_000, 50_000))
        assertEquals(50_000L, TvPreviewSeekMath.step(45_000, 10_000, 50_000))
    }

    @Test fun longRemotePressAcceleratesAndStillClamps() {
        assertEquals(10_000L, TvPreviewSeekMath.remoteStepMs(1_000))
        val accelerated = TvPreviewSeekMath.remoteStepMs(1_001)
        assertEquals(60_000L, accelerated)
        assertEquals(70_000L, TvPreviewSeekMath.step(40_000, accelerated, 70_000))
        assertEquals(0L, TvPreviewSeekMath.step(40_000, -accelerated, 70_000))
    }
}
