package com.ai.playtv.ui.test.sourceentry

import androidx.lifecycle.viewModelScope
import android.util.Log
import com.ai.playtv.di.NetworkDependencies
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sourceentry.*
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.model.bean.domain.source.ReferenceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SourceEntryViewModel(private val repository: SourceRepository, private val network: NetworkDependencies,
    private val testMode: Boolean) :
    BaseMviViewModel<SourceEntryState, SourceEntryEvent, SourceEntryEffect>(SourceEntryState()) {
    private var sequence = 0L
    private var job: Job? = null
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
                job = viewModelScope.launch {
                    try {
                        val result = repository.discover(event.url)
                        if (result.items.isEmpty()) setState { copy(loadingId = null, error = when {
                            ReferenceKind.CLOUD_SHARE in result.references -> "CLOUD"
                            ReferenceKind.WEBSITE in result.references -> "WEBSITE"
                            result.partial -> "PARTIAL_EMPTY"
                            else -> "EMPTY"
                        }) }
                        else {
                            setState { copy(loadingId = null) }
                            setEffect(SourceEntryEffect.OpenPlayer(result.items, result.partial))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.w("PlayTvPreview", "source discovery failed: ${error.javaClass.simpleName}")
                        setState { copy(loadingId = null, error = if (error is retrofit2.HttpException && error.code() == 403) "RATE_LIMIT" else "FAILED") }
                    }
                }
            }
            is SourceEntryEvent.Cancel -> if (currentState.loadingId == event.id) {
                job?.cancel()
                setState { copy(loadingId = null) }
            }
            SourceEntryEvent.ClearError -> setState { copy(error = null) }
        }
    }
    override fun onCleared() { job?.cancel(); network.close(); super.onCleared() }
}
