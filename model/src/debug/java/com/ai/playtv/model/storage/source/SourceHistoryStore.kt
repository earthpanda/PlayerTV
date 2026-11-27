package com.ai.playtv.model.storage.source

import android.util.AtomicFile
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.MAX_IMPORTED_SOURCES
import com.ai.playtv.model.bean.domain.source.SourceDiscovery
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.repository.source.SourceRules
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SourceHistorySnapshot(
    val entries: List<SourceHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Single-process, installation-scoped history; failed reads never replace the stored file. */
class SourceHistoryStore(private val directory: suspend () -> File) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SourceHistorySnapshot())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var entries = emptyList<SourceHistoryEntry>()
    private val activeSessions = ConcurrentHashMap<String, String>()
    private val sessionOrder = mutableMapOf<String, Pair<String, Long>>()

    /** Invalidate delayed saves from older scans of the same user-entered address. */
    fun begin(input: String, sessionId: String) { activeSessions[input.trim()] = sessionId }
    fun end(input: String, sessionId: String) { activeSessions.remove(input.trim(), sessionId) }
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
        commit(entries.map { if (it.id == id) updated else it })
        activeSessions.remove(current.input)
        updated
    }

    /** Reserve a refresh only while the opened snapshot is still current. */
    suspend fun beginRefresh(expected: SourceHistoryEntry, sessionId: String): Boolean = operation {
        initialize()
        entries.firstOrNull { it.id == expected.id } == expected &&
            activeSessions.putIfAbsent(expected.input, sessionId) == null
    }

    /** Commit only the latest owner's completed scan; incomplete scans cannot remove cached items. */
    suspend fun finishRefresh(expected: SourceHistoryEntry, sessionId: String, result: SourceDiscovery): SourceHistoryEntry? = operation {
        initialize()
        val current = entries.firstOrNull { it.id == expected.id } ?: return@operation null
        if (current != expected || activeSessions[expected.input] != sessionId) return@operation null
        val updated = refreshedHistory(current, result)
        if (updated != current) {
            val file = historyFile()
            if (activeSessions[expected.input] != sessionId) return@operation null
            val before = entries
            val ordered = writeSnapshot(file, before.map { if (it.id == current.id) updated else it })
            // A newer owner can start while AtomicFile writes; it cannot save while this mutex is held.
            if (activeSessions[expected.input] != sessionId) {
                writeSnapshot(file, before)
                return@operation null
            }
            publish(ordered)
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
        commit(entries.filterNot { it.id == id } + entry)
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, failed = true)
                throw error
            }
        }
    }

    private suspend fun historyFile(): AtomicFile {
        val dir = directory()
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("History directory unavailable")
        return AtomicFile(File(dir, "history.json"))
    }

    private suspend fun initialize() {
        if (initialized) return
        val file = historyFile()
        val backup = File(file.baseFile.path + ".bak")
        val restored = if (!file.baseFile.exists() && !backup.exists()) emptyList() else {
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw IOException("History exceeds capacity")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            decode(JSONObject(bytes.toString(Charsets.UTF_8)))
        }
        entries = restored.sortedByDescending { it.lastUsedAtMs }
        initialized = true
        mutableState.value = SourceHistorySnapshot(entries, loading = false)
    }

    private suspend fun commit(updated: List<SourceHistoryEntry>) {
        publish(writeSnapshot(historyFile(), updated))
    }

    private fun writeSnapshot(file: AtomicFile, updated: List<SourceHistoryEntry>): List<SourceHistoryEntry> {
        val ordered = updated.sortedByDescending { it.lastUsedAtMs }
        val bytes = encode(ordered).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("History exceeds capacity")
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return ordered
    }

    private fun publish(ordered: List<SourceHistoryEntry>) {
        entries = ordered
        mutableState.value = SourceHistorySnapshot(entries, loading = false)
    }

    private fun encode(values: List<SourceHistoryEntry>): JSONObject = JSONObject().apply {
        put("version", 1)
        put("entries", JSONArray().apply {
            values.forEach { entry -> put(JSONObject().apply {
                put("id", entry.id); put("input", entry.input); put("lastUsedAtMs", entry.lastUsedAtMs)
                put("partial", entry.partial)
                put("items", JSONArray().apply { entry.items.forEach { item -> put(JSONObject().apply {
                    put("id", item.id); put("name", item.name); put("url", item.url); put("kind", item.kind.name)
                    item.channelId?.let { put("channelId", it) }
                    item.logoUrl?.let { put("logoUrl", it) }
                }) } })
            }) }
        })
    }

    private fun decode(root: JSONObject): List<SourceHistoryEntry> {
        require(root.getInt("version") == 1) { "Unsupported history version" }
        val result = mutableListOf<SourceHistoryEntry>()
        val array = root.getJSONArray("entries")
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val input = SourceRules.checkedUrl(entry.getString("input")).toString()
            val id = entry.getString("id")
            require(id == identity(input) && result.none { it.id == id }) { "Invalid history identity" }
            val items = entry.getJSONArray("items")
            require(items.length() in 0..MAX_IMPORTED_SOURCES) { "Invalid history candidates" }
            val sources = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val url = SourceRules.checkedUrl(item.getString("url")).toString()
                val channelId = item.optString("channelId", "").takeIf { it.isNotBlank() && it.length <= 256 }
                val logoUrl = item.optString("logoUrl", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { SourceRules.checkedUrl(it).toString() }.getOrNull()
                }
                ImportedSource(item.getString("id"), item.getString("name"), url, SourceKind.valueOf(item.getString("kind")), channelId, logoUrl)
            }
            result.add(SourceHistoryEntry(id, input, entry.getLong("lastUsedAtMs"), sources, entry.getBoolean("partial")))
        }
        return result
    }

    private fun identity(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
}

/** A verified complete scan replaces the list; all other results only update or append. */
internal fun refreshedHistory(current: SourceHistoryEntry, result: SourceDiscovery): SourceHistoryEntry {
    val complete = !result.partial && !result.rateLimited && (result.items.isNotEmpty() || result.emptyVerified)
    if (complete) return current.copy(items = result.items, partial = false)
    val updates = result.items.associateBy { it.id }
    val merged = (current.items.map { updates[it.id] ?: it } + result.items).distinctBy { it.id }
    return current.copy(items = merged.take(MAX_IMPORTED_SOURCES), partial = true)
}
