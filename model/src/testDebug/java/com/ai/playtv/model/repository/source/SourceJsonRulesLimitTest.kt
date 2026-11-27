package com.ai.playtv.model.repository.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceJsonRulesLimitTest {
    @Test fun directReferencesReachOneThousandAndOverflowIsMarked() {
        fun config(count: Int): String = (1..count).joinToString(",", prefix = "{\"lives\":[", postfix = "]}") {
            "{\"url\":\"https://example.org/$it.mp4\"}"
        }
        val exact = SourceJsonRules.parse(config(1000))
        assertEquals(1000, exact.references.size)
        assertFalse(exact.truncated)
        val overflow = SourceJsonRules.parse(config(1001))
        assertEquals(1000, overflow.references.size)
        assertEquals("https://example.org/1000.mp4", overflow.references.last().url)
        assertTrue(overflow.truncated)
    }
}
