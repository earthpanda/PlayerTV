package com.ai.playtv.model.bean.domain.source

enum class SourceKind { MEDIA, HLS, DASH, UNKNOWN }
enum class SourceOrigin { GITHUB_REPOSITORY, GITHUB_FILE, NETWORK }
enum class ReferenceKind { WEBSITE, CLOUD_SHARE }
const val MAX_IMPORTED_SOURCES = 1_000
data class ImportedSource(
    val id: String,
    val name: String,
    val url: String,
    val kind: SourceKind,
    val channelId: String? = null,
    val logoUrl: String? = null,
)
/** Retain the first candidate's identity and order while accepting explicit missing metadata. */
fun ImportedSource.withMissingMetadataFrom(other: ImportedSource): ImportedSource = copy(
    channelId = channelId ?: other.channelId,
    logoUrl = logoUrl ?: other.logoUrl,
)
data class SourceDiscovery(val origin: SourceOrigin, val items: List<ImportedSource>, val partial: Boolean = false,
    val references: Set<ReferenceKind> = emptySet(), val rateLimited: Boolean = false,
    /** Empty means a recognized source was fully scanned, not an unknown page or failed request. */
    val emptyVerified: Boolean = false)
