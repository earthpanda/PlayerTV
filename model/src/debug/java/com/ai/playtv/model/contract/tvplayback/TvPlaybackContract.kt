package com.ai.playtv.model.contract.tvplayback

import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import com.ai.playtv.model.bean.domain.source.ImportedSource

data class TvPlaybackState(
    val phase: String = "INITIALIZING", val positionMs: Long = 0,
    val durationMs: Long? = null, val seekable: Boolean = false, val bufferedPercent: Int = 0,
    val error: String? = null, val activeId: String? = null, val activeName: String? = null,
    val imported: List<ImportedSource> = emptyList(), val importPartial: Boolean = false,
    val sourceResolving: Boolean = false,
    val audioWithoutVideo: Boolean = false,
    val discoveryRunning: Boolean = false, val discoveryFailed: Boolean = false,
    val discoveryRateLimited: Boolean = false, val historyFailed: Boolean = false,
) : UiState
sealed interface TvPlaybackEvent : UiEvent {
    data object Toggle : TvPlaybackEvent
    data class SetPlaying(val playing: Boolean) : TvPlaybackEvent
    data class Seek(val deltaMs: Long) : TvPlaybackEvent
    data class SeekTo(val positionMs: Long) : TvPlaybackEvent
    data class SelectImported(val source: ImportedSource) : TvPlaybackEvent
    data object Background : TvPlaybackEvent
}
sealed interface TvPlaybackEffect : UiEffect {
    data object ServerForbidden : TvPlaybackEffect
}
