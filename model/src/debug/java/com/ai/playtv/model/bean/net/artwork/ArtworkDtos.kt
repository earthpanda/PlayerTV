package com.ai.playtv.model.bean.net.artwork

import com.ai.playtv.support.network.json.JsonModel
import com.google.gson.annotations.SerializedName

@JsonModel data class ChannelDto(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("alt_names") val alternativeNames: List<String?>?,
)

@JsonModel data class ChannelLogoDto(
    @SerializedName("channel") val channel: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("in_use") val inUse: Boolean?,
)

@JsonModel data class ShowSearchDto(@SerializedName("show") val show: ShowDto?)
@JsonModel data class ShowDto(
    @SerializedName("id") val id: Long?,
    @SerializedName("name") val name: String?,
    @SerializedName("premiered") val premiered: String?,
    @SerializedName("image") val image: ShowImageSizesDto?,
)
@JsonModel data class ShowImageSizesDto(@SerializedName("original") val original: String?, @SerializedName("medium") val medium: String?)
@JsonModel data class ShowArtworkDto(
    @SerializedName("type") val type: String?,
    @SerializedName("main") val main: Boolean?,
    @SerializedName("resolutions") val resolutions: ShowResolutionsDto?,
)
@JsonModel data class ShowResolutionsDto(@SerializedName("original") val original: ShowImageUrlDto?, @SerializedName("medium") val medium: ShowImageUrlDto?)
@JsonModel data class ShowImageUrlDto(@SerializedName("url") val url: String?)

@JsonModel data class MovieSearchDto(@SerializedName("results") val results: List<MovieDto>?)
@JsonModel data class MovieDto(
    @SerializedName("id") val id: Long?,
    @SerializedName("title") val title: String?,
    @SerializedName("original_title") val originalTitle: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
)
