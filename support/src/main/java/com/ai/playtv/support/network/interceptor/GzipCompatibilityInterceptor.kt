package com.ai.playtv.support.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.GzipSource
import okio.buffer

/** Sony gzip 魔数兼容实现；仅已知错误服务端启用，普通请求使用 OkHttp 透明 gzip。 */
class GzipCompatibilityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        // SSE 不预读，避免等待两个字节拖延 onOpen；无 body 的状态也不探测。
        if (chain.request().method == "HEAD" || response.code in listOf(204, 205, 304) ||
            body.contentType()?.subtype.equals("event-stream", ignoreCase = true)) return response
        try {
            val source = body.source()
            val isGzip = source.request(2) && source.buffer[0] == 0x1f.toByte() &&
                source.buffer[1] == 0x8b.toByte()
            if (isGzip) {
                return response.newBuilder().removeHeader("Content-Encoding")
                    .removeHeader("Content-Length")
                    .body(GzipSource(source).buffer().asResponseBody(body.contentType(), -1)).build()
            }
            if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
                return response.newBuilder().removeHeader("Content-Encoding").build()
            }
            return response
        } catch (exception: Exception) {
            body.close()
            throw exception
        }
    }
}
