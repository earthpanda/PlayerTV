package com.ai.playtv.model.bean.domain.artwork

/** The caller supplies the media category; playback format alone is not a category. */
enum class ArtworkMediaType { CHANNEL, TV_SHOW, MOVIE, UNKNOWN }

data class ArtworkQuery(
    val title: String,
    val type: ArtworkMediaType,
    val year: Int? = null,
    val channelId: String? = null,
    val playlistLogoUrl: String? = null,
)

data class ArtworkCandidate(val smallUrl: String? = null, val backgroundUrl: String? = null, val provider: String)

sealed interface ArtworkLookupResult {
    data class Found(val candidate: ArtworkCandidate) : ArtworkLookupResult
    data object NoMatch : ArtworkLookupResult
    data object Ambiguous : ArtworkLookupResult
    data object ConfigurationRequired : ArtworkLookupResult
}
