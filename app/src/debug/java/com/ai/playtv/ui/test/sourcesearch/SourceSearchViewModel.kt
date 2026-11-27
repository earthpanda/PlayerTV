package com.ai.playtv.ui.test.sourcesearch

import android.icu.text.Transliterator
import android.icu.lang.UCharacter
import androidx.lifecycle.viewModelScope
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sourcesearch.*
import com.ai.playtv.model.storage.source.SourceHistoryStore
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class SourceSearchViewModel(private val history: SourceHistoryStore, initialQuery: String) :
    BaseMviViewModel<SourceSearchState, SourceSearchEvent, SourceSearchEffect>(SourceSearchState(query = initialQuery)) {
    private val query = MutableStateFlow(currentState.query)
    private val restricted = listOf(
        "4f7489771c3c13c30b9972380c767c48a4dfd00ba4c6770a943479a18d0e3ba3",
        "3946bc6e02fef8726b19ee574f0df484505086117bc9d877dd4228da710bd547",
        "64f14199ee13ab8dc89ff0b705307008868c0b8c287955d26e4d6c5695aa1ee1",
        "6791e1bd45499837dded382d32b960689df97e740590b7a2a180556baa051c2a",
        "60f1b1c1d68bdb3132ab2841abd151472adc9553599f7549d5096ed09a0fa604",
        "c5d6c441a89fb8886b8b1abb8f1fdcd497ade4e02c54b9382ce0e0cc6ba38991",
        "72974417465b5c5d888e57cad4672de2e3d8cab2faf8f5fb751de83c58e09c78",
    ).map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    private data class IndexedRow(val row: SearchRow, val latin: String, val initials: String)
    init {
        viewModelScope.launch {
            var indexedSnapshot: Any? = null
            var index = emptyList<IndexedRow>()
            combine(history.state, query) { snapshot, value -> snapshot to value }.collectLatest { (snapshot, value) ->
                val rows = withContext(Dispatchers.Default) {
                    val needle = value.trim()
                    if (indexedSnapshot !== snapshot) {
                        val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
                        val updated = mutableListOf<IndexedRow>()
                        for (entry in snapshot.entries) for (item in entry.items) {
                            coroutineContext.ensureActive()
                            val name = item.name.take(512)
                            if (isRestricted(name)) continue
                            val words = transliterator.transliterate(name).split(Regex("[^A-Za-z0-9]+"))
                                .filter { it.isNotEmpty() }
                            val revision = if (item.channelId == null && item.logoUrl == null) null else MessageDigest.getInstance("SHA-256").digest(
                                (item.channelId.orEmpty() + "\u0000" + item.logoUrl.orEmpty()).toByteArray(Charsets.UTF_8)
                            ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                            updated.add(IndexedRow(SearchRow(entry.id, item.id, name, revision),
                                words.joinToString(""), words.joinToString("") { it.take(1) }))
                        }
                        index = updated
                        indexedSnapshot = snapshot
                    }
                    if (needle.isEmpty()) emptyList() else index.filter {
                        it.row.name.contains(needle, true) || it.latin.contains(needle, true) || it.initials.contains(needle, true)
                    }.map { it.row }
                }
                setState { copy(query = value, rows = rows, loading = snapshot.loading,
                    failed = snapshot.failed, hasItems = snapshot.entries.any { it.items.isNotEmpty() },
                    partial = snapshot.entries.any { it.partial }, searching = false) }
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
                if (value == query.value) return
                query.value = value
                setState { copy(query = value, rows = emptyList(), searching = true, failed = false) }
            }
            is SourceSearchEvent.Open -> {
                if (currentState.opening) return
                setState { copy(opening = true) }
                viewModelScope.launch {
                    var launched = false
                    try {
                        val entry = history.find(event.row.historyId)
                        if (entry?.items?.none { it.id == event.row.itemId } != false) {
                            setState { copy(failed = true) }
                        } else if (history.open(event.row.historyId)?.items?.none { it.id == event.row.itemId } != false) {
                            setState { copy(failed = true) }
                        } else {
                            launched = setEffect(SourceSearchEffect.OpenPlayer(event.row.historyId, event.row.itemId))
                            if (!launched) setState { copy(failed = true) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { setState { copy(failed = true) } }
                    finally { if (!launched) setState { copy(opening = false) } }
                }
            }
            SourceSearchEvent.Resume -> setState { copy(opening = false) }
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
