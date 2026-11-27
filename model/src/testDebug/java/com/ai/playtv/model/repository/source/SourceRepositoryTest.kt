package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.bean.domain.source.ReferenceKind
import com.ai.playtv.model.bean.net.source.GithubRepositoryDto
import com.ai.playtv.model.bean.net.source.GithubTreeDto
import com.ai.playtv.model.bean.net.source.GithubTreeEntryDto
import com.ai.playtv.model.network.service.source.GithubSourceService
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.ai.playtv.model.manager.source.SourceDiscoverySession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class SourceRepositoryTest {
    private class FakeService : GithubSourceService {
        var treeResult = GithubTreeDto(false, emptyList())
        val bodies = mutableMapOf<String, Pair<String, String>>()
        val responseBodies = mutableMapOf<String, ResponseBody>()
        val statuses = mutableMapOf<String, Int>()
        var repositoryFailure: Exception? = null
        val redirects = mutableMapOf<String, String>()
        var beforeContent: suspend (String) -> Unit = {}
        var beforeRepository: suspend () -> Unit = {}
        override suspend fun repository(owner: String, repo: String): GithubRepositoryDto {
            beforeRepository()
            repositoryFailure?.let { throw it }
            return GithubRepositoryDto("main")
        }
        override suspend fun tree(owner: String, repo: String, ref: String) = treeResult
        override suspend fun commit(owner: String, repo: String, ref: String) = JsonObject()
        override suspend fun content(url: String): Response<ResponseBody> {
            beforeContent(url)
            statuses[url]?.let { return Response.error(it, "".toResponseBody()) }
            responseBodies[url]?.let { return Response.success(it) }
            redirects[url]?.let { location ->
                val raw = okhttp3.Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Redirect").header("Location", location).build()
                return Response.error("".toResponseBody(), raw)
            }
            val (mime, body) = bodies[url] ?: throw IOException("fixture missing")
            return Response.success(body.toResponseBody(mime.toMediaType()))
        }
    }

    @Test fun directVideoAndHlsEachBecomeOneSource() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://example.org/video"] = "video/mp4" to "binary"
        fake.bodies["https://example.org/playlist"] = "text/plain" to "#EXTM3U\n#EXT-X-TARGETDURATION:6\npart.ts"
        val repo = SourceRepository(fake)
        assertEquals(1, repo.discover("https://example.org/video").items.size)
        val hls = repo.discover("https://example.org/playlist")
        assertEquals(1, hls.items.size)
        assertEquals(SourceKind.HLS, hls.items.single().kind)
    }

    @Test fun readmeFollowsOneLinkedPageAndParsesItsSourcesBeforeRepositoryApi() = runBlocking {
        val fake = FakeService()
        val readme = "https://raw.githubusercontent.com/example/repo/HEAD/README.md"
        val visited = mutableListOf<String>()
        fake.beforeContent = { visited.add(it) }
        fake.bodies[readme] = "text/plain" to "[direct](https://example.org/start.mp4) [page](https://example.org/page)"
        fake.bodies["https://example.org/page"] = "text/html" to """
            <a href="channels.m3u">channels</a><a href="config.json">config</a>
            <a href="https://example.org/deeper">deeper</a><script>https://example.org/script.mp4</script>
        """.trimIndent()
        fake.bodies["https://example.org/channels.m3u"] = "text/plain" to
            "#EXTM3U\n#EXTINF:-1 tvg-logo=\"../logo.png\",News\nhttps://example.org/live.m3u8"
        fake.bodies["https://example.org/config.json"] = "application/json" to
            """{"lives":[{"url":"https://example.org/other.mp4"}]}"""
        fake.beforeRepository = {
            assertTrue("https://example.org/page" in visited)
            assertTrue("https://example.org/channels.m3u" in visited)
        }
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(listOf("https://example.org/start.mp4", "https://example.org/live.m3u8",
            "https://example.org/other.mp4"), result.items.map { it.url })
        assertEquals("https://example.org/logo.png", result.items[1].logoUrl)
        assertFalse("https://example.org/deeper" in visited)
        assertFalse(result.items.any { it.url.endsWith("script.mp4") })
    }

    @Test fun readmeFollowsLinkedRepositoryWithoutFollowingItsOrdinaryLinks() = runBlocking {
        val fake = FakeService()
        val visited = mutableListOf<String>()
        fake.beforeContent = { visited.add(it) }
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/README.md"] =
            "text/plain" to "[linked](https://github.com/other/project)"
        fake.bodies["https://raw.githubusercontent.com/other/project/HEAD/README.md"] =
            "text/plain" to "[video](https://example.org/video.mp4) [page](https://example.org/deeper)"
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(listOf("https://example.org/video.mp4"), result.items.map { it.url })
        assertFalse("https://example.org/deeper" in visited)
    }

    @Test fun linkedEndpointsCanReturnJsonOrPlaylistWithoutFileExtensions() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/README.md"] = "text/plain" to
            "[config](https://example.org/config) <a href='https://example.org/list'>list</a>"
        fake.bodies["https://example.org/config"] = "application/json" to
            """{"lives":[{"url":"https://example.org/video.mp4"}]}"""
        fake.bodies["https://example.org/list"] = "application/vnd.apple.mpegurl" to
            "#EXTM3U\n#EXTINF:-1,News\nhttps://example.org/live.m3u8"
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(listOf("https://example.org/video.mp4", "https://example.org/live.m3u8"),
            result.items.map { it.url })
    }

    @Test fun emptyPlaylistIsVerifiedButHtmlErrorPageIsNot() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://example.org/list.m3u"] = "text/plain" to "#EXTM3U\n"
        fake.bodies["https://example.org/unavailable.m3u"] = "text/html" to "<html>Unavailable</html>"
        val repository = SourceRepository(fake)
        val empty = repository.discover("https://example.org/list.m3u")
        assertTrue(empty.items.isEmpty())
        assertTrue(empty.emptyVerified)
        val html = repository.discover("https://example.org/unavailable.m3u")
        assertTrue(html.items.isEmpty())
        assertFalse(html.emptyVerified)
    }

    @Test fun emptyJsonIsVerifiedOnlyForRecognizedValidConfiguration() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://example.org/empty.json"] = "application/json" to """{"lives":[]}"""
        fake.bodies["https://example.org/unknown.json"] = "application/json" to """{"other":[]}"""
        fake.bodies["https://example.org/bad.json"] = "application/json" to """{"lives":[{}]}"""
        val repository = SourceRepository(fake)
        assertTrue(repository.discover("https://example.org/empty.json").emptyVerified)
        val unknown = repository.discover("https://example.org/unknown.json")
        assertTrue(unknown.partial)
        assertFalse(unknown.emptyVerified)
        val bad = repository.discover("https://example.org/bad.json")
        assertTrue(bad.partial)
        assertFalse(bad.emptyVerified)
    }

    @Test fun jsonConfigFollowsNestedConfigsAndLiveListsWithoutTreatingOtherFieldsAsSources() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://example.org/root.json"] = "application/json" to
            """{"urls":[{"url":"nested.json"}],"lives":[{"url":"channels.txt"}],"sites":[{"api":"https://example.org/not-video.mp4"}]}"""
        fake.bodies["https://example.org/nested.json"] = "application/json" to
            """{"urls":[{"url":"root.json"}],"lives":[{"name":"Movie","url":"https://example.org/video.mp4"},{"url":"http://example.org/unsupported.m3u"}]}"""
        fake.bodies["https://example.org/channels.txt"] = "text/plain" to "News,https://example.org/news.m3u8"
        val updates = mutableListOf<List<String>>()
        val result = SourceRepository(fake).discover("https://example.org/root.json") { updates.add(it.items.map { item -> item.url }) }
        assertEquals(listOf("https://example.org/video.mp4", "https://example.org/news.m3u8"), result.items.map { it.url })
        assertEquals("Movie", result.items.first().name)
        assertEquals("News", result.items.last().name)
        assertTrue(result.partial)
        assertTrue(updates.any { it == listOf("https://example.org/video.mp4") })
    }

    @Test fun readmeFilenameDiscoversJsonWhenRepositoryApiFails() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/README.md"] = "text/plain" to "1. config.json"
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/config.json"] = "application/json" to
            """{"lives":[{"url":"https://example.org/channels.m3u"}]}"""
        fake.bodies["https://example.org/channels.m3u"] = "text/plain" to
            "#EXTM3U\n#EXTINF:-1,News\nhttps://example.org/live.m3u8"
        fake.repositoryFailure = IOException("API unavailable")
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(listOf("https://example.org/live.m3u8"), result.items.map { it.url })
        assertTrue(result.partial)
    }

    @Test fun malformedOrOversizedDirectJsonIsNotReturnedAsMedia() = runBlocking {
        val fake = FakeService()
        val url = "https://example.org/config.json"
        val repo = SourceRepository(fake)
        fake.bodies[url] = "application/json" to "{broken"
        try { repo.discover(url); fail("invalid JSON must fail") } catch (_: IOException) { }
        fake.bodies[url] = "application/json" to ("{\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}")
        val result = repo.discover(url)
        assertTrue(result.items.isEmpty())
        assertTrue(result.partial)
    }

    @Test fun repositoryTreeAndDirectMarkdownFollowJsonReferences() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/README.md"] = "text/plain" to "no sources"
        fake.treeResult = GithubTreeDto(false, listOf(GithubTreeEntryDto("config.json", "blob")))
        fake.bodies["https://raw.githubusercontent.com/example/repo/main/config.json"] = "application/json" to
            """{"lives":[{"url":"https://example.org/live.mp4"}]}"""
        fake.bodies["https://example.org/readme.md"] = "text/plain" to "[config](https://example.org/config.json)"
        fake.bodies["https://example.org/config.json"] = "application/json" to
            """{"lives":[{"url":"https://example.org/other.mp4"}]}"""
        val repo = SourceRepository(fake)
        assertEquals("https://example.org/live.mp4", repo.discover("https://github.com/example/repo").items.single().url)
        assertEquals("https://example.org/other.mp4", repo.discover("https://example.org/readme.md").items.single().url)
    }

    @Test fun jsonConfigLimitKeepsFoundSourcesAndMarksIncomplete() = runBlocking {
        val fake = FakeService()
        val refs = (0..16).joinToString(",") { "{\"url\":\"$it.json\"}" }
        fake.bodies["https://example.org/root.json"] = "application/json" to "{\"urls\":[$refs]}"
        for (index in 0..16) fake.bodies["https://example.org/$index.json"] = "application/json" to
            """{"lives":[{"url":"https://example.org/$index.mp4"}]}"""
        val result = SourceRepository(fake).discover("https://example.org/root.json")
        assertEquals(15, result.items.size)
        assertTrue(result.partial)
    }
    @Test fun playlistKeepsMoreThanHundredAndLimitsAtOneThousand() = runBlocking {
        val fake = FakeService()
        val repo = SourceRepository(fake)
        val longPath = "x".repeat(120)
        fun playlist(count: Int) = "#EXTM3U\n" + (1..count).joinToString("\n") {
            "#EXTINF:-1,Channel $it\nhttps://tv.example/$longPath/$it.m3u8"
        }
        fake.bodies["https://example.org/list"] = "text/plain" to playlist(105)
        val small = repo.discover("https://example.org/list")
        assertEquals(105, small.items.size)
        assertFalse(small.partial)
        fake.bodies["https://example.org/list"] = "text/plain" to playlist(1000)
        val exact = repo.discover("https://example.org/list")
        assertEquals(1000, exact.items.size)
        assertFalse(exact.partial)
        fake.bodies["https://example.org/list"] = "text/plain" to playlist(1005)
        val overflow = repo.discover("https://example.org/list")
        assertEquals(1000, overflow.items.size)
        assertEquals("Channel 1", overflow.items.first().name)
        assertEquals("Channel 1000", overflow.items.last().name)
        assertTrue(overflow.partial)
    }

    @Test fun jsonPlaylistPublishesFirstItemThenBatchesUpToTheLimit() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://example.org/config.json"] = "application/json" to
            """{"lives":[{"url":"https://example.org/channels.m3u"}]}"""
        fake.bodies["https://example.org/channels.m3u"] = "text/plain" to
            ("#EXTM3U\n" + (1..1005).joinToString("\n") { "#EXTINF:-1,Channel $it\nhttps://tv.example/$it.m3u8" })
        val sizes = mutableListOf<Int>()
        val result = SourceRepository(fake).discover("https://example.org/config.json") { sizes.add(it.items.size) }
        assertEquals(1, sizes.first())
        assertEquals(1000, result.items.size)
        assertEquals("Channel 1000", result.items.last().name)
        assertTrue(result.partial)
        assertTrue(sizes.size <= 25)
    }
    @Test fun repositoryCombinesMediaAndReadmeAttachmentsAndReportsTruncation() = runBlocking {
        val fake = FakeService()
        fake.treeResult = GithubTreeDto(true, listOf(GithubTreeEntryDto("app/utils.ts", "blob"), GithubTreeEntryDto("docs/demo.mp4", "blob"), GithubTreeEntryDto("README.md", "blob")))
        fake.bodies["https://raw.githubusercontent.com/example/repo/main/app/utils.ts"] = "text/plain" to "export const helper = 1"
        fake.bodies["https://raw.githubusercontent.com/example/repo/main/README.md"] = "text/plain" to
            "[demo](https://github.com/user-attachments/assets/abc)"
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(2, result.items.size)
        assertTrue(result.partial)
        assertEquals("https://raw.githubusercontent.com/example/repo/main/docs/demo.mp4", result.items.first().url)
    }
    @Test fun redirectsAreResolvedAndLoopsAreRejected() = runBlocking {
        val fake = FakeService()
        fake.redirects["https://example.org/a"] = "/b"
        fake.bodies["https://example.org/b"] = "video/mp4" to "binary"
        val repo = SourceRepository(fake)
        assertEquals("https://example.org/b", repo.playbackUrl("https://example.org/a"))
        fake.redirects["https://example.org/b"] = "/a"
        try { repo.playbackUrl("https://example.org/a"); fail("loop should fail") } catch (_: IOException) { }
    }

    @Test fun rawHttpFailuresKeepStatusForDiscoveryAndPlayback() = runBlocking {
        val fake = FakeService()
        val url = "https://example.org/source"
        val repo = SourceRepository(fake)
        for (status in listOf(403, 404)) {
            fake.statuses[url] = status
            for (action in listOf<suspend () -> Unit>({ repo.discover(url); Unit }, { repo.playbackUrl(url); Unit })) {
                try { action(); fail("HTTP failure expected") }
                catch (error: SourceHttpFailure) { assertTrue(status == error.sourceHttpStatus()) }
            }
        }
        assertNull(IOException("socket failure").sourceHttpStatus())
    }

    @Test fun forbiddenInputAndGithubApiBothReport403WithoutTreating404AsForbidden() = runBlocking {
        for (status in listOf(403, 404)) {
            for (github in listOf(false, true)) {
                val fake = FakeService()
                val url = if (github) "https://github.com/example/repo" else "https://example.org/source"
                if (github) fake.repositoryFailure = retrofit2.HttpException(Response.error<GithubRepositoryDto>(status, "".toResponseBody()))
                else fake.statuses[url] = status
                val released = CompletableDeferred<Unit>()
                val session = SourceDiscoverySession(SourceRepository(fake), url, { released.complete(Unit) }, Dispatchers.Default)
                val done = withTimeout(3_000) { session.state.first { !it.running } }
                assertEquals(if (status == 403) "HTTP_403" else "FAILED", done.error)
                session.close()
                withTimeout(3_000) { released.await() }
            }
        }
    }

    @Test fun forbiddenOptionalReadmeStillKeepsMediaAsPartialResult() = runBlocking {
        val fake = FakeService()
        fake.treeResult = GithubTreeDto(false, listOf(
            GithubTreeEntryDto("demo.mp4", "blob"), GithubTreeEntryDto("README.md", "blob")))
        fake.statuses["https://raw.githubusercontent.com/example/repo/main/README.md"] = 403
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(1, result.items.size)
        assertTrue(result.partial)
    }
    @Test fun webAndCloudPagesAreNotPresentedAsMedia() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://example.org/page"] = "text/html" to "<html><body>Website</body></html>"
        val repo = SourceRepository(fake)
        val page = repo.discover("https://example.org/page")
        assertTrue(page.items.isEmpty())
        assertTrue(ReferenceKind.WEBSITE in page.references)
        val share = repo.discover("https://pan.quark.cn/s/example")
        assertTrue(share.items.isEmpty())
        assertTrue(ReferenceKind.CLOUD_SHARE in share.references)
    }
    @Test fun unreadMediaResponseClosesOffTheCallerThread() = runBlocking {
        val closedOn = AtomicReference<String>()
        val source = object : ForwardingSource(Buffer().writeUtf8("media")) {
            override fun close() { closedOn.set(Thread.currentThread().name); super.close() }
        }.buffer()
        val fake = FakeService()
        fake.responseBodies["https://example.org/video"] = object : ResponseBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = 5L
            override fun source(): BufferedSource = source
        }
        val caller = Thread.currentThread().name
        assertEquals(1, SourceRepository(fake).discover("https://example.org/video").items.size)
        assertNotNull(closedOn.get())
        assertNotEquals(caller, closedOn.get())
    }

    @Test fun repositoryPublishesMediaBeforeSlowReadmeAndAppendsUniqueResults() = runBlocking {
        val fake = FakeService()
        fake.treeResult = GithubTreeDto(false, listOf(
            GithubTreeEntryDto("demo.mp4", "blob"), GithubTreeEntryDto("README.md", "blob")))
        val readme = "https://raw.githubusercontent.com/example/repo/main/README.md"
        val gate = CompletableDeferred<Unit>()
        fake.beforeContent = { if (it == readme) gate.await() }
        fake.bodies[readme] = "text/plain" to "[same](demo.mp4) [next](https://example.org/next.mkv)"
        val first = CompletableDeferred<List<String>>()
        val updates = mutableListOf<List<String>>()
        val task = launch(Dispatchers.Default) {
            SourceRepository(fake).discover("https://github.com/example/repo") { result ->
                val urls = result.items.map { it.url }
                synchronized(updates) { updates.add(urls) }
                if (urls.isNotEmpty()) first.complete(urls)
            }
        }
        val initial = withTimeout(3_000) { first.await() }
        assertEquals(listOf("https://raw.githubusercontent.com/example/repo/main/demo.mp4"), initial)
        assertFalse(task.isCompleted)
        gate.complete(Unit)
        withTimeout(3_000) { task.join() }
        val final = synchronized(updates) { updates.last() }
        assertEquals(2, final.size)
        assertEquals(initial.first(), final.first())
        assertEquals("https://example.org/next.mkv", final.last())
    }

    @Test fun sessionRetainsFirstItemWhenLaterRequestFailsAndCanClose() = runBlocking {
        val fake = FakeService()
        fake.treeResult = GithubTreeDto(false, listOf(
            GithubTreeEntryDto("demo.mp4", "blob"), GithubTreeEntryDto("README.md", "blob")))
        val released = CompletableDeferred<Unit>()
        val session = SourceDiscoverySession(SourceRepository(fake), "https://github.com/example/repo", { released.complete(Unit) }, Dispatchers.Default)
        val done = withTimeout(3_000) { session.state.first { !it.running } }
        assertEquals(1, done.result?.items?.size)
        assertTrue(done.result?.partial == true)
        assertNull(done.error)
        session.close()
        session.close()
        withTimeout(3_000) { released.await() }
    }

    @Test fun closingSessionCancelsPendingDiscoveryAndReleasesOnce() = runBlocking {
        val fake = FakeService()
        fake.treeResult = GithubTreeDto(false, listOf(
            GithubTreeEntryDto("demo.mp4", "blob"), GithubTreeEntryDto("README.md", "blob")))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fake.beforeContent = { url ->
            if (url == "https://raw.githubusercontent.com/example/repo/main/README.md") {
                entered.complete(Unit)
                try { gate.await() } finally { stopped.complete(Unit) }
            }
        }
        val releases = java.util.concurrent.atomic.AtomicInteger()
        val session = SourceDiscoverySession(SourceRepository(fake), "https://github.com/example/repo", { releases.incrementAndGet() }, Dispatchers.Default)
        withTimeout(3_000) { entered.await() }
        assertEquals(1, session.state.value.result?.items?.size)
        session.close()
        session.close()
        withTimeout(3_000) { stopped.await() }
        withTimeout(3_000) { while (releases.get() == 0) kotlinx.coroutines.yield() }
        assertEquals(1, releases.get())
        assertFalse(session.state.value.running)
    }

    @Test fun readmeCandidateIsPublishedBeforeWaitingForRepositoryApi() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/README.md"] =
            "text/plain" to "[video](https://example.org/first.mp4)"
        val apiGate = CompletableDeferred<Unit>()
        fake.beforeRepository = { apiGate.await() }
        val first = CompletableDeferred<List<String>>()
        val task = launch(Dispatchers.Default) {
            SourceRepository(fake).discover("https://github.com/example/repo") {
                if (it.items.isNotEmpty()) first.complete(it.items.map { item -> item.url })
            }
        }
        assertEquals(listOf("https://example.org/first.mp4"), withTimeout(3_000) { first.await() })
        assertFalse(task.isCompleted)
        apiGate.complete(Unit)
        withTimeout(3_000) { task.join() }
    }

    @Test fun readmeHeadAndDefaultBranchStillDeduplicate() = runBlocking {
        val fake = FakeService()
        fake.bodies["https://raw.githubusercontent.com/example/repo/HEAD/README.md"] =
            "text/plain" to "[video](demo.mp4)"
        fake.treeResult = GithubTreeDto(false, listOf(GithubTreeEntryDto("demo.mp4", "blob")))
        val result = SourceRepository(fake).discover("https://github.com/example/repo")
        assertEquals(listOf("https://raw.githubusercontent.com/example/repo/HEAD/demo.mp4"), result.items.map { it.url })
    }

    @Test fun rateLimitedApiFallsBackToRenderedReadmeAndKeepsTheReason() = runBlocking {
        val fake = FakeService()
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.github.com/repos/example/repo").build())
            .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
            .header("X-RateLimit-Remaining", "0").build()
        fake.repositoryFailure = retrofit2.HttpException(Response.error<GithubRepositoryDto>("".toResponseBody(), raw))
        fake.bodies["https://github.com/example/repo"] = "text/html" to
            "<a href='https://example.org/chrome.mp4'>chrome</a>" +
            "<article class='markdown-body'><a href='https://example.org/video.mp4'>video</a></article>"
        val session = SourceDiscoverySession(SourceRepository(fake), "https://github.com/example/repo", {}, Dispatchers.Default)
        val result = withTimeout(3_000) { session.state.first { !it.running } }
        assertEquals(listOf("https://example.org/video.mp4"), result.result?.items?.map { it.url })
        assertEquals("RATE_LIMIT", result.error)
        assertTrue(result.result?.partial == true)
        session.close()
    }
}
