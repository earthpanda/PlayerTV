package com.ai.playtv.ui.test.sourcesearch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchPageScannerTest {
    @Test fun priorityIsFrozenAcrossPagesAndPreservesDuplicatesAndGroupOrder() = runBlocking {
        val ready = mutableSetOf("b", "d")
        val groups = groupSearchItems(listOf("a", "b", "c", "b", "d")) { it in ready }
        val first = scanSearchPage(groups, SearchCursor(), 2) { _, item -> item }
        ready.add("a")
        val second = scanSearchPage(groups, first.cursor, 2) { _, item -> item }
        val third = scanSearchPage(groups, second.cursor, 2) { _, item -> item }
        assertEquals(listOf("b", "b"), first.rows)
        assertEquals(listOf("d", "a"), second.rows)
        assertEquals(listOf("c"), third.rows)
        assertFalse(third.hasMore)
    }

    @Test fun pagesContinueAcrossSourcesWithoutScanningAheadOrDroppingDuplicates() = runBlocking {
        val sources = listOf((1..12).toList(), emptyList(), (13..45).toList(), listOf(45))
        var examined = 0
        suspend fun page(cursor: SearchCursor) = scanSearchPage(sources, cursor, 20) { _, item ->
            examined++
            item
        }
        val first = page(SearchCursor())
        assertEquals((1..20).toList(), first.rows)
        assertEquals(20, examined)
        assertTrue(first.hasMore)
        val second = page(first.cursor)
        assertEquals((21..40).toList(), second.rows)
        val third = page(second.cursor)
        assertEquals(listOf(41, 42, 43, 44, 45, 45), third.rows)
        assertFalse(third.hasMore)
    }

    @Test fun sparseAndEmptyMatchesReachEndWithoutFalseResults() = runBlocking {
        val sources = listOf((1..25).toList(), (26..42).toList())
        val first = scanSearchPage(sources, SearchCursor(), 3) { _, item -> item.takeIf { it % 10 == 0 } }
        assertEquals(listOf(10, 20, 30), first.rows)
        assertTrue(first.hasMore)
        val last = scanSearchPage(sources, first.cursor, 3) { _, item -> item.takeIf { it % 10 == 0 } }
        assertEquals(listOf(40), last.rows)
        assertFalse(last.hasMore)
        assertTrue(scanSearchPage(sources, SearchCursor(), 20) { _, _: Int -> null as Int? }.rows.isEmpty())
    }
}
