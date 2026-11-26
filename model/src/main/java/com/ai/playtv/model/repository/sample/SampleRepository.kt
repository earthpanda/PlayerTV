package com.ai.playtv.model.repository.sample

import com.ai.playtv.model.bean.domain.sample.SampleItem
import com.ai.playtv.model.bean.domain.sample.SamplePage
import kotlinx.coroutines.delay

/**
 * Deterministic local data for the template preview, never a production network fallback.
 * Example: loadPage(0) yields the first ten IDs and the next page index.
 * Replace this demo when the first real feature's data contract is confirmed.
 */
class SampleRepository {
    /** Technical demonstration only: three pages, ten rows per page. */
    private val pageSize = 10
    /** Bounded local data makes the no-more-data and refresh-reset paths observable. */
    private val pageCount = 3

    /** Simulate a cancellable asynchronous boundary, without disk, HTTP or permissions. */
    suspend fun loadPage(page: Int): SamplePage {
        require(page in 0 until pageCount) { "Sample page out of range" }
        delay(250L) // Demo timing in milliseconds; not a business timeout or performance target.
        val start = page * pageSize
        return SamplePage(
            items = (1..pageSize).map { SampleItem((start + it).toLong()) },
            nextPage = (page + 1).takeIf { it < pageCount },
        )
    }
}
