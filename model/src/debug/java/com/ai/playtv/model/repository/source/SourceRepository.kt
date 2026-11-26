package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.*
import com.ai.playtv.model.network.service.source.GithubSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.URI
import kotlin.coroutines.coroutineContext

/** Page-scoped, bounded discovery. Results are candidates, not playback guarantees. */
class SourceRepository(private val service: GithubSourceService) {
    private val maxText = 1024 * 1024
    private val maxItems = 100
    private val maxMarkdown = 12

    suspend fun discover(input: String, onUpdate: suspend (SourceDiscovery) -> Unit = {}): SourceDiscovery {
        val uri = SourceRules.checkedUrl(input)
        val parts = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
        if (uri.host.equals("github.com", true) && parts.size == 2) return repository(parts[0], parts[1].removeSuffix(".git"), onUpdate)
        if (uri.host.equals("github.com", true) && parts.size >= 3 && parts[2] == "tree") throw IOException("Directory URLs are not supported")
        val origin = if (uri.host.equals("github.com", true) || uri.host.equals("raw.githubusercontent.com", true)) SourceOrigin.GITHUB_FILE else SourceOrigin.NETWORK
        if (ReferenceKind.CLOUD_SHARE in SourceRules.referenceKinds(uri.toString())) return SourceDiscovery(origin, emptyList(), references = setOf(ReferenceKind.CLOUD_SHARE)).also { onUpdate(it) }
        val normalized = normalizeGithubFile(uri)
        val fetched = fetch(normalized, true)
        val content = fetched.text
        val type = content?.let(SourceRules::kind) ?: SourceKind.UNKNOWN
        val playlist = if (type == SourceKind.UNKNOWN && content != null &&
            (content.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U", true) || uri.path.endsWith(".m3u", true) || uri.path.endsWith(".m3u8", true) || uri.path.endsWith(".txt", true)))
            SourceRules.playlist(URI(fetched.finalUrl), if (fetched.truncated) content.substringBeforeLast('\n', "") else content,
                uri.path.endsWith(".txt", true)) else null
        val items = when {
            type != SourceKind.UNKNOWN -> listOf(item(normalized, type))
            playlist != null -> playlist.items
            content != null && SourceRules.isMarkdown(uri.path) -> SourceRules.markdownLinks(content, URI(fetched.finalUrl)).map { item(it, SourceKind.UNKNOWN) }
            content != null -> emptyList()
            else -> listOf(item(normalized, if (SourceRules.candidate(uri.path)) SourceKind.MEDIA else SourceKind.UNKNOWN))
        }
        val references = when {
            fetched.isHtml -> setOf(ReferenceKind.WEBSITE)
            content != null && SourceRules.isMarkdown(uri.path) -> SourceRules.referenceKinds(content)
            else -> emptySet()
        }
        return bounded(origin, items, items.size > maxItems || fetched.truncated || playlist?.skipped == true, references).also { onUpdate(it) }
    }

    /** Resolve redirects again at selection time so temporary signed targets are not saved. */
    suspend fun playbackUrl(url: String): String = fetch(SourceRules.checkedUrl(url).toString(), false).finalUrl

    private suspend fun repository(owner: String, repo: String, onUpdate: suspend (SourceDiscovery) -> Unit): SourceDiscovery {
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repo.matches(Regex("[A-Za-z0-9_.-]+")))
        val branch = service.repository(owner, repo).defaultBranch?.takeIf { it.isNotBlank() } ?: throw IOException("Missing default branch")
        val tree = service.tree(owner, repo, branch)
        val entries = tree.tree ?: throw IOException("Missing repository tree")
        val files = entries.filter { it.type == "blob" && it.path != null }
        fun raw(path: String): String {
            val b = "https://raw.githubusercontent.com/".toHttpUrl().newBuilder().addPathSegment(owner).addPathSegment(repo).addPathSegment(branch)
            path.split('/').forEach { b.addPathSegment(it) }
            return b.build().toString()
        }
        fun isList(path: String): Boolean = listOf(".m3u", ".m3u8", ".mpd", ".txt").any { path.substringBefore('?').endsWith(it, true) }
        fun isTs(path: String) = path.endsWith(".ts", true)
        val mediaFiles = files.filter { SourceRules.candidate(it.path!!) && !isList(it.path!!) && !isTs(it.path!!) }
        val results = mediaFiles.take(maxItems).map { item(raw(it.path!!), SourceKind.UNKNOWN) }.toMutableList()
        val lists = files.filter { isList(it.path!!) }.map { raw(it.path!!) }.toMutableList()
        val transportFiles = files.filter { isTs(it.path!!) }.map { raw(it.path!!) }.toMutableList()
        val markdown = files.filter { SourceRules.isMarkdown(it.path!!) }
        var partial = tree.truncated != false || results.size >= maxItems || markdown.size > maxMarkdown
        val references = mutableSetOf<ReferenceKind>()
        suspend fun publish() {
            val unique = results.distinctBy { it.id }
            onUpdate(bounded(SourceOrigin.GITHUB_REPOSITORY, unique, partial || unique.size > maxItems, references.toSet()))
        }
        if (results.isNotEmpty()) publish()
        for (entry in markdown.take(maxMarkdown)) {
            coroutineContext.ensureActive()
            try {
                val fetched = fetch(raw(entry.path!!), true)
                partial = partial || fetched.truncated
                fetched.text?.let { text ->
                    references.addAll(SourceRules.referenceKinds(text))
                    SourceRules.markdownLinks(text, URI(fetched.finalUrl)).forEach { link ->
                        val path = URI(link).path.orEmpty()
                        when {
                            isList(path) -> lists.add(link)
                            isTs(path) -> transportFiles.add(link)
                            else -> results.add(item(link, SourceKind.UNKNOWN))
                        }
                    }
                }
            } catch (e: IOException) { partial = true }
            publish()
        }
        val uniqueTransport = transportFiles.distinct()
        if (uniqueTransport.size > 8) partial = true
        for (url in uniqueTransport.take(8)) {
            coroutineContext.ensureActive()
            if (results.size >= maxItems) { partial = true; break }
            try {
                if (fetch(url, true, probeTransport = true).isTransportStream) results.add(item(url, SourceKind.MEDIA))
            } catch (e: IOException) { partial = true }
            publish()
        }
        val uniqueLists = lists.distinct()
        if (uniqueLists.size > 8) partial = true
        for (listUrl in uniqueLists.take(8)) {
            coroutineContext.ensureActive()
            if (results.size >= maxItems) { partial = true; break }
            try {
                val found = discover(listUrl)
                results.addAll(found.items)
                partial = partial || found.partial
                references.addAll(found.references)
            } catch (e: IOException) { partial = true }
            catch (e: retrofit2.HttpException) { partial = true }
            publish()
        }
        val unique = results.distinctBy { it.id }
        return bounded(SourceOrigin.GITHUB_REPOSITORY, unique, partial || unique.size > maxItems, references).also { onUpdate(it) }
    }

    private suspend fun normalizeGithubFile(uri: URI): String {
        val p = uri.path.trim('/').split('/')
        if (!uri.host.equals("github.com", true) || p.size < 5 || p[2] != "blob") return uri.toString()
        // Resolve ambiguous slash-containing refs against GitHub, longest first.
        for (end in p.size - 1 downTo 4) {
            val ref = p.subList(3, end).joinToString("/")
            try {
                service.commit(p[0], p[1], ref)
                val b = "https://raw.githubusercontent.com/".toHttpUrl().newBuilder().addPathSegment(p[0]).addPathSegment(p[1]).addPathSegment(ref)
                p.subList(end, p.size).forEach { b.addPathSegment(it) }
                return b.build().toString()
            } catch (e: retrofit2.HttpException) { if (e.code() != 404 && e.code() != 422) throw e }
        }
        throw IOException("GitHub ref not found")
    }

    private data class Fetched(val finalUrl: String, val text: String?, val truncated: Boolean,
        val isHtml: Boolean = false, val isTransportStream: Boolean = false)
    private suspend fun fetch(initial: String, readText: Boolean, probeTransport: Boolean = false): Fetched {
        var current = initial
        val visited = mutableSetOf<String>()
        repeat(6) {
            coroutineContext.ensureActive()
            current = SourceRules.checkedUrl(current).toString()
            if (!visited.add(current)) throw IOException("Redirect loop")
            val response = service.content(current)
            val body = response.body()
            val errorBody = response.errorBody()
            try {
                if (response.code() in 300..399) {
                    val location = response.headers()["Location"] ?: throw IOException("Missing redirect location")
                    current = SourceRules.checkedUrl(URI(current).resolve(location).toString()).toString()
                    return@repeat
                }
                if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code()}")
                return withContext(Dispatchers.IO) {
                    if (!readText) return@withContext Fetched(current, null, false)
                    if (probeTransport) {
                        val bytes = ByteArray(564)
                        val stream = body.byteStream()
                        var count = 0
                        while (count < bytes.size) {
                            ensureActive()
                            val n = stream.read(bytes, count, bytes.size - count)
                            if (n < 0) break
                            count += n
                        }
                        return@withContext Fetched(current, null, false, isTransportStream = SourceRules.isTransportStream(bytes.copyOf(count)))
                    }
                    val mime = body.contentType()?.toString()?.lowercase().orEmpty()
                    val path = URI(current).path.orEmpty()
                    val textual = mime.startsWith("text/") || mime.contains("mpegurl") || mime.contains("dash+xml") || mime.contains("xml") || SourceRules.isMarkdown(path) || path.endsWith(".txt", true) || path.endsWith(".m3u", true) || path.endsWith(".m3u8", true) || path.endsWith(".mpd", true)
                    if (!textual) return@withContext Fetched(current, null, false)
                    val stream = body.byteStream()
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= maxText) {
                        ensureActive()
                        val n = stream.read(buffer, 0, minOf(buffer.size, maxText + 1 - output.size()))
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    val bytes = output.toByteArray()
                    Fetched(current, bytes.take(maxText).toByteArray().toString(Charsets.UTF_8), bytes.size > maxText, mime.startsWith("text/html"))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { body?.close(); errorBody?.close() }
            }
        }
        throw IOException("Too many redirects")
    }
    private fun item(url: String, kind: SourceKind): ImportedSource {
        val uri = SourceRules.checkedUrl(url)
        return ImportedSource(url, uri.path.substringAfterLast('/').ifEmpty { uri.host }, url, kind)
    }
    private fun bounded(origin: SourceOrigin, items: List<ImportedSource>, incomplete: Boolean, references: Set<ReferenceKind>): SourceDiscovery {
        val kept = mutableListOf<ImportedSource>()
        var size = 0
        for (item in items) {
            val next = item.url.length + item.name.length
            if (kept.size >= maxItems || size + next > 100_000) break
            kept.add(item.copy(name = item.name.take(256)))
            size += next
        }
        return SourceDiscovery(origin, kept, incomplete || kept.size < items.size, references)
    }
}
