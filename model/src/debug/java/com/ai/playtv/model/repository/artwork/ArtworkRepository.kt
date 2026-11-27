package com.ai.playtv.model.repository.artwork

import com.ai.playtv.model.bean.domain.artwork.*
import com.ai.playtv.model.bean.net.artwork.*
import com.ai.playtv.model.network.service.artwork.ArtworkHttpService
import com.ai.playtv.model.repository.source.SourceRules
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.text.Normalizer
import kotlin.coroutines.coroutineContext

/** Experimental metadata lookup. It does not establish an image licence or enable product UI. */
class ArtworkRepository(
    private val service: ArtworkHttpService,
    private val gson: Gson = Gson(),
    private val movieBearerToken: String? = null,
    private val cacheChannelIndexes: Boolean = false,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val channelIndexMutex = Mutex()
    private var cachedNames: ChannelNames? = null
    private var cachedLogos: Map<String, LogoChoices>? = null
    private var channelRetryAt: Long? = null

    suspend fun lookup(query: ArtworkQuery): ArtworkLookupResult {
        require(query.title.length <= 256 && (query.channelId?.length ?: 0) <= 256)
        return when (query.type) {
            ArtworkMediaType.CHANNEL -> channel(query)
            ArtworkMediaType.TV_SHOW -> show(query)
            ArtworkMediaType.MOVIE -> movie(query)
            ArtworkMediaType.UNKNOWN -> ArtworkLookupResult.NoMatch
        }
    }

    private suspend fun channel(query: ArtworkQuery): ArtworkLookupResult {
        safeUrl(query.playlistLogoUrl)?.let { return ArtworkLookupResult.Found(ArtworkCandidate(smallUrl = it, provider = "playlist")) }
        val sourceId = query.channelId?.trim()?.takeIf { it.isNotEmpty() }
        if (sourceId != null) {
            val byId = channelLogo(sourceId)
            if (byId != ArtworkLookupResult.NoMatch) return byId
        }
        val name = normalized(query.title).takeIf { it.isNotEmpty() } ?: return ArtworkLookupResult.NoMatch
        val names = channelNames(name, sourceId)
        if (sourceId != null && sourceId in names.knownIds) return ArtworkLookupResult.NoMatch
        val ids = names.byName[name].orEmpty()
        val matchedId = when (ids.size) {
            0 -> return ArtworkLookupResult.NoMatch
            1 -> ids.single()
            else -> return ArtworkLookupResult.Ambiguous
        }
        if (matchedId == sourceId) return ArtworkLookupResult.NoMatch
        return channelLogo(matchedId)
    }

    private class ChannelNames(val byName: Map<String, Set<String>>, val knownIds: Set<String>)

    private suspend fun channelNames(name: String, sourceId: String?): ChannelNames {
        if (!cacheChannelIndexes) return readChannelNames(name, sourceId)
        return channelIndexMutex.withLock {
            cachedNames ?: loadChannelIndex { readChannelNames(null, null) }.also { cachedNames = it }
        }
    }

    private suspend fun readChannelNames(onlyName: String?, sourceId: String?): ChannelNames =
        readJson(CHANNEL_INDEX, MAX_INDEX_BYTES) { reader ->
            val found = mutableMapOf<String, MutableSet<String>>()
            val known = mutableSetOf<String>()
            var records = 0
            var associations = 0
            reader.beginArray()
            while (reader.hasNext()) {
                coroutineContext.ensureActive()
                if (++records > MAX_INDEX_RECORDS) throw IOException("Artwork index record limit exceeded")
                val channel = gson.fromJson<ChannelDto?>(reader, ChannelDto::class.java) ?: continue
                val id = channel.id?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: continue
                if (onlyName == null || id == sourceId) known.add(id)
                for (raw in listOfNotNull(channel.name) + channel.alternativeNames.orEmpty().filterNotNull()) {
                    if (++associations > MAX_NAME_ASSOCIATIONS) throw IOException("Artwork index name limit exceeded")
                    if (raw.length > MAX_INDEX_NAME_LENGTH) continue
                    val key = normalized(raw)
                    if (key.isEmpty() || (onlyName != null && key != onlyName)) continue
                    val ids = found.getOrPut(key) { mutableSetOf() }
                    if (ids.size < 2) ids.add(id)
                }
            }
            reader.endArray()
            ChannelNames(found, known)
        }

    private suspend fun channelLogo(id: String): ArtworkLookupResult {
        val choices = if (!cacheChannelIndexes) readChannelLogos(id)[id] else channelIndexMutex.withLock {
            val index = cachedLogos ?: loadChannelIndex { readChannelLogos(null) }.also { cachedLogos = it }
            index[id]
        }
        val urls = if (choices == null) emptySet() else choices.active.ifEmpty { choices.other }
        return when (urls.size) {
            0 -> ArtworkLookupResult.NoMatch
            1 -> ArtworkLookupResult.Found(ArtworkCandidate(smallUrl = urls.single(), provider = "channel-index"))
            else -> ArtworkLookupResult.Ambiguous
        }
    }

    private class LogoChoices {
        val active = mutableSetOf<String>()
        val other = mutableSetOf<String>()
        fun add(url: String, inUse: Boolean) {
            val target = if (inUse) active else other
            if (target.size < 2) target.add(url)
        }
    }

    /** Called under channelIndexMutex; cancellation never starts a retry delay. */
    private suspend fun <T> loadChannelIndex(load: suspend () -> T): T {
        val now = monotonicNanos()
        if (channelRetryAt?.let { now - it < 0 } == true) throw IOException("Artwork index retry delayed")
        return try {
            load().also { channelRetryAt = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            channelRetryAt = monotonicNanos() + CHANNEL_RETRY_DELAY_NANOS
            throw error
        }
    }

    private suspend fun readChannelLogos(onlyId: String?): Map<String, LogoChoices> =
        readJson(LOGO_INDEX, MAX_INDEX_BYTES) { reader ->
            val found = mutableMapOf<String, LogoChoices>()
            var records = 0
            reader.beginArray()
            while (reader.hasNext()) {
                coroutineContext.ensureActive()
                if (++records > MAX_INDEX_RECORDS) throw IOException("Artwork index record limit exceeded")
                val logo = gson.fromJson<ChannelLogoDto?>(reader, ChannelLogoDto::class.java) ?: continue
                val id = logo.channel?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: continue
                if (onlyId != null && id != onlyId) continue
                val url = safeUrl(logo.url) ?: continue
                found.getOrPut(id) { LogoChoices() }.add(url, logo.inUse == true)
            }
            reader.endArray()
            found
        }

    private suspend fun show(query: ArtworkQuery): ArtworkLookupResult {
        val name = normalized(query.title).takeIf { it.isNotEmpty() } ?: return ArtworkLookupResult.NoMatch
        val url = TV_SEARCH.toHttpUrl().newBuilder().addQueryParameter("q", query.title.trim()).build().toString()
        val results = readArray(url, MAX_JSON_BYTES, ShowSearchDto::class.java)
        val candidates = results.mapNotNull { it.show }.filter { show ->
            show.id != null && show.name?.let(::normalized) == name && matchesYear(query.year, show.premiered)
        }.distinctBy { it.id }
        if (candidates.isEmpty()) return ArtworkLookupResult.NoMatch
        if (candidates.size > 1) return ArtworkLookupResult.Ambiguous
        val selected = candidates.single()
        val fallback = safeUrl(selected.image?.original ?: selected.image?.medium)
        val images = try {
            readArray("https://api.tvmaze.com/shows/${selected.id}/images", MAX_JSON_BYTES, ShowArtworkDto::class.java)
        } catch (error: IOException) {
            if (fallback != null) return result(fallback, null, "tvmaze")
            throw error
        }
        fun select(type: String): String? {
            val options = images.filter { it.type == type }
            val preferred = options.firstOrNull { it.main == true } ?: options.firstOrNull()
            return safeUrl(preferred?.resolutions?.original?.url ?: preferred?.resolutions?.medium?.url)
        }
        val small = select("poster") ?: fallback
        val background = select("background")
        return result(small, background, "tvmaze")
    }

    private suspend fun movie(query: ArtworkQuery): ArtworkLookupResult {
        val token = movieBearerToken?.trim()?.takeIf { it.isNotEmpty() } ?: return ArtworkLookupResult.ConfigurationRequired
        require(token.none { it == '\r' || it == '\n' })
        val name = normalized(query.title).takeIf { it.isNotEmpty() } ?: return ArtworkLookupResult.NoMatch
        val url = MOVIE_SEARCH.toHttpUrl().newBuilder().addQueryParameter("query", query.title.trim())
            .apply { query.year?.let { addQueryParameter("year", it.toString()) } }.build().toString()
        val data = readJson(url, MAX_JSON_BYTES, "Bearer $token") { reader -> gson.fromJson<MovieSearchDto>(reader, MovieSearchDto::class.java) }
        val candidates = data.results.orEmpty().filter { movie ->
            movie.id != null && listOfNotNull(movie.title, movie.originalTitle).any { normalized(it) == name } &&
                matchesYear(query.year, movie.releaseDate)
        }.distinctBy { it.id }
        if (candidates.isEmpty()) return ArtworkLookupResult.NoMatch
        if (candidates.size > 1) return ArtworkLookupResult.Ambiguous
        val selected = candidates.single()
        return result(movieImage(selected.posterPath, "w500"), movieImage(selected.backdropPath, "w1280"), "tmdb")
    }

    /** The consumer owns only the duration of the callback; the repository always closes the response. */
    suspend fun <T> readImage(url: String, maxBytes: Long, consumer: suspend (InputStream) -> T): T {
        require(maxBytes in 1..MAX_IMAGE_BYTES)
        return readResponse(url, maxBytes, null) { body -> consumer(body) }
    }

    private suspend fun <T> readArray(url: String, limit: Long, type: Class<T>): List<T> = readJson(url, limit) { reader ->
        val results = mutableListOf<T>()
        reader.beginArray()
        while (reader.hasNext()) {
            coroutineContext.ensureActive()
            if (results.size >= MAX_RESULTS) throw IOException("Artwork result limit exceeded")
            results.add(gson.fromJson<T>(reader, type))
        }
        reader.endArray()
        results
    }

    private suspend fun <T> readJson(url: String, limit: Long, authorization: String? = null,
        consume: suspend (JsonReader) -> T): T = readResponse(url, limit, authorization) { input ->
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader -> consume(reader) }
    }

    private suspend fun <T> readResponse(url: String, limit: Long, authorization: String?, consume: suspend (InputStream) -> T): T {
        var current = safeUrl(url) ?: throw IOException("Invalid artwork URL")
        val original = URI(current)
        val visited = mutableSetOf<String>()
        repeat(6) {
            coroutineContext.ensureActive()
            if (!visited.add(current)) throw IOException("Artwork redirect loop")
            val target = URI(current)
            val sameOrigin = target.host.equals(original.host, true) &&
                (if (target.port < 0) 443 else target.port) == (if (original.port < 0) 443 else original.port)
            val response = service.get(current, authorization.takeIf { sameOrigin })
            val body = response.body()
            val error = response.errorBody()
            try {
                if (response.code() in 300..399) {
                    val location = response.headers()["Location"] ?: throw IOException("Missing artwork redirect")
                    current = safeUrl(URI(current).resolve(location).toString()) ?: throw IOException("Invalid artwork redirect")
                    return@repeat
                }
                if (!response.isSuccessful) throw IOException("Artwork HTTP ${response.code()}")
                if (body == null) throw IOException("Missing artwork body")
                if (body.contentLength() > limit) throw IOException("Artwork response exceeds limit")
                return withContext(Dispatchers.IO) { consume(LimitedInputStream(body.byteStream(), limit)) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { body?.close(); error?.close() }
            }
        }
        throw IOException("Too many artwork redirects")
    }

    private fun result(small: String?, background: String?, provider: String): ArtworkLookupResult =
        if (small == null && background == null) ArtworkLookupResult.NoMatch
        else ArtworkLookupResult.Found(ArtworkCandidate(small, background, provider))

    private fun movieImage(path: String?, size: String): String? {
        if (path == null || !path.matches(Regex("/[A-Za-z0-9_.-]+"))) return null
        return "https://image.tmdb.org/t/p/$size$path"
    }

    private fun safeUrl(value: String?): String? = value?.let { runCatching { SourceRules.checkedUrl(it).toString() }.getOrNull() }
    private fun matchesYear(year: Int?, date: String?): Boolean = year == null || date?.take(4)?.toIntOrNull() == year
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > limit) throw IOException("Artwork response exceeds limit")
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val allowed = minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt()
            val read = super.read(bytes, offset, allowed)
            if (read > 0) { count += read; if (count > limit) throw IOException("Artwork response exceeds limit") }
            return read
        }
    }

    private companion object {
        const val LOGO_INDEX = "https://iptv-org.github.io/api/logos.json"
        const val CHANNEL_INDEX = "https://iptv-org.github.io/api/channels.json"
        const val TV_SEARCH = "https://api.tvmaze.com/search/shows"
        const val MOVIE_SEARCH = "https://api.themoviedb.org/3/search/movie"
        const val MAX_INDEX_BYTES = 16L * 1024 * 1024
        const val MAX_JSON_BYTES = 2L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val MAX_RESULTS = 200
        const val MAX_INDEX_RECORDS = 150_000
        const val MAX_NAME_ASSOCIATIONS = 300_000
        const val MAX_INDEX_NAME_LENGTH = 4096
        const val CHANNEL_RETRY_DELAY_NANOS = 30_000_000_000L
    }
}
