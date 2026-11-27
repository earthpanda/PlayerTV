package com.ai.playtv.model.repository.artwork

import com.ai.playtv.model.bean.domain.artwork.*
import com.ai.playtv.model.network.service.artwork.ArtworkHttpService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class ArtworkRepositoryTest {
    private class FakeService : ArtworkHttpService {
        val responses = mutableMapOf<String, String>()
        val authorizations = mutableListOf<String?>()
        val redirects = mutableMapOf<String, String>()
        override suspend fun get(url: String, authorization: String?): Response<ResponseBody> {
            authorizations.add(authorization)
            redirects[url]?.let { location ->
                val raw = okhttp3.Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Redirect").header("Location", location).build()
                return Response.error("".toResponseBody(), raw)
            }
            return Response.success((responses[url] ?: throw IOException("missing fixture")).toResponseBody("application/json".toMediaType()))
        }
    }

    @Test fun channelUsesPlaylistLogoFirstAndRequiresUnambiguousIndex() = runBlocking {
        val service = FakeService()
        val repo = ArtworkRepository(service)
        val direct = repo.lookup(ArtworkQuery("Channel", ArtworkMediaType.CHANNEL, channelId = "id", playlistLogoUrl = "https://example.org/logo.png"))
        assertEquals("https://example.org/logo.png", (direct as ArtworkLookupResult.Found).candidate.smallUrl)
        assertTrue(service.authorizations.isEmpty())
        service.responses["https://iptv-org.github.io/api/logos.json"] = """[{"channel":"id","url":"https://example.org/a.png","in_use":true},{"channel":"id","url":"https://example.org/b.png","in_use":true}]"""
        assertEquals(ArtworkLookupResult.Ambiguous, repo.lookup(ArtworkQuery("Channel", ArtworkMediaType.CHANNEL, channelId = "id")))
        service.responses["https://iptv-org.github.io/api/logos.json"] = """[{"channel":"id","url":"https://example.org/a.png","in_use":true},{"channel":"other","url":"https://example.org/b.png"}]"""
        val found = repo.lookup(ArtworkQuery("Channel", ArtworkMediaType.CHANNEL, channelId = "id")) as ArtworkLookupResult.Found
        assertEquals("https://example.org/a.png", found.candidate.smallUrl)
    }

    @Test fun channelNameAndAliasRequireOneDistinctChannel() = runBlocking {
        val service = FakeService()
        val channels = "https://iptv-org.github.io/api/channels.json"
        val logos = "https://iptv-org.github.io/api/logos.json"
        service.responses[channels] = """[{"id":"id","name":"Example TV","alt_names":["Example Plus"]}]"""
        service.responses[logos] = """[{"channel":"id","url":"https://example.org/a.png","in_use":true}]"""
        val repo = ArtworkRepository(service)
        for (name in listOf("example-tv", "Example Plus")) {
            val found = repo.lookup(ArtworkQuery(name, ArtworkMediaType.CHANNEL)) as ArtworkLookupResult.Found
            assertEquals("https://example.org/a.png", found.candidate.smallUrl)
        }
        assertEquals(ArtworkLookupResult.NoMatch, repo.lookup(ArtworkQuery("missing", ArtworkMediaType.CHANNEL)))
        service.responses[channels] = """[{"id":"id","name":"Example TV"},{"id":"other","alt_names":["Example TV"]}]"""
        assertEquals(ArtworkLookupResult.Ambiguous, repo.lookup(ArtworkQuery("Example TV", ArtworkMediaType.CHANNEL)))
    }

    @Test fun channelIndexEnforcesResponseLimitAndPropagatesCancellation() = runBlocking {
        val service = FakeService()
        service.responses["https://iptv-org.github.io/api/channels.json"] = "[" + " ".repeat(16 * 1024 * 1024) + "]"
        try { ArtworkRepository(service).lookup(ArtworkQuery("name", ArtworkMediaType.CHANNEL)); fail("limit expected") }
        catch (_: IOException) { }
        val cancelled = object : ArtworkHttpService {
            override suspend fun get(url: String, authorization: String?): Response<ResponseBody> { throw CancellationException("cancelled") }
        }
        try { ArtworkRepository(cancelled).lookup(ArtworkQuery("name", ArtworkMediaType.CHANNEL)); fail("cancellation expected") }
        catch (_: CancellationException) { }
    }

    @Test fun tvUsesExactTitleAndYearAndFindsDifferentImageSlots() = runBlocking {
        val service = FakeService()
        val url = "https://api.tvmaze.com/search/shows?q=Show"
        service.responses[url] = """[{"show":{"id":1,"name":"Show","premiered":"2010-01-01"}},{"show":{"id":2,"name":"Show","premiered":"2020-01-01"}}]"""
        service.responses["https://api.tvmaze.com/shows/1/images"] = """[{"type":"poster","main":true,"resolutions":{"original":{"url":"https://example.org/poster.jpg"}}},{"type":"background","main":true,"resolutions":{"original":{"url":"https://example.org/background.jpg"}}}]"""
        val repo = ArtworkRepository(service)
        assertEquals(ArtworkLookupResult.Ambiguous, repo.lookup(ArtworkQuery("Show", ArtworkMediaType.TV_SHOW)))
        val found = repo.lookup(ArtworkQuery("Show", ArtworkMediaType.TV_SHOW, year = 2010)) as ArtworkLookupResult.Found
        assertEquals("https://example.org/poster.jpg", found.candidate.smallUrl)
        assertEquals("https://example.org/background.jpg", found.candidate.backgroundUrl)
        assertEquals(ArtworkLookupResult.NoMatch, repo.lookup(ArtworkQuery("Show", ArtworkMediaType.TV_SHOW, year = 2000)))
    }

    @Test fun tvKeepsSearchPosterWhenImageListFails() = runBlocking {
        val service = FakeService()
        service.responses["https://api.tvmaze.com/search/shows?q=Show"] = """[{"show":{"id":1,"name":"Show","image":{"original":"https://example.org/poster.jpg"}}}]"""
        val found = ArtworkRepository(service).lookup(ArtworkQuery("Show", ArtworkMediaType.TV_SHOW)) as ArtworkLookupResult.Found
        assertEquals("https://example.org/poster.jpg", found.candidate.smallUrl)
        assertNull(found.candidate.backgroundUrl)
    }

    @Test fun movieRequiresLocalTokenAndUsesItOnlyOnItsOwnHost() = runBlocking {
        val service = FakeService()
        val query = ArtworkQuery("Film", ArtworkMediaType.MOVIE, year = 2020)
        assertEquals(ArtworkLookupResult.ConfigurationRequired, ArtworkRepository(service).lookup(query))
        val url = "https://api.themoviedb.org/3/search/movie?query=Film&year=2020"
        service.responses[url] = """{"results":[{"id":1,"title":"Film","release_date":"2020-01-01","poster_path":"/poster.jpg","backdrop_path":"/wide.jpg"}]}"""
        val found = ArtworkRepository(service, movieBearerToken = "sample-token").lookup(query) as ArtworkLookupResult.Found
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", found.candidate.smallUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/wide.jpg", found.candidate.backgroundUrl)
        assertEquals(listOf("Bearer sample-token"), service.authorizations)
        service.authorizations.clear()
        service.redirects[url] = "https://other.example/result"
        service.responses["https://other.example/result"] = """{"results":[]}"""
        assertEquals(ArtworkLookupResult.NoMatch, ArtworkRepository(service, movieBearerToken = "sample-token").lookup(query))
        assertEquals(listOf("Bearer sample-token", null), service.authorizations)
    }

    @Test fun imagesEnforceHttpsRedirectAndSizeLimit() = runBlocking {
        val service = FakeService()
        service.redirects["https://example.org/a"] = "https://other.example/b"
        service.responses["https://other.example/b"] = "0123456789"
        val repo = ArtworkRepository(service)
        try { repo.readImage("https://example.org/a", 4) { it.readBytes() }; fail("limit expected") } catch (_: IOException) { }
        assertEquals(listOf(null, null), service.authorizations)
        try { repo.readImage("http://example.org/a", 10) { it.readBytes() }; fail("https required") } catch (_: IOException) { }
        service.redirects["https://example.org/a"] = "http://example.org/b"
        try { repo.readImage("https://example.org/a", 10) { it.readBytes() }; fail("https redirect required") } catch (_: IOException) { }
    }
}
