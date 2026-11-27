package com.ai.playtv.model.repository.artwork

import com.ai.playtv.model.bean.domain.artwork.*
import com.ai.playtv.model.network.service.artwork.ArtworkHttpService
import com.ai.playtv.model.storage.artwork.ArtworkFileStore
import com.ai.playtv.model.storage.artwork.ArtworkSlot
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in external integration check; normal unit tests never require an Internet connection. */
class ArtworkLiveTest {
    @Test fun downloadsChannelLogo() = runBlocking {
        checkImage(ArtworkQuery("CCTV1", ArtworkMediaType.CHANNEL, channelId = "CCTV1.cn"), ArtworkSlot.SMALL)
    }

    @Test fun downloadsTvPoster() = runBlocking {
        checkImage(ArtworkQuery("Under the Dome", ArtworkMediaType.TV_SHOW), ArtworkSlot.SMALL)
    }

    @Test fun downloadsTvBackground() = runBlocking {
        checkImage(ArtworkQuery("Under the Dome", ArtworkMediaType.TV_SHOW), ArtworkSlot.BACKGROUND)
    }

    private suspend fun checkImage(query: ArtworkQuery, slot: ArtworkSlot) {
        assumeTrue(System.getenv("PLAYTV_ARTWORK_LIVE_TEST") == "1")
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        try {
            val service = Retrofit.Builder().baseUrl("https://api.tvmaze.com/").client(client).build().create(ArtworkHttpService::class.java)
            val repository = ArtworkRepository(service)
            val store = ArtworkFileStore(File("build/verification/artwork-live"), 16L * 1024 * 1024, 64L * 1024 * 1024)
            val found = repository.lookup(query) as ArtworkLookupResult.Found
            val url = if (slot == ArtworkSlot.SMALL) found.candidate.smallUrl else found.candidate.backgroundUrl
            assertNotNull("Requested image must be present", url)
            val ticket = store.ticket("live-verification", "${query.type}-$slot")
            val file = repository.readImage(url!!, 16L * 1024 * 1024) { store.save(ticket, slot, it) }
            assertTrue(file.length() > 0)
            println("Artwork verification ${query.type} $slot: ${file.length()} bytes; ${file.absolutePath}")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
