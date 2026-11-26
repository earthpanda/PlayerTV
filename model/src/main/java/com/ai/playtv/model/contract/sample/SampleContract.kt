package com.ai.playtv.model.contract.sample

import com.ai.playtv.model.bean.domain.sample.SampleItem
import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import com.ai.playtv.model.state.common.PageStatus

/** Complete snapshot for the technical sample; data definitions stay outside the ViewModel. */
data class SampleState(
    /** Mutually exclusive first-page/content phase. */
    val pageStatus: PageStatus = PageStatus.Initial,
    /** Successfully committed immutable row snapshot. */
    val items: List<SampleItem> = emptyList(),
    /** Refresh is independent of content already on screen. */
    val isRefreshing: Boolean = false,
    /** Append requests keep existing rows while running. */
    val isLoadingMore: Boolean = false,
    /** Null explicitly means there is no next page. */
    val nextPage: Int? = 0,
    /** Last failed stage remains inspectable even if its transient Toast is not delivered. */
    val loadFailure: SampleLoadFailure? = null,
) : UiState

/** Sample-only failure stages, not backend codes or a universal request state machine. */
enum class SampleLoadFailure {
    /** No initial result has been committed. */
    Initial,
    /** A replacement request failed; retain the previously committed content or empty state. */
    Refresh,
    /** An append failed; retain the same cursor for retry. */
    LoadMore,
}

/** Inputs for one interaction unit, colocated with State and Effect. */
sealed interface SampleEvent : UiEvent {
    /** Load only if this ViewModel has not initialized yet. */
    data object Initialize : SampleEvent
    /** Replace content with page zero; reset end-of-list state on success. */
    data object Refresh : SampleEvent
    /** Append the next page unless busy or exhausted. */
    data object LoadMore : SampleEvent
    /** Report row identity; do not put a View or callback in the Contract. */
    data class ItemClicked(val id: Long) : SampleEvent
}

/** Transient sample outputs mapped to localized UI feedback. */
sealed interface SampleEffect : UiEffect {
    /** UI resolves a localized message for the same failure stage committed to State. */
    data class LoadFailed(val phase: SampleLoadFailure) : SampleEffect
    /** Selected identity for localized feedback. */
    data class ItemSelected(val id: Long) : SampleEffect
}
