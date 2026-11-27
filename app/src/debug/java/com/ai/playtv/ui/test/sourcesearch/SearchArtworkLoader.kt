package com.ai.playtv.ui.test.sourcesearch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.ImageView
import com.ai.playtv.SourcePreviewApplication
import com.ai.playtv.base.diagnostics.DiagnosticEvent
import com.ai.playtv.base.diagnostics.DiagnosticLevel
import com.ai.playtv.base.diagnostics.DiagnosticLog
import com.ai.playtv.di.NetworkDependencies
import com.ai.playtv.model.contract.sourcesearch.SearchRow
import com.ai.playtv.model.network.service.artwork.ArtworkHttpService
import com.ai.playtv.model.repository.artwork.ArtworkRepository
import com.ai.playtv.model.repository.artwork.SearchArtworkRepository
import com.ai.playtv.model.storage.artwork.AndroidWebpEncoder
import com.ai.playtv.model.storage.artwork.ArtworkImageEncoder
import com.ai.playtv.support.network.NetworkConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException

/** Search-screen-only binding; all views and jobs are released when the screen is destroyed. */
class SearchArtworkLoader(application: SourcePreviewApplication) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permits = Semaphore(2)
    private val network = NetworkDependencies(NetworkConfig("https://iptv-org.github.io/".toHttpUrl()))
    private val webp = AndroidWebpEncoder(512, 512, 1920, 1080, 80)
    private val encoder = ArtworkImageEncoder { input, output, slot ->
        val checked = decode(input, 512) ?: throw IOException("Artwork decoding failed")
        checked.recycle()
        webp.encode(input, output, slot)
    }
    private val repository = try {
        SearchArtworkRepository(
            { historyId, itemId -> application.sourceHistory.find(historyId)?.items?.firstOrNull { it.id == itemId } },
            ArtworkRepository(network.service(ArtworkHttpService::class.java), cacheChannelIndexes = true), application.sourceArtwork, encoder, MAX_IMAGE_BYTES)
    } catch (failure: Throwable) {
        network.close()
        throw failure
    }
    private val failures = object : LinkedHashMap<Triple<String, String, String?>, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String?>, Long>?) = size > 256
    }
    private var reportedNoResult = false

    /** Caller cancels the previous job and clears the view before every bind and on recycle. */
    fun bind(view: ImageView, row: SearchRow): Job = scope.launch {
        val key = Triple(row.historyId, row.itemId, row.artworkRevision)
        val failedAt = failures[key]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_DELAY_MS) return@launch
        try {
            permits.withPermit {
                val file = repository.small(row.historyId, row.itemId)
                val bitmap = file?.let { withContext(Dispatchers.IO) { decode(it, 512) } }
                if (bitmap == null) {
                    failures[key] = SystemClock.elapsedRealtime()
                    if (file != null) {
                        DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("search_artwork", "decode", "failed"))
                    } else if (!reportedNoResult) {
                        reportedNoResult = true
                        DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("search_artwork", "lookup", "no_result"))
                    }
                }
                else { failures.remove(key); view.setImageBitmap(bitmap) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures[key] = SystemClock.elapsedRealtime()
            DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("search_artwork", "load", "failed", errorType = error.javaClass.simpleName))
        }
    }

    override fun close() { scope.cancel(); network.close() }

    private companion object {
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        const val RETRY_DELAY_MS = 60_000L
        fun decode(file: File, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
