package com.ai.playtv

import com.ai.playtv.di.SourceDiscoveryOwner
import com.ai.playtv.base.storage.AppDirectories
import com.ai.playtv.model.storage.source.SourceHistoryStore
import com.ai.playtv.model.storage.artwork.ArtworkFileStore
import java.io.File

/** Debug-only composition root; sessions are started explicitly by the entry screen. */
class SourcePreviewApplication : AppApplication() {
    val sourceHistory: SourceHistoryStore by lazy {
        val directories = AppDirectories(this)
        SourceHistoryStore { directories.noBackup("source_history") }
    }
    val discoveryOwner: SourceDiscoveryOwner by lazy { SourceDiscoveryOwner(sourceHistory) }
    val sourceArtwork: ArtworkFileStore by lazy {
        ArtworkFileStore(File(noBackupFilesDir, "source_artwork"), 5L * 1024 * 1024, 128L * 1024 * 1024)
    }
}
