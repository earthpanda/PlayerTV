package com.ai.playtv.model.contract.sourceentry

import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState

data class SourceEntryState(val loadingId: Long? = null, val error: String? = null) : UiState
sealed interface SourceEntryEvent : UiEvent {
    data class Submit(val url: String) : SourceEntryEvent
    data class Cancel(val id: Long) : SourceEntryEvent
    data object ClearError : SourceEntryEvent
}
sealed interface SourceEntryEffect : UiEffect {
    data object OpenDefaults : SourceEntryEffect
    data class OpenPlayer(val items: List<ImportedSource>, val partial: Boolean) : SourceEntryEffect
}
