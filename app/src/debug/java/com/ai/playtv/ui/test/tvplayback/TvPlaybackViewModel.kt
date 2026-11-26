package com.ai.playtv.ui.test.tvplayback

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.lifecycle.viewModelScope
import com.ai.playback.AndroidPlayer
import com.ai.playback.CommandResult
import com.ai.playback.PlaybackOptions
import com.ai.playback.PlaybackPhase
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.tvplayback.TvPlaybackEffect
import com.ai.playtv.model.contract.tvplayback.TvPlaybackEvent
import com.ai.playtv.model.contract.tvplayback.TvPlaybackState
import com.ai.playtv.model.contract.tvplayback.TvPreviewSource
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.di.NetworkDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Debug-only session; all player and surface commands are issued on main. */
class TvPlaybackViewModel(private val appContext: Context, private val repository: SourceRepository,
    private val network: NetworkDependencies, private val initialSources: List<ImportedSource>, partial: Boolean) :
    BaseMviViewModel<TvPlaybackState, TvPlaybackEvent, TvPlaybackEffect>(TvPlaybackState(
        imported = initialSources, importPartial = partial,
        activeId = initialSources.firstOrNull { it.url != KNOWN_CRASHING_WEBM }?.id ?: TvPreviewSource.BUNNY.name,
        activeName = initialSources.firstOrNull { it.url != KNOWN_CRASHING_WEBM }?.name,
    )) {
    companion object { const val KNOWN_CRASHING_WEBM = "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.webm" }
    private var player: AndroidPlayer? = null
    private var surface: Surface? = null
    private var background = false
    private var lastPhase: PlaybackPhase? = null
    private var currentSource = TvPreviewSource.BUNNY
    private var sourceGeneration = 0L
    private var importedSource: ImportedSource? = null
    private var requestedSource: ImportedSource? = null
    private var selectionJob: Job? = null

    private fun sourceUrl(source: TvPreviewSource) = when (source) {
        TvPreviewSource.BUNNY -> "https://media.w3.org/2010/05/bunny/trailer.mp4"
        TvPreviewSource.OCEANS -> "https://vjs.zencdn.net/v/oceans.mp4"
        TvPreviewSource.FLOWER -> "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"
        TvPreviewSource.SINTEL -> "https://media.w3.org/2010/05/sintel/trailer.mp4"
        TvPreviewSource.GITHUB_BUNNY -> "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4"
        TvPreviewSource.GITHUB_ECHO -> "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/echo-hereweare.mp4"
        TvPreviewSource.GITHUB_FLOWER -> "https://raw.githubusercontent.com/mdn/interactive-examples/main/live-examples/media/cc0-videos/flower.mp4"
        TvPreviewSource.GITHUB_WEBM -> "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.webm"
        TvPreviewSource.GITHUB_OGV -> "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/echo-hereweare.ogv"
        TvPreviewSource.GITHUB_MKV -> "https://raw.githubusercontent.com/matroska-org/matroska-test-files/master/test_files/test1.mkv"
        TvPreviewSource.GITHUB_FLV -> "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/guqin.flv"
        TvPreviewSource.SAMPLE_AVI -> "https://filesamples.com/samples/video/avi/sample_640x360.avi"
        TvPreviewSource.SAMPLE_MOV -> "https://filesamples.com/samples/video/mov/sample_640x360.mov"
        TvPreviewSource.SAMPLE_M4V -> "https://filesamples.com/samples/video/m4v/sample_640x360.m4v"
        TvPreviewSource.SAMPLE_TS -> "https://filesamples.com/samples/video/ts/sample_640x360.ts"
        TvPreviewSource.GITHUB_MP3 -> "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/AirReview-Landmarks-02-ChasingCorporate.mp3"
        TvPreviewSource.GITHUB_M4A -> "https://raw.githubusercontent.com/rafaelreis-hotmart/Audio-Sample-files/master/sample.m4a"
        TvPreviewSource.HLS -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        TvPreviewSource.DASH -> "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd"
        TvPreviewSource.CGTN -> "https://english-livebkali.cgtn.com/live/encgtn.m3u8"
        TvPreviewSource.CGTN_DOCUMENTARY -> "https://hlspackager.akamaized.net/live/DB/CGTN_DOCUMENTARY/HLS/CGTN_DOCUMENTARY.m3u8"
        TvPreviewSource.FRANCE24_EN -> "https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_900.m3u8"
    }

    init {
        viewModelScope.launch {
            try {
                val created = AndroidPlayer.create(appContext)
                player = created
                surface?.let { report(created.setSurface(it)) }
                if (initialSources.isEmpty()) loadSource(created, currentSource)
                else initialSources.firstOrNull { it.url != KNOWN_CRASHING_WEBM }?.let { selectImported(it) }
                    ?: setState { copy(phase = "FAILED", error = "SOURCE_UNAVAILABLE") }
                created.state.collect { snapshot ->
                    if (snapshot.generation != sourceGeneration) return@collect
                    if (lastPhase != snapshot.phase) {
                        Log.i("PlayTvPreview", "phase=${snapshot.phase} failure=${snapshot.failure?.code}")
                        lastPhase = snapshot.phase
                    }
                    setState { copy(phase = snapshot.phase.name, positionMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs, seekable = snapshot.seekable, bufferedPercent = snapshot.bufferedPercent,
                        error = snapshot.failure?.code?.name) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("PlayTvPreview", "initialization failed", error)
                setState { copy(phase = "FAILED", error = "INITIALIZATION_FAILED") }
            }
        }
    }

    fun attachSurface(value: Surface?) {
        surface = value
        player?.let { report(it.setSurface(value)) }
    }

    override fun onEvent(event: TvPlaybackEvent) {
        if (event == TvPlaybackEvent.Background) background = true
        val activePlayer = player ?: run {
            if (event !is TvPlaybackEvent.Background) setState { copy(error = "PLAYER_NOT_READY") }
            return
        }
        when (event) {
            TvPlaybackEvent.Toggle -> when (activePlayer.state.value.phase) {
                PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.PREPARING -> report(activePlayer.pause())
                PlaybackPhase.PAUSED -> report(activePlayer.play())
                PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.FAILED ->
                    (requestedSource ?: importedSource)?.let { selectImported(it) } ?: loadSource(activePlayer, currentSource)
                else -> Unit
            }
            is TvPlaybackEvent.Seek -> {
                val snapshot = activePlayer.state.value
                if (snapshot.seekable && snapshot.durationMs != null) {
                    val position = (snapshot.positionMs + event.deltaMs).coerceIn(0, snapshot.durationMs!!)
                    report(activePlayer.seekTo(position))
                }
            }
            is TvPlaybackEvent.SeekTo -> {
                val snapshot = activePlayer.state.value
                val duration = snapshot.durationMs
                if (snapshot.seekable && duration != null && duration > 0) {
                    report(activePlayer.seekTo(event.positionMs.coerceIn(0L, duration)))
                }
            }
            is TvPlaybackEvent.SelectSource -> { selectionJob?.cancel(); requestedSource = null; setState { copy(sourceResolving = false) }; loadSource(activePlayer, event.source) }
            is TvPlaybackEvent.SelectImported -> selectImported(event.source)
            TvPlaybackEvent.Background -> {
                background = true
                if (activePlayer.state.value.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.PREPARING)) {
                    report(activePlayer.pause())
                }
            }
        }
    }
    fun foreground() { background = false }
    private fun selectImported(source: ImportedSource) {
        selectionJob?.cancel()
        requestedSource = source
        setState { copy(sourceResolving = true, error = null) }
        selectionJob = viewModelScope.launch {
            try {
                val url = repository.playbackUrl(source.url)
                val activePlayer = player ?: return@launch
                val result = activePlayer.loadHttps(url, PlaybackOptions(playWhenReady = !background))
                if (result is CommandResult.Accepted) {
                    importedSource = source
                    sourceGeneration = activePlayer.state.value.generation
                    setState { copy(activeId = source.id, activeName = source.name, phase = "PREPARING", positionMs = 0, sourceResolving = false,
                        durationMs = null, seekable = false, bufferedPercent = 0, error = null) }
                } else setState { copy(sourceResolving = false) }
                report(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("PlayTvPreview", "source resolution failed: ${error.javaClass.simpleName}")
                setState { copy(sourceResolving = false, error = "SOURCE_RESOLUTION_FAILED") }
            }
        }
    }
    private fun loadSource(activePlayer: AndroidPlayer, source: TvPreviewSource) {
        val result = activePlayer.loadHttps(sourceUrl(source), PlaybackOptions(playWhenReady = !background))
        if (result is CommandResult.Accepted) {
            currentSource = source
            importedSource = null
            sourceGeneration = activePlayer.state.value.generation
            Log.i("PlayTvPreview", "source=$source generation=$sourceGeneration")
            setState { copy(source = source, activeId = source.name, activeName = null, phase = "PREPARING", positionMs = 0, durationMs = null,
                seekable = false, bufferedPercent = 0, error = null) }
        }
        report(result)
    }
    private fun report(result: CommandResult) {
        if (result is CommandResult.Rejected) {
            Log.w("PlayTvPreview", "command rejected: ${result.failure.code}")
            setState { copy(error = result.failure.code.name) }
        }
    }
    override fun onCleared() {
        selectionJob?.cancel()
        network.close()
        surface = null
        player?.close()
        player = null
        super.onCleared()
    }
}
