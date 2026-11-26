package com.ai.playtv.support.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 提取 Sony 的按请求读超时；内部 Header 无论有效与否均不会送达服务端。 */
class RequestTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val values = request.headers.values(READ_TIMEOUT_HEADER)
        val clean = request.newBuilder().removeHeader(READ_TIMEOUT_HEADER).build()
        if (values.isEmpty()) return chain.proceed(clean)
        val seconds = values.singleOrNull()?.toIntOrNull()
        if (seconds == null || seconds !in 1..86400) {
            throw IOException("Invalid internal read timeout")
        }
        return chain.withReadTimeout(seconds, TimeUnit.SECONDS).proceed(clean)
    }

    companion object {
        const val READ_TIMEOUT_HEADER = "X-Read-Timeout-Seconds"
    }
}
