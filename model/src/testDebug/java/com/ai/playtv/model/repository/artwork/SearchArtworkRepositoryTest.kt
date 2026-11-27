package com.ai.playtv.model.repository.artwork

import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.network.service.artwork.ArtworkHttpService
import com.ai.playtv.model.storage.artwork.ArtworkFileStore
import com.ai.playtv.model.storage.artwork.ArtworkImageEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.util.Base64

class SearchArtworkRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/V1sAAAAASUVORK5CYII=")

    @Test fun savesOnceThenReusesOwnedFileWithoutNetwork() = runBlocking {
        var calls = 0
        val service = object : ArtworkHttpService {
            override suspend fun get(url: String, authorization: String?): Response<ResponseBody> {
                calls++
                return Response.success(bytes.toResponseBody())
            }
        }
        val item = ImportedSource("one", "name", "https://example.org/play", SourceKind.UNKNOWN, logoUrl = "https://example.org/logo.png")
        val store = ArtworkFileStore(temporary.newFolder(), 1024, 4096)
        val repo = SearchArtworkRepository({ _, _ -> item }, ArtworkRepository(service), store, ArtworkImageEncoder { _, _, _ -> false }, 1024)
        val files = listOf(async { repo.small("source", "one") }, async { repo.small("source", "one") }).awaitAll()
        assertEquals(1, calls)
        assertEquals(files[0], files[1])
        assertArrayEquals(bytes, files[0]!!.readBytes())
        assertNotNull(repo.small("source", "one"))
        assertEquals(1, calls)
    }

    @Test fun missingMetadataAndChangedItemDoNotSaveOrGuessAType() = runBlocking {
        var item: ImportedSource? = ImportedSource("one", "name", "https://example.org/play", SourceKind.UNKNOWN)
        var calls = 0
        val service = object : ArtworkHttpService {
            override suspend fun get(url: String, authorization: String?): Response<ResponseBody> {
                calls++
                item = null
                return Response.success(bytes.toResponseBody())
            }
        }
        val store = ArtworkFileStore(temporary.newFolder(), 1024, 4096)
        val repo = SearchArtworkRepository({ _, _ -> item }, ArtworkRepository(service), store, ArtworkImageEncoder { _, _, _ -> false }, 1024)
        assertNull(repo.small("source", "one"))
        assertEquals(0, calls)
        item = item!!.copy(logoUrl = "https://example.org/logo.png")
        assertNull(repo.small("source", "one"))
        assertEquals(1, calls)
        assertNull(store.find("source", "one", com.ai.playtv.model.storage.artwork.ArtworkSlot.SMALL))
    }
}
