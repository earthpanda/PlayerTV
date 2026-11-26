package com.ai.playtv.support.network.header

import okhttp3.HttpUrl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 作为 networkInterceptor 安装；每一跳先移除受管 Header，仅同 scheme/host/port 注入。
 * Header 名固定、值每次读取，账号切换仍须取消旧作用域；不注入 URL query/cookie 存储。
 */
class OriginHeadersInterceptor(
    private val origin: HttpUrl,
    headerNames: Set<String>,
    private val values: () -> Map<String, String>,
) : Interceptor {
    private val managedNames = headerNames.map { it.lowercase(java.util.Locale.ROOT) }.toSet()

    init {
        require(managedNames.isNotEmpty())
        require(managedNames.none { it in setOf("host", "content-length", "content-type", "accept-encoding",
            "connection", "transfer-encoding", "x-read-timeout-seconds") })
        // 固定名称在组装时校验，避免错误配置直到 OkHttp 工作线程才抛出。
        managedNames.forEach { Headers.Builder().add(it, "") }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        managedNames.forEach { builder.removeHeader(it) }
        val url = request.url
        if (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
            val current = try { values().toMap() } catch (exception: Exception) {
                throw IOException("Header provider failed", exception)
            }
            current.forEach { (name, value) ->
                if (name.lowercase(java.util.Locale.ROOT) !in managedNames) {
                    throw IOException("Undeclared managed header")
                }
                try { builder.header(name, value) } catch (exception: IllegalArgumentException) {
                    throw IOException("Invalid managed header", exception)
                }
            }
        }
        return chain.proceed(builder.build())
    }
}
