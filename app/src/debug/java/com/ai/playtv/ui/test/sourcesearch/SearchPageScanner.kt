package com.ai.playtv.ui.test.sourcesearch

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class SearchCursor(val source: Int = 0, val item: Int = 0)
internal data class SearchPage<T>(val rows: List<T>, val cursor: SearchCursor, val hasMore: Boolean)

/** Continue in source order, stopping immediately when a page of matches is full. */
internal suspend fun <T, R> scanSearchPage(
    sources: List<List<T>>, start: SearchCursor, limit: Int, match: (Int, T) -> R?,
): SearchPage<R> {
    require(limit > 0)
    val rows = ArrayList<R>(limit)
    var source = start.source
    var item = start.item
    while (source < sources.size && rows.size < limit) {
        coroutineContext.ensureActive()
        if (item >= sources[source].size) { source++; item = 0; continue }
        match(source, sources[source][item++])?.let(rows::add)
    }
    while (source < sources.size && item >= sources[source].size) { source++; item = 0 }
    return SearchPage(rows, SearchCursor(source, item), source < sources.size)
}
