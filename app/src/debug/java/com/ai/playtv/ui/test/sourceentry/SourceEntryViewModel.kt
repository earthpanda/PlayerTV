package com.ai.playtv.ui.test.sourceentry

import androidx.lifecycle.viewModelScope
import com.ai.playtv.di.SourceDiscoveryOwner
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sourceentry.*
import com.ai.playtv.model.bean.domain.source.ReferenceKind
import com.ai.playtv.model.storage.source.SourceHistoryStore
import com.ai.playtv.ui.test.tvplayback.TvPlaybackViewModel
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SourceEntryViewModel(private val owner: SourceDiscoveryOwner,
    private val history: SourceHistoryStore) :
    BaseMviViewModel<SourceEntryState, SourceEntryEvent, SourceEntryEffect>(SourceEntryState()) {
    private var sequence = 0L
    private var job: Job? = null
    private var sessionId: String? = null
    private var handedOff = false
    init {
        viewModelScope.launch {
            history.state.collect { snapshot ->
                setState { copy(history = snapshot.entries.map { entry ->
                    val uri = URI(entry.input)
                    SourceHistoryRow(entry.id, uri.host + uri.rawPath.orEmpty(), entry.lastUsedAtMs)
                }, repositorySuggestions = snapshot.entries.mapNotNull { entry ->
                    val uri = URI(entry.input)
                    if (uri.host.equals("github.com", ignoreCase = true) &&
                        uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }.size == 2)
                        entry.input.substringBefore('?') else null
                }.distinct(), historyLoading = snapshot.loading, historyFailed = snapshot.failed) }
            }
        }
        viewModelScope.launch {
            try { history.load() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Store publishes the failure to the screen. */ }
        }
    }
    override fun onEvent(event: SourceEntryEvent) {
        when (event) {
            is SourceEntryEvent.Submit -> {
                if (currentState.loadingId != null || currentState.historyOpening) return
                if (event.url.isBlank()) {
                    setState { copy(error = "REQUIRED") }
                    return
                }
                val id = ++sequence
                setState { copy(loadingId = id, error = null) }
                job?.cancel()
                val (newSessionId, session) = owner.start(event.url)
                sessionId = newSessionId
                handedOff = false
                job = viewModelScope.launch {
                    var errorReported = false
                    session.state.collect { snapshot ->
                        val result = snapshot.result
                        val first = result?.items?.firstOrNull {
                            it.url != TvPlaybackViewModel.KNOWN_CRASHING_WEBM
                        }
                        if (!handedOff && first != null) {
                            if (setEffect(SourceEntryEffect.OpenPlayer(newSessionId))) {
                                handedOff = true
                                setState { copy(loadingId = null) }
                            } else {
                                handedOff = true
                                owner.close(newSessionId)
                                setState { copy(loadingId = null, error = "FAILED") }
                            }
                        } else if (!handedOff && !snapshot.running) {
                            if (!errorReported && snapshot.error == "HTTP_403") {
                                errorReported = true
                                setEffect(SourceEntryEffect.ServerForbidden)
                            }
                            setState { copy(loadingId = null, error = snapshot.error ?: when {
                                ReferenceKind.CLOUD_SHARE in result?.references.orEmpty() -> "CLOUD"
                                ReferenceKind.WEBSITE in result?.references.orEmpty() -> "WEBSITE"
                                result?.partial == true -> "PARTIAL_EMPTY"
                                else -> "EMPTY"
                            }) }
                            owner.close(newSessionId)
                        }
                    }
                }
            }
            is SourceEntryEvent.Cancel -> if (currentState.loadingId == event.id) {
                job?.cancel()
                sessionId?.let(owner::close)
                sessionId = null
                setState { copy(loadingId = null) }
            }
            is SourceEntryEvent.OpenHistory -> {
                if (currentState.loadingId != null || currentState.historyOpening) return
                setState { copy(historyOpening = true) }
                viewModelScope.launch {
                    try {
                        val entry = history.open(event.id)
                        if (entry != null && !setEffect(SourceEntryEffect.OpenHistory(entry.id))) {
                            setState { copy(error = "FAILED") }
                        } else if (entry == null) setState { copy(error = "FAILED") }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { setState { copy(error = "HISTORY_FAILED") } }
                    finally { setState { copy(historyOpening = false) } }
                }
            }
            SourceEntryEvent.ClearError -> setState { copy(error = null) }
        }
    }
    override fun onCleared() {
        job?.cancel()
        if (!handedOff) sessionId?.let(owner::close)
        super.onCleared()
    }
}
