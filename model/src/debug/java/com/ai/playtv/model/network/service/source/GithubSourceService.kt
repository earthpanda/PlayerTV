package com.ai.playtv.model.network.service.source

import com.ai.playtv.model.bean.net.source.GithubRepositoryDto
import com.ai.playtv.model.bean.net.source.GithubTreeDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GithubSourceService {
    @GET("repos/{owner}/{repo}") suspend fun repository(@Path("owner") owner: String, @Path("repo") repo: String): GithubRepositoryDto
    @GET("repos/{owner}/{repo}/git/trees/{ref}?recursive=1") suspend fun tree(@Path("owner") owner: String, @Path("repo") repo: String, @Path("ref") ref: String): GithubTreeDto
    @GET("repos/{owner}/{repo}/commits/{ref}") suspend fun commit(@Path("owner") owner: String, @Path("repo") repo: String, @Path("ref") ref: String): com.google.gson.JsonObject
    @Streaming @GET suspend fun content(@Url url: String): Response<ResponseBody>
}
