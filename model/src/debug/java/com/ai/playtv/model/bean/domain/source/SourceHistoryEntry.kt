package com.ai.playtv.model.bean.domain.source

/** One source and its last discovered candidates; timestamps are epoch milliseconds. */
data class SourceHistoryEntry(
    val id: String,
    val input: String,
    val lastUsedAtMs: Long,
    val items: List<ImportedSource>,
    val partial: Boolean,
)
