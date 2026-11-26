package com.ai.playtv.model.bean.domain.sample

/** Independent sample row data; displayed text is resolved by the UI from this stable ID. */
data class SampleItem(
    /** Stable identity used for DiffUtil comparisons and click results. */
    val id: Long,
)
