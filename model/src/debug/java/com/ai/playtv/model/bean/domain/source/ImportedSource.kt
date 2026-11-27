package com.ai.playtv.model.bean.domain.source

enum class SourceKind { MEDIA, HLS, DASH, UNKNOWN }
enum class SourceOrigin { GITHUB_REPOSITORY, GITHUB_FILE, NETWORK }
enum class ReferenceKind { WEBSITE, CLOUD_SHARE }
const val MAX_IMPORTED_SOURCES = 1_000
data class ImportedSource(val id: String, val name: String, val url: String, val kind: SourceKind)
data class SourceDiscovery(val origin: SourceOrigin, val items: List<ImportedSource>, val partial: Boolean = false,
    val references: Set<ReferenceKind> = emptySet(), val rateLimited: Boolean = false)
