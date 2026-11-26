package com.ai.playtv.ui.test.sourceentry

import androidx.lifecycle.viewModelScope
import com.ai.playtv.di.SourceDiscoveryOwner
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sourceentry.*
import com.ai.playtv.model.bean.domain.source.ReferenceKind
import com.ai.playtv.ui.test.tvplayback.TvPlaybackViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SourceEntryViewModel(private val owner: SourceDiscoveryOwner,
    private val testMode: Boolean) :
    BaseMviViewModel<SourceEntryState, SourceEntryEvent, SourceEntryEffect>(SourceEntryState()) {
    private var sequence = 0L
    private var job: Job? = null
    private var sessionId: String? = null
    private var handedOff = false
    override fun onEvent(event: SourceEntryEvent) {
        when (event) {
            is SourceEntryEvent.Submit -> {
                if (currentState.loadingId != null) return
                if (event.url.isBlank()) {
                    if (testMode) setEffect(SourceEntryEffect.OpenDefaults) else setState { copy(error = "REQUIRED") }
                    return
                }
                val id = ++sequence
                setState { copy(loadingId = id, error = null) }
                job?.cancel()
                val (newSessionId, session) = owner.start(event.url)
                sessionId = newSessionId
                handedOff = false
                job = viewModelScope.launch {
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
            SourceEntryEvent.ClearError -> setState { copy(error = null) }
        }
    }
    override fun onCleared() {
        job?.cancel()
        if (!handedOff) sessionId?.let(owner::close)
        super.onCleared()
    }
}
