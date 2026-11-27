package com.ai.playtv.ui.test.tvplayback

import android.content.Context
import android.util.Log
import android.view.Surface
import com.ai.playtv.BuildConfig
import androidx.lifecycle.viewModelScope
import com.ai.playback.AndroidPlayer
import com.ai.playback.CommandResult
import com.ai.playback.PlaybackOptions
import com.ai.playback.PlaybackPhase
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.tvplayback.TvPlaybackEffect
import com.ai.playtv.model.contract.tvplayback.TvPlaybackEvent
import com.ai.playtv.model.contract.tvplayback.TvPlaybackState
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.model.repository.source.sourceHttpStatus
import com.ai.playtv.di.NetworkDependencies
import com.ai.playtv.di.SourceDiscoveryOwner
import com.ai.playtv.model.manager.source.SourceDiscoverySession
import com.ai.playtv.model.storage.source.SourceHistoryStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Debug-only session; all player and surface commands are issued on main. */
class TvPlaybackViewModel(private val appContext: Context, private val repository: SourceRepository,
    private val network: NetworkDependencies, private val initialSources: List<ImportedSource>, partial: Boolean,
    private val discoveryOwner: SourceDiscoveryOwner? = null, private val sessionId: String? = null,
    private val session: SourceDiscoverySession? = null, private val history: SourceHistoryStore? = null,
    private val historyId: String? = null, private val initialItemId: String? = null) :
    BaseMviViewModel<TvPlaybackState, TvPlaybackEvent, TvPlaybackEffect>(TvPlaybackState(
        imported = initialSources, importPartial = partial,
        activeId = initialSources.firstOrNull { BuildConfig.TEST_WEBM_CANDIDATE || it.url != KNOWN_CRASHING_WEBM }?.id,
        activeName = initialSources.firstOrNull { BuildConfig.TEST_WEBM_CANDIDATE || it.url != KNOWN_CRASHING_WEBM }?.name,
    )) {
    companion object { const val KNOWN_CRASHING_WEBM = "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.webm" }
    private var player: AndroidPlayer? = null
    private var surface: Surface? = null
    private var background = false
    private var lastPhase: PlaybackPhase? = null
    private var sourceGeneration = 0L
    private var importedSource: ImportedSource? = null
    private var requestedSource: ImportedSource? = null
    private var selectionJob: Job? = null

    init {
        if (history != null) viewModelScope.launch {
            history.state.collect { snapshot -> setState { copy(historyFailed = snapshot.failed) } }
        }
        if (session != null) viewModelScope.launch {
            var errorReported = false
            session.state.collect { snapshot ->
                if (!errorReported && snapshot.error == "HTTP_403") {
                    errorReported = true
                    setEffect(TvPlaybackEffect.ServerForbidden)
                }
                setState { copy(imported = snapshot.result?.items ?: imported,
                    importPartial = snapshot.result?.partial == true,
                    discoveryRunning = snapshot.running,
                    discoveryFailed = snapshot.error != null,
                    discoveryRateLimited = snapshot.error == "RATE_LIMIT" || snapshot.result?.rateLimited == true) }
            }
        }
        viewModelScope.launch {
            try {
                val restored = historyId?.let { id ->
                    history?.find(id) ?: throw IOException("History unavailable")
                }
                val startSources = restored?.items ?: initialSources
                val playable = startSources.filter { BuildConfig.TEST_WEBM_CANDIDATE || it.url != KNOWN_CRASHING_WEBM }
                val firstSource = if (initialItemId == null) playable.firstOrNull()
                    else playable.firstOrNull { it.id == initialItemId }
                if (firstSource == null) throw IOException("Source unavailable")
                if (restored != null) setState { copy(imported = restored.items, importPartial = restored.partial,
                    activeId = firstSource.id, activeName = firstSource.name) }
                val created = AndroidPlayer.create(appContext)
                player = created
                surface?.let { report(created.setSurface(it)) }
                selectImported(firstSource)
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
                Log.e("PlayTvPreview", "initialization failed: ${error.javaClass.simpleName}")
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
                    (requestedSource ?: importedSource)?.let { selectImported(it) }
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
                currentCoroutineContext().ensureActive()
                Log.w("PlayTvPreview", "source resolution failed: ${error.javaClass.simpleName}")
                setState { copy(sourceResolving = false, error = "SOURCE_RESOLUTION_FAILED") }
                if (error.sourceHttpStatus() == 403) setEffect(TvPlaybackEffect.ServerForbidden)
            }
        }
    }
    private fun report(result: CommandResult) {
        if (result is CommandResult.Rejected) {
            Log.w("PlayTvPreview", "command rejected: ${result.failure.code}")
            setState { copy(error = result.failure.code.name) }
        }
    }
    override fun onCleared() {
        selectionJob?.cancel()
        if (sessionId != null) discoveryOwner?.close(sessionId)
        network.close()
        surface = null
        player?.close()
        player = null
        super.onCleared()
    }
}
