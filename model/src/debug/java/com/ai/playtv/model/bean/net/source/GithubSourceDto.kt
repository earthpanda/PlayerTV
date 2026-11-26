package com.ai.playtv.model.bean.net.source

import com.ai.playtv.support.network.json.JsonModel
import com.google.gson.annotations.SerializedName

@JsonModel data class GithubRepositoryDto(@SerializedName("default_branch") val defaultBranch: String?)
@JsonModel data class GithubTreeDto(@SerializedName("truncated") val truncated: Boolean?, @SerializedName("tree") val tree: List<GithubTreeEntryDto>?)
@JsonModel data class GithubTreeEntryDto(@SerializedName("path") val path: String?, @SerializedName("type") val type: String?)
