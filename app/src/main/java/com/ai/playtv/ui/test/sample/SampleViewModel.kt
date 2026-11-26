package com.ai.playtv.ui.test.sample

import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.model.contract.sample.SampleEffect
import com.ai.playtv.model.contract.sample.SampleEvent
import com.ai.playtv.model.contract.sample.SampleLoadFailure
import com.ai.playtv.model.contract.sample.SampleState
import com.ai.playtv.model.repository.sample.SampleRepository
import com.ai.playtv.model.state.common.PageStatus
import kotlinx.coroutines.CancellationException

/**
 * Execute the local demo's Contract; no data/enum/sealed definitions live in this file.
 * Construct with SampleViewModel(SampleRepository()) using the explicit Factory.
 */
class SampleViewModel(
    /** Data source is supplied by the composition root, never pulled from a global singleton. */
    private val repository: SampleRepository,
) : BaseMviViewModel<SampleState, SampleEvent, SampleEffect>(SampleState()) {
    /** Serialize refresh/append requests and avoid reloading on Activity recreation. */
    override fun onEvent(event: SampleEvent) {
        when (event) {
            SampleEvent.Initialize -> if (currentState.pageStatus == PageStatus.Initial) load(refresh = true)
            SampleEvent.Refresh -> load(refresh = true)
            SampleEvent.LoadMore -> load(refresh = false)
            is SampleEvent.ItemClicked -> setEffect(SampleEffect.ItemSelected(event.id))
        }
    }

    /** Load one page while preserving committed content on noninitial failures. */
    private fun load(refresh: Boolean) {
        if (currentState.isRefreshing || currentState.isLoadingMore) return
        if (!refresh && currentState.pageStatus != PageStatus.Content) return
        val beforeRequest = currentState
        val page = if (refresh) 0 else beforeRequest.nextPage ?: return
        val failurePhase = when {
            !refresh -> SampleLoadFailure.LoadMore
            beforeRequest.pageStatus == PageStatus.Initial || beforeRequest.pageStatus == PageStatus.Error ->
                SampleLoadFailure.Initial
            else -> SampleLoadFailure.Refresh
        }
        setState {
            copy(
                pageStatus = if (failurePhase == SampleLoadFailure.Initial) PageStatus.Loading else pageStatus,
                isRefreshing = refresh,
                isLoadingMore = !refresh,
                loadFailure = null,
            )
        }
        launchSafe {
            try {
                val result = repository.loadPage(page)
                setState {
                    val updated = if (refresh) result.items else items + result.items
                    copy(
                        items = updated,
                        pageStatus = if (updated.isEmpty()) PageStatus.Empty else PageStatus.Content,
                        nextPage = result.nextPage,
                        isRefreshing = false,
                        isLoadingMore = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                // This preview serializes requests; restore only the fields owned by this request.
                setState {
                    copy(pageStatus = beforeRequest.pageStatus, isRefreshing = false,
                        isLoadingMore = false, loadFailure = beforeRequest.loadFailure)
                }
                throw cancelled
            } catch (error: Exception) {
                handleError(error)
                setState {
                    copy(pageStatus = if (failurePhase == SampleLoadFailure.Initial) PageStatus.Error
                        else beforeRequest.pageStatus,
                        isRefreshing = false, isLoadingMore = false, loadFailure = failurePhase)
                }
                setEffect(SampleEffect.LoadFailed(failurePhase))
            }
        }
    }
}
