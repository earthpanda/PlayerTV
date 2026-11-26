package com.ai.playtv.model.contract.tvplayback

import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import com.ai.playtv.model.bean.domain.source.ImportedSource

/** Debug sample sources. Playlist entries are not an IPTV catalog. */
enum class TvPreviewSource {
    BUNNY, OCEANS, FLOWER, SINTEL, GITHUB_BUNNY, GITHUB_ECHO, GITHUB_FLOWER,
    GITHUB_WEBM, GITHUB_OGV, GITHUB_MKV, GITHUB_FLV, SAMPLE_AVI, SAMPLE_MOV, SAMPLE_M4V, SAMPLE_TS,
    GITHUB_MP3, GITHUB_M4A,
    HLS, DASH, CGTN, CGTN_DOCUMENTARY, FRANCE24_EN,
}

data class TvPlaybackState(
    val phase: String = "INITIALIZING", val positionMs: Long = 0,
    val durationMs: Long? = null, val seekable: Boolean = false, val bufferedPercent: Int = 0,
    val error: String? = null, val source: TvPreviewSource = TvPreviewSource.BUNNY,
    val activeId: String = TvPreviewSource.BUNNY.name, val activeName: String? = null,
    val imported: List<ImportedSource> = emptyList(), val importPartial: Boolean = false,
    val sourceResolving: Boolean = false,
    val discoveryRunning: Boolean = false, val discoveryFailed: Boolean = false,
) : UiState
sealed interface TvPlaybackEvent : UiEvent {
    data object Toggle : TvPlaybackEvent
    data class Seek(val deltaMs: Long) : TvPlaybackEvent
    data class SeekTo(val positionMs: Long) : TvPlaybackEvent
    data class SelectSource(val source: TvPreviewSource) : TvPlaybackEvent
    data class SelectImported(val source: ImportedSource) : TvPlaybackEvent
    data object Background : TvPlaybackEvent
}
sealed interface TvPlaybackEffect : UiEffect {
    data object ServerForbidden : TvPlaybackEffect
}
