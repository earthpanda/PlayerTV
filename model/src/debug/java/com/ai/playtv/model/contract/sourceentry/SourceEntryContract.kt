package com.ai.playtv.model.contract.sourceentry

import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState

data class SourceHistoryRow(val id: String, val title: String, val lastUsedAtMs: Long)
data class SourceEntryState(
    val loadingId: Long? = null, val error: String? = null,
    val history: List<SourceHistoryRow> = emptyList(), val historyLoading: Boolean = true,
    val historyFailed: Boolean = false, val historyOpening: Boolean = false,
    val repositorySuggestions: List<String> = emptyList(),
) : UiState
sealed interface SourceEntryEvent : UiEvent {
    data class Submit(val url: String) : SourceEntryEvent
    data class Cancel(val id: Long) : SourceEntryEvent
    data class OpenHistory(val id: String) : SourceEntryEvent
    data object ClearError : SourceEntryEvent
}
sealed interface SourceEntryEffect : UiEffect {
    data object ServerForbidden : SourceEntryEffect
    data class OpenPlayer(val sessionId: String) : SourceEntryEffect
    data class OpenHistory(val id: String) : SourceEntryEffect
}
