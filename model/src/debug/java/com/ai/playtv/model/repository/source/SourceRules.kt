package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.bean.domain.source.ReferenceKind
import java.net.URI

/** Pure rules: extensions discover candidates, content determines playlist type. */
object SourceRules {
    private val media = setOf("mp4", "mkv", "webm", "ogv", "ogg", "mov", "m4v", "ts", "flv", "avi", "mp3", "m4a", "aac", "wav", "m3u", "m3u8", "mpd", "txt")
    private val urlPattern = Regex("https://[^\\s<>\\\"')\\]]+", RegexOption.IGNORE_CASE)
    fun checkedUrl(value: String): URI {
        val uri = URI(value.trim())
        require(value.length <= 2048 && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        return uri
    }
    fun candidate(path: String): Boolean = path.substringBefore('?').substringAfterLast('.', "").lowercase() in media
    fun isTransportStream(bytes: ByteArray): Boolean = bytes.size >= 377 &&
        bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() && bytes[376] == 0x47.toByte()
    fun isMarkdown(path: String) = path.endsWith(".md", true) || path.endsWith(".markdown", true)
    fun referenceKinds(text: String): Set<ReferenceKind> {
        val urls = urlPattern.findAll(text).mapNotNull { runCatching { URI(it.value).host?.lowercase() }.getOrNull() }.toList()
        return buildSet {
            if (urls.any { it == "pan.quark.cn" || it == "pan.baidu.com" || it == "www.alipan.com" || it == "www.aliyundrive.com" || it == "alipan.com" || it == "aliyundrive.com" }) add(ReferenceKind.CLOUD_SHARE)
            if (urls.any { it != "github.com" && !it.endsWith(".githubusercontent.com") && it != "pan.quark.cn" && it != "pan.baidu.com" }) add(ReferenceKind.WEBSITE)
        }
    }
    fun markdownLinks(text: String, base: URI? = null): List<String> {
        val absolute = urlPattern.findAll(text).map { it.value.trimEnd('.', ',', ';') }
        val relative = if (base == null) emptySequence() else Regex("\\]\\(<?([^\\s)>]+)").findAll(text).map { it.groupValues[1] }
            .mapNotNull { value -> runCatching { base.resolve(value).toString() }.getOrNull() }
        return (absolute + relative).mapNotNull { value ->
            val uri = runCatching { checkedUrl(value) }.getOrNull() ?: return@mapNotNull null
            if (candidate(uri.path.orEmpty()) || uri.host.equals("github.com", true) && uri.path.startsWith("/user-attachments/assets/")) uri.toString() else null
        }.distinct().toList()
    }

    /** Read only the rendered README article, not arbitrary page chrome or scripts. */
    fun githubReadmeHtmlLinks(html: String, base: URI): List<String> {
        val start = Regex("<article\\b[^>]*\\bmarkdown-body\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
            ?: return emptyList()
        val end = html.indexOf("</article", start.range.last + 1, ignoreCase = true)
        if (end < 0) return emptyList()
        val article = html.substring(start.range.last + 1, end).replace("&amp;", "&", ignoreCase = true)
        val hrefs = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(article).map { it.groupValues[1] }
            .mapNotNull { runCatching { base.resolve(it).toString() }.getOrNull() }
        return (hrefs + urlPattern.findAll(article).map { it.value.trimEnd('.', ',', ';') }).mapNotNull { value ->
            val uri = runCatching { checkedUrl(value) }.getOrNull() ?: return@mapNotNull null
            if (candidate(uri.path.orEmpty()) || uri.host.equals("github.com", true) && uri.path.startsWith("/user-attachments/assets/")) uri.toString() else null
        }.distinct().toList()
    }
    fun kind(content: String): SourceKind = when {
        content.lineSequence().any { it.trim().startsWith("#EXT-X-", true) } -> SourceKind.HLS
        Regex("<MPD(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(content) -> SourceKind.DASH
        else -> SourceKind.UNKNOWN
    }
    data class ParsedPlaylist(val items: List<ImportedSource>, val skipped: Boolean)
    fun playlist(base: URI, content: String, txt: Boolean = false): ParsedPlaylist {
        var title: String? = null
        var skipped = false
        val items = buildList {
            for (line in content.lineSequence()) {
                val value = line.trim().trimStart('\uFEFF').trim()
                if (value.startsWith("#EXTINF:", true)) title = value.substringAfter(',', "").trim().ifEmpty { null }
                else if (value.isNotEmpty() && !value.startsWith('#')) {
                    if (value.contains(",#genre#", true)) { title = null; continue }
                    val isTxtChannel = value.contains(',') && !value.startsWith("http", true)
                    val target = if (isTxtChannel) value.substringAfter(',').trim() else value
                    if (txt && !target.startsWith("https://", true) && !target.startsWith("http://", true)) { skipped = true; title = null; continue }
                    if (isTxtChannel) title = value.substringBefore(',').trim().ifEmpty { null }
                    val url = try { checkedUrl(base.resolve(target).toString()).toString() } catch (_: Exception) { skipped = true; title = null; continue }
                    add(ImportedSource(url, title ?: URI(url).path.substringAfterLast('/').ifEmpty { URI(url).host }, url, SourceKind.UNKNOWN))
                    title = null
                }
            }
        }.distinctBy { it.id }
        return ParsedPlaylist(items, skipped)
    }
}
