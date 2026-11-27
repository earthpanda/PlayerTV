package com.ai.playtv.model.contract.sourcesearch

import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState

data class SearchRow(val historyId: String, val itemId: String, val name: String, val artworkRevision: String? = null)
data class SourceSearchState(
    val query: String = "", val rows: List<SearchRow> = emptyList(),
    val loading: Boolean = true, val failed: Boolean = false,
    val hasItems: Boolean = false, val partial: Boolean = false, val opening: Boolean = false,
    val searching: Boolean = false,
) : UiState
sealed interface SourceSearchEvent : UiEvent {
    data class Query(val value: String) : SourceSearchEvent
    data class Open(val row: SearchRow) : SourceSearchEvent
    data object Resume : SourceSearchEvent
}
sealed interface SourceSearchEffect : UiEffect {
    data class OpenPlayer(val historyId: String, val itemId: String) : SourceSearchEffect
}
