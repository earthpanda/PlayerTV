package com.ai.playtv.model.repository.artwork

import com.ai.playtv.model.bean.domain.artwork.ArtworkLookupResult
import com.ai.playtv.model.bean.domain.artwork.ArtworkMediaType
import com.ai.playtv.model.bean.domain.artwork.ArtworkQuery
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.storage.artwork.ArtworkFileStore
import com.ai.playtv.model.storage.artwork.ArtworkImageEncoder
import com.ai.playtv.model.storage.artwork.ArtworkSlot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Debug search: one owner serializes cache checks and downloads for this file store. */
class SearchArtworkRepository(
    private val findItem: suspend (String, String) -> ImportedSource?,
    private val artwork: ArtworkRepository,
    private val files: ArtworkFileStore,
    private val encoder: ArtworkImageEncoder,
    private val maxImageBytes: Long,
) {
    private val mutex = Mutex()

    suspend fun small(historyId: String, itemId: String): File? {
        val initial = findItem(historyId, itemId) ?: return null
        files.find(historyId, itemId, ArtworkSlot.SMALL)?.let { return it }
        if (initial.logoUrl == null && initial.channelId == null) return null
        return mutex.withLock {
            val item = findItem(historyId, itemId) ?: return@withLock null
            files.find(historyId, itemId, ArtworkSlot.SMALL)?.let { return@withLock it }
            if (item.logoUrl == null && item.channelId == null) return@withLock null
            val ticket = files.ticket(historyId, itemId)
            val lookupTitle = item.name.takeIf { it.length <= 256 }.orEmpty()
            val match = artwork.lookup(ArtworkQuery(lookupTitle, ArtworkMediaType.CHANNEL,
                channelId = item.channelId, playlistLogoUrl = item.logoUrl))
            val url = (match as? ArtworkLookupResult.Found)?.candidate?.smallUrl ?: return@withLock null
            val current = findItem(historyId, itemId)
            if (current != item) return@withLock null
            artwork.readImage(url, maxImageBytes) { input ->
                if (findItem(historyId, itemId) != item) return@readImage null
                files.save(ticket, ArtworkSlot.SMALL, input, encoder)
            }
        }
    }
}
