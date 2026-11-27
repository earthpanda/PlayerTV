package com.ai.playtv.model.storage.source

import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.MAX_IMPORTED_SOURCES
import com.ai.playtv.model.bean.domain.source.SourceDiscovery
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.bean.local.source.StoredSearchData
import com.ai.playtv.model.database.source.SourceHistoryDatabase
import com.ai.playtv.model.repository.source.SourceRules
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SourceHistorySnapshot(
    val entries: List<SourceHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Single-process, installation-scoped history; committed database state is the only source. */
class SourceHistoryStore(private val directory: suspend () -> File) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SourceHistorySnapshot())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var database: SourceHistoryDatabase? = null
    private var entries = emptyList<SourceHistoryEntry>()
    private val activeSessions = ConcurrentHashMap<String, String>()
    private val sessionCommitLock = Any()
    private val sessionOrder = mutableMapOf<String, Pair<String, Long>>()

    /** Invalidate delayed saves from older scans of the same user-entered address. */
    fun begin(input: String, sessionId: String) { synchronized(sessionCommitLock) { activeSessions[input.trim()] = sessionId } }
    fun end(input: String, sessionId: String) { synchronized(sessionCommitLock) { activeSessions.remove(input.trim(), sessionId) } }
    fun ownsRefresh(input: String, sessionId: String): Boolean = activeSessions[input.trim()] == sessionId

    suspend fun load() = operation { initialize() }

    suspend fun find(id: String): SourceHistoryEntry? = operation {
        initialize()
        entries.firstOrNull { it.id == id }
    }

    suspend fun open(id: String): SourceHistoryEntry? = operation {
        initialize()
        val current = entries.firstOrNull { it.id == id } ?: return@operation null
        val timestamp = maxOf(System.currentTimeMillis(), (entries.maxOfOrNull { it.lastUsedAtMs } ?: 0L) + 1)
        val updated = current.copy(lastUsedAtMs = timestamp)
        commit(updated, current)
        synchronized(sessionCommitLock) { activeSessions.remove(current.input) }
        updated
    }

    /** Reserve a refresh only while the opened snapshot is still current. */
    suspend fun beginRefresh(expected: SourceHistoryEntry, sessionId: String): Boolean = operation {
        initialize()
        entries.firstOrNull { it.id == expected.id } == expected && synchronized(sessionCommitLock) {
            activeSessions.putIfAbsent(expected.input, sessionId) == null
        }
    }

    /** Commit only the latest owner's completed scan; incomplete scans cannot remove cached items. */
    suspend fun finishRefresh(expected: SourceHistoryEntry, sessionId: String, result: SourceDiscovery): SourceHistoryEntry? = operation {
        initialize()
        val current = entries.firstOrNull { it.id == expected.id } ?: return@operation null
        if (current != expected || activeSessions[expected.input] != sessionId) return@operation null
        val updated = refreshedHistory(current, result)
        if (updated != current) {
            val context = currentCoroutineContext()
            val committed = requireNotNull(database).write(updated, current) { finish ->
                synchronized(sessionCommitLock) {
                    context.ensureActive()
                    if (activeSessions[expected.input] != sessionId) false else { finish(); true }
                }
            }
            if (!committed) return@operation null
            publish(entries.map { if (it.id == current.id) updated else it })
        }
        updated
    }

    suspend fun save(input: String, sessionId: String, discovery: SourceDiscovery, failed: Boolean) = operation {
        if (discovery.items.isEmpty()) return@operation
        initialize()
        val normalized = SourceRules.checkedUrl(input).toString()
        if (activeSessions[normalized] != sessionId) return@operation
        val id = identity(normalized)
        val last = sessionOrder[normalized]
        val timestamp = if (last?.first == sessionId) last.second else {
            maxOf(System.currentTimeMillis(), (entries.maxOfOrNull { it.lastUsedAtMs } ?: 0L) + 1)
                .also { sessionOrder[normalized] = sessionId to it }
        }
        val entry = SourceHistoryEntry(id, normalized, timestamp, discovery.items, discovery.partial || failed)
        val context = currentCoroutineContext()
        val committed = requireNotNull(database).write(entry, entries.firstOrNull { it.id == id }) { finish ->
            synchronized(sessionCommitLock) {
                context.ensureActive()
                if (activeSessions[normalized] != sessionId) false else { finish(); true }
            }
        }
        if (committed) publish(entries.filterNot { it.id == id } + entry)
    }

    suspend fun readSearchData(): Map<String, StoredSearchData> = operation(markFailure = false) {
        initialize()
        requireNotNull(database).searchData()
    }

    suspend fun saveSearchData(values: Map<String, StoredSearchData>) = operation(markFailure = false) {
        initialize()
        val context = currentCoroutineContext()
        requireNotNull(database).saveSearchData(values) { context.ensureActive(); true }
    }

    private suspend fun <T> operation(markFailure: Boolean = true, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (markFailure) mutableState.value = mutableState.value.copy(loading = false, failed = true)
                throw error
            }
        }
    }

    private suspend fun initialize() {
        if (initialized) return
        val opened = SourceHistoryDatabase(directory())
        try {
            val restored = opened.read().map { entry ->
                val input = SourceRules.checkedUrl(entry.input).toString()
                require(entry.id == identity(input) && entry.items.size <= MAX_IMPORTED_SOURCES) { "Invalid history source" }
                entry.copy(input = input, items = entry.items.map { item ->
                    val url = SourceRules.checkedUrl(item.url).toString()
                    val channel = item.channelId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    val logo = item.logoUrl?.takeIf { it.isNotBlank() }?.let {
                        runCatching { SourceRules.checkedUrl(it).toString() }.getOrNull()
                    }
                    item.copy(url = url, channelId = channel, logoUrl = logo)
                })
            }
            database = opened
            entries = restored
            initialized = true
            mutableState.value = SourceHistorySnapshot(entries, loading = false)
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
    }

    private suspend fun commit(updated: SourceHistoryEntry, previous: SourceHistoryEntry) {
        val context = currentCoroutineContext()
        requireNotNull(database).write(updated, previous) { finish -> context.ensureActive(); finish(); true }
        publish(entries.map { if (it.id == updated.id) updated else it })
    }

    private fun publish(updated: List<SourceHistoryEntry>) {
        entries = updated.sortedByDescending { it.lastUsedAtMs }
        mutableState.value = SourceHistorySnapshot(entries, loading = false)
    }

    private fun identity(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

}

/** A verified complete scan replaces the list; all other results only update or append. */
internal fun refreshedHistory(current: SourceHistoryEntry, result: SourceDiscovery): SourceHistoryEntry {
    val complete = !result.partial && !result.rateLimited && (result.items.isNotEmpty() || result.emptyVerified)
    if (complete) return current.copy(items = result.items, partial = false)
    val updates = result.items.associateBy { it.id }
    val merged = (current.items.map { updates[it.id] ?: it } + result.items).distinctBy { it.id }
    return current.copy(items = merged.take(MAX_IMPORTED_SOURCES), partial = true)
}
