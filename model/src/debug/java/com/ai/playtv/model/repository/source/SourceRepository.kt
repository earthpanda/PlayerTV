package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.*
import com.ai.playtv.model.network.service.source.GithubSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Ordered discovery: raw README, repository API, then the rendered README article. */
    private suspend fun repository(owner: String, repo: String, onUpdate: suspend (SourceDiscovery) -> Unit): SourceDiscovery {
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repo.matches(Regex("[A-Za-z0-9_.-]+")))
        val rawRoot = "https://raw.githubusercontent.com/".toHttpUrl().newBuilder()
            .addPathSegment(owner).addPathSegment(repo).build().toString().trimEnd('/') + "/"
        var branch: String? = null
        val results = mutableListOf<ImportedSource>()
        val references = mutableSetOf<ReferenceKind>()
        val scannedDocuments = mutableListOf<String>()
        val scannedLists = mutableListOf<String>()
        val scannedTransport = mutableListOf<String>()
        var partial = false
        var rateLimited = false
        var apiFailure: Exception? = null

        fun raw(ref: String, path: String): String {
            val builder = "https://raw.githubusercontent.com/".toHttpUrl().newBuilder()
                .addPathSegment(owner).addPathSegment(repo).addPathSegment(ref)
            path.split('/').forEach { builder.addPathSegment(it) }
            return builder.build().toString()
        }
        // HEAD and the API-reported default branch refer to the same repository snapshot.
        fun canonical(url: String): String {
            val prefixes = listOfNotNull("HEAD", branch).map { raw(it, "") }
            val prefix = prefixes.firstOrNull { url.startsWith(it) } ?: return url
            return rawRoot + "<default>/" + url.removePrefix(prefix)
        }
        fun seen(url: String, existing: List<String>): Boolean = existing.any { canonical(it) == canonical(url) }
        fun isList(path: String): Boolean = listOf(".m3u", ".m3u8", ".mpd", ".txt").any { path.endsWith(it, true) }
        fun isTs(path: String): Boolean = path.endsWith(".ts", true)
        fun add(source: ImportedSource): Boolean {
            if (results.any { canonical(it.url) == canonical(source.url) }) return false
            if (results.size >= maxItems) { partial = true; return false }
            results.add(source)
            return true
        }
        suspend fun publish() {
            onUpdate(bounded(SourceOrigin.GITHUB_REPOSITORY, results, partial, references.toSet(), rateLimited))
        }
        suspend fun processLinks(links: List<String>) {
            val lists = mutableListOf<String>()
            val transport = mutableListOf<String>()
            var added = false
            for (link in links) {
                val path = URI(link).path.orEmpty()
                when {
                    isList(path) -> lists.add(link)
                    isTs(path) -> transport.add(link)
                    else -> if (add(item(link, SourceKind.UNKNOWN))) added = true
                }
            }
            if (added) publish()
            for (url in transport) {
                coroutineContext.ensureActive()
                if (results.size >= maxItems) { partial = true; break }
                if (seen(url, scannedTransport)) continue
                if (scannedTransport.size >= 8) { partial = true; break }
                scannedTransport.add(url)
                try {
                    val probe = withTimeoutOrNull(5_000L) { fetch(url, true, probeTransport = true) }
                    if (probe == null) partial = true
                    else if (probe.isTransportStream && add(item(url, SourceKind.MEDIA))) publish()
                } catch (e: IOException) { partial = true }
            }
            for (url in lists) {
                coroutineContext.ensureActive()
                if (results.size >= maxItems) { partial = true; break }
                if (seen(url, scannedLists)) continue
                if (scannedLists.size >= 8) { partial = true; break }
                scannedLists.add(url)
                try {
                    val found = withTimeoutOrNull(5_000L) { discover(url) }
                    if (found == null) { partial = true; continue }
                    var changed = false
                    found.items.forEach { if (add(it)) changed = true }
                    partial = partial || found.partial
                    references.addAll(found.references)
                    if (changed) publish()
                } catch (e: IOException) { partial = true }
                catch (e: retrofit2.HttpException) { partial = true; rateLimited = rateLimited || e.isGithubRateLimited() }
            }
        }
        suspend fun scanMarkdown(url: String) {
            if (seen(url, scannedDocuments)) return
            val fetched = fetch(url, true)
            scannedDocuments.add(url)
            partial = partial || fetched.truncated
            fetched.text?.let { text ->
                references.addAll(SourceRules.referenceKinds(text))
                processLinks(SourceRules.markdownLinks(text, URI(fetched.finalUrl)))
            }
        }

        val readmeFinished = withTimeoutOrNull(15_000L) {
            try { scanMarkdown(raw("HEAD", "README.md")) }
            catch (e: SourceHttpFailure) { if (e.statusCode != 404) partial = true }
            catch (e: IOException) { partial = true }
            true
        }
        if (readmeFinished == null) partial = true

        if (results.size < maxItems) {
            val apiFinished = withTimeoutOrNull(30_000L) {
                try {
                    val defaultBranch = service.repository(owner, repo).defaultBranch?.takeIf { it.isNotBlank() }
                        ?: throw IOException("Missing default branch")
                    branch = defaultBranch
                    val tree = service.tree(owner, repo, defaultBranch)
                    val files = (tree.tree ?: throw IOException("Missing repository tree"))
                        .filter { it.type == "blob" && it.path != null }
                    partial = partial || tree.truncated != false
                    val media = files.filter { SourceRules.candidate(it.path!!) && !isList(it.path!!) && !isTs(it.path!!) }
                    processLinks(media.map { raw(defaultBranch, it.path!!) })
                    val markdown = files.filter { SourceRules.isMarkdown(it.path!!) }
                    if (markdown.size > maxMarkdown) partial = true
                    for (entry in markdown.take(maxMarkdown)) {
                        coroutineContext.ensureActive()
                        if (results.size >= maxItems) { partial = true; break }
                        try { scanMarkdown(raw(defaultBranch, entry.path!!)) }
                        catch (e: IOException) { partial = true }
                        catch (e: retrofit2.HttpException) { partial = true; rateLimited = rateLimited || e.isGithubRateLimited() }
                    }
                    val remaining = files.filter { isList(it.path!!) || isTs(it.path!!) }
                        .map { raw(defaultBranch, it.path!!) }
                    processLinks(remaining)
                } catch (e: IOException) {
                    apiFailure = e; partial = true
                } catch (e: retrofit2.HttpException) {
                    apiFailure = e; partial = true; rateLimited = e.isGithubRateLimited()
                } catch (e: com.google.gson.JsonParseException) {
                    apiFailure = e; partial = true
                }
                true
            }
            if (apiFinished == null) partial = true
        } else partial = true
        if (results.isNotEmpty()) publish()

        if (results.size < maxItems) {
            val htmlFinished = withTimeoutOrNull(15_000L) {
                try {
                    val page = fetch("https://github.com/$owner/$repo", true)
                    partial = partial || page.truncated
                    page.text?.let { html -> processLinks(SourceRules.githubReadmeHtmlLinks(html, URI(page.finalUrl))) }
                } catch (e: IOException) { partial = true }
                catch (e: retrofit2.HttpException) { partial = true }
                true
            }
            if (htmlFinished == null) partial = true
        }
        if (results.isEmpty() && apiFailure != null) throw apiFailure!!
        return bounded(SourceOrigin.GITHUB_REPOSITORY, results, partial, references.toSet(), rateLimited).also { onUpdate(it) }
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
                if (!response.isSuccessful) throw SourceHttpFailure(response.code())
                if (body == null) throw IOException("Missing response body")
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
    private fun bounded(origin: SourceOrigin, items: List<ImportedSource>, incomplete: Boolean, references: Set<ReferenceKind>,
        rateLimited: Boolean = false): SourceDiscovery {
        val kept = mutableListOf<ImportedSource>()
        var size = 0
        for (item in items) {
            val next = item.url.length + item.name.length
            if (kept.size >= maxItems || size + next > 100_000) break
            kept.add(item.copy(name = item.name.take(256)))
            size += next
        }
        return SourceDiscovery(origin, kept, incomplete || kept.size < items.size, references, rateLimited)
    }
}
