package com.ai.playtv.ui.test.sourceentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareSourceHandoffTest {
    @Test fun tokenIsRequiredAndEachLinkCanBeConsumedOnce() {
        var clock = 100L
        var sequence = 0
        val store = ShareSourceHandoffStore({ clock }) { (++sequence).toString() }
        val token = store.offer("https://example.org/first")
        assertNull(store.consume("wrong"))
        clock += 10
        assertEquals("https://example.org/first", store.consume(token))
        assertNull(store.consume(token))
    }

    @Test fun newerHandoffWinsAndOldExpiryCannotDiscardIt() {
        var sequence = 0
        val store = ShareSourceHandoffStore({ 0 }) { (++sequence).toString() }
        val first = store.offer("https://example.org/first")
        val second = store.offer("https://example.org/second")
        store.discard(first)
        assertNull(store.consume(first))
        assertEquals("https://example.org/second", store.consume(second))
    }

    @Test fun expiredOrBackwardsClockNeverReleasesTheLink() {
        var clock = 100L
        var sequence = 0
        val store = ShareSourceHandoffStore({ clock }) { (++sequence).toString() }
        val expired = store.offer("https://example.org/first")
        clock += ShareSourceHandoffStore.MAX_AGE_MS
        assertNull(store.consume(expired))
        val backwards = store.offer("https://example.org/second")
        clock--
        assertNull(store.consume(backwards))
    }
}
