package com.ai.playtv.model.storage.source

import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.SourceDiscovery
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.bean.domain.source.SourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHistoryRefreshTest {
    private fun source(id: String, name: String = id) = ImportedSource(id, name, "https://example.org/$id", SourceKind.UNKNOWN)
    private val original = SourceHistoryEntry("history", "https://example.org/list", 42,
        listOf(source("a"), source("b")), false)

    @Test fun completeResultRemovesMissingAndUpdatesExistingItems() {
        val withIcon = source("b", "updated").copy(logoUrl = "https://example.org/icon.png")
        val result = SourceDiscovery(SourceOrigin.NETWORK, listOf(withIcon, source("c")))
        val updated = refreshedHistory(original, result)
        assertEquals(listOf(withIcon, source("c")), updated.items)
        assertEquals(42L, updated.lastUsedAtMs)
        assertFalse(updated.partial)
    }

    @Test fun partialAndUnverifiedEmptyResultsNeverRemoveExistingItems() {
        val partial = refreshedHistory(original, SourceDiscovery(SourceOrigin.NETWORK,
            listOf(source("b", "updated"), source("c")), partial = true))
        assertEquals(listOf(source("a"), source("b", "updated"), source("c")), partial.items)
        assertTrue(partial.partial)
        val unknown = refreshedHistory(original, SourceDiscovery(SourceOrigin.NETWORK, emptyList()))
        assertEquals(original.items, unknown.items)
        assertTrue(unknown.partial)
    }

    @Test fun verifiedEmptyResultClearsItemsButRateLimitedResultDoesNot() {
        val empty = SourceDiscovery(SourceOrigin.NETWORK, emptyList(), emptyVerified = true)
        assertTrue(refreshedHistory(original, empty).items.isEmpty())
        assertEquals(original.items, refreshedHistory(original, empty.copy(rateLimited = true)).items)
    }
}
