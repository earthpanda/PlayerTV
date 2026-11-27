package com.ai.playtv.ui.test.sourcesearch

import android.icu.text.Transliterator
import android.icu.lang.UCharacter
import androidx.lifecycle.viewModelScope
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sourcesearch.*
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.storage.source.SourceHistorySnapshot
import com.ai.playtv.model.storage.source.SourceHistoryStore
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceSearchViewModel(private val history: SourceHistoryStore, initialQuery: String) :
    BaseMviViewModel<SourceSearchState, SourceSearchEvent, SourceSearchEffect>(SourceSearchState(query = initialQuery)) {
    private var snapshot = SourceHistorySnapshot()
    private var session: SearchSession? = null
    private var searchJob: Job? = null
    private val restricted = listOf(
        "4f7489771c3c13c30b9972380c767c48a4dfd00ba4c6770a943479a18d0e3ba3",
        "3946bc6e02fef8726b19ee574f0df484505086117bc9d877dd4228da710bd547",
        "64f14199ee13ab8dc89ff0b705307008868c0b8c287955d26e4d6c5695aa1ee1",
        "6791e1bd45499837dded382d32b960689df97e740590b7a2a180556baa051c2a",
        "60f1b1c1d68bdb3132ab2841abd151472adc9553599f7549d5096ed09a0fa604",
        "c5d6c441a89fb8886b8b1abb8f1fdcd497ade4e02c54b9382ce0e0cc6ba38991",
        "72974417465b5c5d888e57cad4672de2e3d8cab2faf8f5fb751de83c58e09c78",
    ).map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    private class SearchSession(val query: String, val entries: List<SourceHistoryEntry>, var cursor: SearchCursor = SearchCursor())
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
                val page = withContext(Dispatchers.Default) { searchPage(active.entries, active.query.trim(), start) }
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
    private suspend fun searchPage(entries: List<SourceHistoryEntry>, needle: String, start: SearchCursor): SearchPage<SearchRow> {
        val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
        return scanSearchPage(entries.map { it.items }, start, 20) { source, candidate ->
            val name = candidate.name.take(512)
            val direct = name.contains(needle, ignoreCase = true)
            val words = if (direct) emptyList() else transliterator.transliterate(name)
                .split(Regex("[^A-Za-z0-9]+")) .filter { it.isNotEmpty() }
            val matches = direct || words.joinToString("").contains(needle, true) ||
                words.joinToString("") { it.take(1) }.contains(needle, true)
            if (matches && !isRestricted(name)) {
                val revision = if (candidate.channelId == null && candidate.logoUrl == null) null else MessageDigest.getInstance("SHA-256").digest(
                    (candidate.channelId.orEmpty() + "\u0000" + candidate.logoUrl.orEmpty()).toByteArray(Charsets.UTF_8)
                ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                SearchRow(entries[source].id, candidate.id, name, revision)
            } else null
        }
    }
    private fun isRestricted(value: String): Boolean {
        val normalized = UCharacter.foldCase(Normalizer.normalize(value, Normalizer.Form.NFKC), true)
            .filter { it.isLetterOrDigit() }
        val hash = MessageDigest.getInstance("SHA-256")
        for (start in normalized.indices) for (end in start + 1..minOf(normalized.length, start + 40)) {
            val digest = hash.digest(normalized.substring(start, end).toByteArray(Charsets.UTF_8))
            if (restricted.any { it.contentEquals(digest) }) return true
        }
        return false
    }
}
