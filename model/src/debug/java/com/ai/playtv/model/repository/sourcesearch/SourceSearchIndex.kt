package com.ai.playtv.model.repository.sourcesearch

import android.icu.lang.UCharacter
import android.icu.text.Transliterator
import com.ai.playtv.model.storage.source.SourceHistoryStore
import com.ai.playtv.model.bean.local.source.StoredSearchData
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** Incrementally prepares committed history and keeps a process-local copy of persisted search data. */
class SourceSearchIndex(history: SourceHistoryStore, private val reportFailure: (String) -> Unit) : AutoCloseable {
    private data class Pinyin(val full: String, val initials: String)
    private class Entry {
        val pinyinLock = Mutex()
        val restrictionLock = Mutex()
        @Volatile var pinyin: Pinyin? = null
        @Volatile var restricted: Boolean? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val transliterator = object : ThreadLocal<Transliterator>() {
        override fun initialValue(): Transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
    }
    private val separators = Regex("[^A-Za-z0-9]+")
    private val restricted = listOf(
        "4f7489771c3c13c30b9972380c767c48a4dfd00ba4c6770a943479a18d0e3ba3",
        "3946bc6e02fef8726b19ee574f0df484505086117bc9d877dd4228da710bd547",
        "64f14199ee13ab8dc89ff0b705307008868c0b8c287955d26e4d6c5695aa1ee1",
        "6791e1bd45499837dded382d32b960689df97e740590b7a2a180556baa051c2a",
        "60f1b1c1d68bdb3132ab2841abd151472adc9553599f7549d5096ed09a0fa604",
        "c5d6c441a89fb8886b8b1abb8f1fdcd497ade4e02c54b9382ce0e0cc6ba38991",
        "72974417465b5c5d888e57cad4672de2e3d8cab2faf8f5fb751de83c58e09c78",
    ).map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }

    init {
        scope.launch {
            history.state.filter { !it.loading && !it.failed }
                .map { snapshot -> snapshot.entries.flatMap { it.items }.map { it.name.take(512) }.toSet() }
                .distinctUntilChanged()
                .collectLatest { names ->
                    entries.keys.retainAll(names)
                    val stored = try { history.readSearchData() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { reportFailure(error.javaClass.simpleName); emptyMap() }
                    for (name in names) {
                        val saved = stored[name] ?: continue
                        val entry = entries.computeIfAbsent(name) { Entry() }
                        if (entry.pinyin == null && saved.pinyin != null && saved.initials != null)
                            entry.pinyin = Pinyin(saved.pinyin, saved.initials)
                        if (entry.restricted == null) entry.restricted = saved.restricted
                    }
                    // Commit small batches, with pinyin prepared before the more expensive checks.
                    val batch = linkedMapOf<String, StoredSearchData>()
                    for (name in names) {
                        if (stored[name]?.pinyin == null || stored[name]?.initials == null) warm {
                            val value = pinyin(name)
                            batch[name] = StoredSearchData(pinyin = value.full, initials = value.initials)
                        }
                        if (batch.size >= BATCH_SIZE) { persist(history, batch); batch.clear() }
                        yield()
                    }
                    if (batch.isNotEmpty()) { persist(history, batch); batch.clear() }
                    for (name in names) {
                        if (stored[name]?.restricted == null) warm {
                            batch[name] = StoredSearchData(restricted = isRestricted(name))
                        }
                        if (batch.size >= BATCH_SIZE) { persist(history, batch); batch.clear() }
                        yield()
                    }
                    if (batch.isNotEmpty()) persist(history, batch)
                }
        }
    }

    fun readyNames(): Set<String> = entries.entries.asSequence()
        .filter { it.value.pinyin != null }.map { it.key }.toSet()

    suspend fun matches(name: String, needle: String): Boolean {
        val value = name.take(512)
        if (value.contains(needle, ignoreCase = true)) return true
        val pinyin = pinyin(value)
        return pinyin.full.contains(needle, ignoreCase = true) ||
            pinyin.initials.contains(needle, ignoreCase = true)
    }

    suspend fun isRestricted(name: String): Boolean {
        val value = name.take(512)
        val entry = entries.computeIfAbsent(value) { Entry() }
        return entry.restricted ?: entry.restrictionLock.withLock {
            entry.restricted ?: checkRestricted(value).also { entry.restricted = it }
        }
    }

    private suspend fun pinyin(name: String): Pinyin {
        val entry = entries.computeIfAbsent(name) { Entry() }
        return entry.pinyin ?: entry.pinyinLock.withLock {
            entry.pinyin ?: run {
                currentCoroutineContext().ensureActive()
                val words = transliterator.get()!!.transliterate(name).split(separators).filter { it.isNotEmpty() }
                Pinyin(words.joinToString(""), words.joinToString("") { it.take(1) })
                    .also { entry.pinyin = it }
            }
        }
    }

    private suspend fun checkRestricted(value: String): Boolean {
        val normalized = UCharacter.foldCase(Normalizer.normalize(value, Normalizer.Form.NFKC), true)
            .filter { it.isLetterOrDigit() }
        val hash = MessageDigest.getInstance("SHA-256")
        for (start in normalized.indices) {
            currentCoroutineContext().ensureActive()
            for (end in start + 1..minOf(normalized.length, start + 40)) {
                val digest = hash.digest(normalized.substring(start, end).toByteArray(Charsets.UTF_8))
                if (restricted.any { it.contentEquals(digest) }) return true
            }
        }
        return false
    }

    private suspend fun warm(block: suspend () -> Any) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            reportFailure(error.javaClass.simpleName)
        }
    }

    private suspend fun persist(history: SourceHistoryStore, values: Map<String, StoredSearchData>) {
        repeat(2) { attempt ->
            try { history.saveSearchData(values); return }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (attempt == 1) reportFailure(error.javaClass.simpleName) else delay(200)
            }
        }
    }

    override fun close() { scope.cancel(); entries.clear() }

    private companion object { const val BATCH_SIZE = 64 }
}
