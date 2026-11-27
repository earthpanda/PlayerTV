package com.ai.playtv

import com.ai.playtv.di.SourceDiscoveryOwner
import com.ai.playtv.base.storage.AppDirectories
import com.ai.playtv.base.diagnostics.DiagnosticEvent
import com.ai.playtv.base.diagnostics.DiagnosticLevel
import com.ai.playtv.base.diagnostics.DiagnosticLog
import com.ai.playtv.model.storage.source.SourceHistoryStore
import com.ai.playtv.model.storage.artwork.ArtworkFileStore
import com.ai.playtv.model.repository.sourcesearch.SourceSearchIndex
import java.io.File

/** Debug-only composition root; sessions are started explicitly by the entry screen. */
class SourcePreviewApplication : AppApplication() {
    private val historyComponents: Pair<SourceHistoryStore, SourceSearchIndex> by lazy {
        val directories = AppDirectories(this)
        val history = SourceHistoryStore { directories.noBackup("source_history") }
        history to SourceSearchIndex(history) { errorType ->
            DiagnosticLog.write(DiagnosticLevel.WARN,
                DiagnosticEvent("source_search_index", "prepare", "failed", errorType = errorType))
        }
    }
    val sourceHistory: SourceHistoryStore get() = historyComponents.first
    val sourceSearchIndex: SourceSearchIndex get() = historyComponents.second
    val discoveryOwner: SourceDiscoveryOwner by lazy { SourceDiscoveryOwner(sourceHistory) }
    val sourceArtwork: ArtworkFileStore by lazy {
        ArtworkFileStore(File(noBackupFilesDir, "source_artwork"), 5L * 1024 * 1024, 128L * 1024 * 1024)
    }
}
