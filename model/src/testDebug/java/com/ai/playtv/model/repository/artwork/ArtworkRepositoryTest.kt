package com.ai.playtv.model.repository.artwork

import com.ai.playtv.model.bean.domain.artwork.*
import com.ai.playtv.model.network.service.artwork.ArtworkHttpService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
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
        val requests = mutableListOf<String>()
        val redirects = mutableMapOf<String, String>()
        override suspend fun get(url: String, authorization: String?): Response<ResponseBody> {
            requests.add(url)
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

    @Test fun unknownSourceIdFallsBackToExactNameWithoutChoosingAmbiguousResults() = runBlocking {
        val service = FakeService()
        val channels = "https://iptv-org.github.io/api/channels.json"
        val logos = "https://iptv-org.github.io/api/logos.json"
        service.responses[logos] = """[{"channel":"standard.id","url":"https://example.org/one.png","in_use":true}]"""
        service.responses[channels] = """[{"id":"standard.id","name":"Example TV","alt_names":[null,"Example Plus"]}]"""
        val query = ArtworkQuery("Example Plus", ArtworkMediaType.CHANNEL, channelId = "local-id")
        val found = ArtworkRepository(service).lookup(query) as ArtworkLookupResult.Found
        assertEquals("https://example.org/one.png", found.candidate.smallUrl)
        service.responses[channels] = """[{"id":"standard.id","name":"Example Plus"},{"id":"other.id","name":"Example Plus"}]"""
        assertEquals(ArtworkLookupResult.Ambiguous, ArtworkRepository(service).lookup(query))
        service.responses[channels] = """[{"id":"standard.id","name":"Example Plus"}]"""
        service.responses[logos] = """[{"channel":"standard.id","url":"https://example.org/one.png","in_use":true},{"channel":"standard.id","url":"https://example.org/two.png","in_use":true}]"""
        assertEquals(ArtworkLookupResult.Ambiguous, ArtworkRepository(service).lookup(query))
    }

    @Test fun channelIndexCacheIsSharedWithinRepositoryButNotAcrossRepositories() = runBlocking {
        val service = FakeService()
        val channels = "https://iptv-org.github.io/api/channels.json"
        val logos = "https://iptv-org.github.io/api/logos.json"
        service.responses[channels] = """[{"id":"one.id","name":"One"},{"id":"two.id","name":"Two"}]"""
        service.responses[logos] = """[{"channel":"one.id","url":"https://example.org/one.png"},{"channel":"two.id","url":"https://example.org/two.png"}]"""
        val repo = ArtworkRepository(service, cacheChannelIndexes = true)
        val results = listOf(async { repo.lookup(ArtworkQuery("One", ArtworkMediaType.CHANNEL, channelId = "local-one")) },
            async { repo.lookup(ArtworkQuery("Two", ArtworkMediaType.CHANNEL, channelId = "local-two")) }).awaitAll()
        assertEquals("https://example.org/one.png", (results[0] as ArtworkLookupResult.Found).candidate.smallUrl)
        assertEquals("https://example.org/two.png", (results[1] as ArtworkLookupResult.Found).candidate.smallUrl)
        assertEquals(1, service.requests.count { it == channels })
        assertEquals(1, service.requests.count { it == logos })
        service.responses[logos] = """[{"channel":"one.id","url":"https://example.org/new.png"}]"""
        val refreshed = ArtworkRepository(service, cacheChannelIndexes = true)
            .lookup(ArtworkQuery("One", ArtworkMediaType.CHANNEL, channelId = "local-one")) as ArtworkLookupResult.Found
        assertEquals("https://example.org/new.png", refreshed.candidate.smallUrl)
        assertEquals(2, service.requests.count { it == logos })
    }

    @Test fun knownChannelIdWithoutLogoDoesNotFallBackToAnotherChannel() = runBlocking {
        val service = FakeService()
        service.responses["https://iptv-org.github.io/api/channels.json"] =
            """[{"id":"known.id","name":"Original"},{"id":"other.id","name":"Example"}]"""
        service.responses["https://iptv-org.github.io/api/logos.json"] =
            """[{"channel":"other.id","url":"https://example.org/other.png"}]"""
        val query = ArtworkQuery("Example", ArtworkMediaType.CHANNEL, channelId = "known.id")
        assertEquals(ArtworkLookupResult.NoMatch, ArtworkRepository(service, cacheChannelIndexes = true).lookup(query))
    }

    @Test fun failedIndexLoadIsThrottledAndRetriedAfterCooldown() = runBlocking {
        val service = FakeService()
        val channels = "https://iptv-org.github.io/api/channels.json"
        val logos = "https://iptv-org.github.io/api/logos.json"
        service.responses[logos] = """[{"channel":"standard.id","url":"https://example.org/one.png"}]"""
        var now = 1L
        val repo = ArtworkRepository(service, cacheChannelIndexes = true, monotonicNanos = { now })
        val query = ArtworkQuery("Example", ArtworkMediaType.CHANNEL, channelId = "local-id")
        try { repo.lookup(query); fail("missing index expected") } catch (_: IOException) { }
        service.responses[channels] = """[{"id":"standard.id","name":"Example"}]"""
        try { repo.lookup(query); fail("retry must be delayed") } catch (_: IOException) { }
        assertEquals(1, service.requests.count { it == channels })
        now += 30_000_000_000L
        assertTrue(repo.lookup(query) is ArtworkLookupResult.Found)
        assertEquals(2, service.requests.count { it == channels })
        assertEquals(1, service.requests.count { it == logos })
    }

    @Test fun cancellingIndexLoadAllowsNextLookupToRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        var logoRequests = 0
        val service = object : ArtworkHttpService {
            override suspend fun get(url: String, authorization: String?): Response<ResponseBody> {
                if (url.endsWith("/logos.json")) {
                    logoRequests++
                    if (logoRequests == 1) { started.complete(Unit); blocked.await() }
                    return Response.success("""[{"channel":"id","url":"https://example.org/logo.png"}]""".toResponseBody())
                }
                throw IOException("unexpected fixture")
            }
        }
        val repo = ArtworkRepository(service, cacheChannelIndexes = true)
        val query = ArtworkQuery("Example", ArtworkMediaType.CHANNEL, channelId = "id")
        val first = async { repo.lookup(query) }
        started.await()
        first.cancel()
        try { first.await(); fail("cancellation expected") } catch (_: CancellationException) { }
        assertTrue(repo.lookup(query) is ArtworkLookupResult.Found)
        assertEquals(2, logoRequests)
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
