package com.ai.playtv.ui.test.sourcesearch

import androidx.lifecycle.viewModelScope
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sourcesearch.*
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.repository.sourcesearch.SourceSearchIndex
import com.ai.playtv.model.storage.source.SourceHistorySnapshot
import com.ai.playtv.model.storage.source.SourceHistoryStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceSearchViewModel(private val history: SourceHistoryStore, private val index: SourceSearchIndex, initialQuery: String) :
    BaseMviViewModel<SourceSearchState, SourceSearchEvent, SourceSearchEffect>(SourceSearchState(query = initialQuery)) {
    private var snapshot = SourceHistorySnapshot()
    private var session: SearchSession? = null
    private var searchJob: Job? = null
    private data class Candidate(val historyId: String, val item: ImportedSource)
    private class SearchSession(val query: String, val entries: List<SourceHistoryEntry>,
        var cursor: SearchCursor = SearchCursor(), var groups: List<List<Candidate>>? = null)
    init {
        viewModelScope.launch {
            history.state.collect { newSnapshot ->
                snapshot = newSnapshot
                startSearch(currentState.query)
            }
        }
        viewModelScope.launch {
            try { history.load() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    override fun onEvent(event: SourceSearchEvent) {
        when (event) {
            is SourceSearchEvent.Query -> {
                val value = event.value.take(40)
                if (value == currentState.query) return
                startSearch(value)
            }
            is SourceSearchEvent.Open -> {
                if (currentState.opening || currentState.query.isBlank() || currentState.searching ||
                    event.row !in currentState.rows) return
                val selectedQuery = currentState.query
                setState { copy(opening = true) }
                viewModelScope.launch {
                    var launched = false
                    try {
                        val entry = history.find(event.row.historyId)
                        if (entry?.items?.none { it.id == event.row.itemId } != false) {
                            setState { copy(failed = true) }
                        } else if (currentState.query != selectedQuery) {
                            return@launch
                        } else if (history.open(event.row.historyId)?.items?.none { it.id == event.row.itemId } != false) {
                            setState { copy(failed = true) }
                        } else if (currentState.query == selectedQuery) {
                            launched = setEffect(SourceSearchEffect.OpenPlayer(event.row.historyId, event.row.itemId))
                            if (!launched) setState { copy(failed = true) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { setState { copy(failed = true) } }
                    finally { if (!launched) setState { copy(opening = false) } }
                }
            }
            SourceSearchEvent.LoadMore -> session?.let { loadPage(it, first = false) }
            SourceSearchEvent.Resume -> setState { copy(opening = false) }
        }
    }
    private fun startSearch(value: String) {
        searchJob?.cancel()
        session = null
        val canSearch = value.isNotBlank() && !snapshot.loading && !snapshot.failed
        setState { copy(query = value, rows = emptyList(), loading = snapshot.loading,
            failed = snapshot.failed, hasItems = snapshot.entries.any { it.items.isNotEmpty() },
            partial = snapshot.entries.any { it.partial }, searching = canSearch,
            loadingMore = false, hasMore = false, pageFailed = false) }
        if (canSearch) {
            val next = SearchSession(value, snapshot.entries)
            session = next
            loadPage(next, first = true)
        }
    }
    private fun loadPage(active: SearchSession, first: Boolean) {
        if (session !== active || (!first && (!currentState.hasMore || currentState.loadingMore))) return
        if (!first) setState { copy(loadingMore = true, pageFailed = false) }
        val start = active.cursor
        searchJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.Default) { searchPage(active, start) }
                if (session !== active) return@launch
                active.cursor = page.cursor
                setState { copy(rows = if (first) page.rows else rows + page.rows,
                    searching = false, loadingMore = false, hasMore = page.hasMore) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session === active) setState { copy(searching = false, loadingMore = false,
                    failed = first, pageFailed = !first) }
            }
        }
    }
    private suspend fun searchPage(active: SearchSession, start: SearchCursor): SearchPage<SearchRow> {
        val groups = active.groups ?: run {
            val readyNames = index.readyNames()
            val candidates = mutableListOf<Candidate>()
            for (entry in active.entries) for (item in entry.items) {
                currentCoroutineContext().ensureActive()
                candidates.add(Candidate(entry.id, item))
            }
            groupSearchItems(candidates) { it.item.name.take(512) in readyNames }.also { active.groups = it }
        }
        return scanSearchPage(groups, start, 20) { _, wrapped ->
            val candidate = wrapped.item
            val name = candidate.name.take(512)
            if (index.matches(name, active.query.trim()) && !index.isRestricted(name)) {
                val revision = if (candidate.channelId == null && candidate.logoUrl == null) null else MessageDigest.getInstance("SHA-256").digest(
                    (candidate.channelId.orEmpty() + "\u0000" + candidate.logoUrl.orEmpty()).toByteArray(Charsets.UTF_8)
                ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                SearchRow(wrapped.historyId, candidate.id, name, revision)
            } else null
        }
    }
}
