package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.bean.domain.source.ReferenceKind
import com.ai.playtv.model.bean.net.source.GithubRepositoryDto
import com.ai.playtv.model.bean.net.source.GithubTreeDto
import com.ai.playtv.model.bean.net.source.GithubTreeEntryDto
import com.ai.playtv.model.network.service.source.GithubSourceService
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
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
        val redirects = mutableMapOf<String, String>()
        override suspend fun repository(owner: String, repo: String) = GithubRepositoryDto("main")
        override suspend fun tree(owner: String, repo: String, ref: String) = treeResult
        override suspend fun commit(owner: String, repo: String, ref: String) = JsonObject()
        override suspend fun content(url: String): Response<ResponseBody> {
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
}
