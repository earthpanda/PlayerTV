package com.ai.playtv.model.bean.domain.sample

/** Local sample pagination result, separate from the screen's State/Event/Effect. */
data class SamplePage(
    /** Stable rows in this page. */
    val items: List<SampleItem>,
    /** Next zero-based page, or null when the sample data is exhausted. */
    val nextPage: Int?,
)
