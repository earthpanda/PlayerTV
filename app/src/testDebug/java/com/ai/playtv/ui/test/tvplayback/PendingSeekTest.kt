package com.ai.playtv.ui.test.tvplayback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSeekTest {
    @Test fun forwardSeekIgnoresOldAndTransientUnknownPositions() {
        val pending = PendingSeek("source", 60_000, 20_000, 120_000, null, true)
        assertFalse(pending.appearsReached(0, null))
        assertFalse(pending.appearsReached(20_500, 120_000))
        assertTrue(pending.appearsReached(58_000, 120_000))
    }

    @Test fun backwardSeekAndSeekToStartRequireValidPosition() {
        val pending = PendingSeek("source", 0, 60_000, 120_000, null, false)
        assertFalse(pending.appearsReached(0, null))
        assertFalse(pending.appearsReached(60_000, 120_000))
        assertTrue(pending.appearsReached(0, 120_000))
    }

    @Test fun nearbyOldPositionIsNotMistakenForAShortSeek() {
        val pending = PendingSeek("source", 10_800, 10_000, 120_000, null, true)
        assertFalse(pending.appearsReached(10_000, 120_000))
        assertFalse(pending.appearsReached(10_100, 120_000))
        assertTrue(pending.appearsReached(10_800, 120_000))
    }
}
