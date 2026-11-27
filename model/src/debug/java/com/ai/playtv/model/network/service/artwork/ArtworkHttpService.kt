package com.ai.playtv.model.network.service.artwork

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

/** A streaming response is closed by the repository, including on error and cancellation. */
interface ArtworkHttpService {
    @Streaming @GET
    suspend fun get(@Url url: String, @Header("Authorization") authorization: String? = null): Response<ResponseBody>
}
