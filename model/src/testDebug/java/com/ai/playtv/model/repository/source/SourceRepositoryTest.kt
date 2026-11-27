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
    @Test fun playlistReturnsFirstHundredAndMarksPartial() = runBlocking {
        val fake = FakeService()
        val text = "#EXTM3U\n" + (1..105).joinToString("\n") { "#EXTINF:-1,Channel $it\nhttps://tv.example/$it.m3u8" }
        fake.bodies["https://example.org/list"] = "text/plain" to text
        val result = SourceRepository(fake).discover("https://example.org/list")
        assertEquals(100, result.items.size)
        assertEquals("Channel 1", result.items.first().name)
        assertEquals("Channel 100", result.items.last().name)
        assertTrue(result.partial)
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
