package com.ai.playtv.model.state.common

/**
 * Mutually exclusive initial-page phases; local refresh/pagination flags remain orthogonal.
 * UI maps phases to local strings and views, e.g. PageStateLayout.render(PageStatus.Empty).
 */
sealed interface PageStatus {
    /** Data has not yet been requested. */
    data object Initial : PageStatus
    /** Initial content is being loaded. */
    data object Loading : PageStatus
    /** Usable content is available. */
    data object Content : PageStatus
    /** Loading succeeded with no items. */
    data object Empty : PageStatus
    /** Initial loading failed; the UI may expose a retry event. */
    data object Error : PageStatus
}
