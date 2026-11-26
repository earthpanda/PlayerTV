package com.ai.playtv.support.network

import okhttp3.HttpUrl
import okhttp3.Interceptor

/** 每个后端/会话创建配置快照；环境切换重建所属依赖，不修改全局 URL。 */
data class NetworkConfig(
    val baseUrl: HttpUrl,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 30,
    val writeTimeoutSeconds: Long = 30,
    val callTimeoutSeconds: Long = 60,
    val allowCleartext: Boolean = false,
    val gzipCompatibility: Boolean = false,
    val interceptors: List<Interceptor> = emptyList(),
    val networkInterceptors: List<Interceptor> = emptyList(),
) {
    init {
        require(baseUrl.isHttps || allowCleartext) { "HTTPS required" }
        require(baseUrl.encodedPath.endsWith('/')) { "Base URL must end with /" }
        require(connectTimeoutSeconds in 1..86400 && readTimeoutSeconds in 1..86400)
        require(writeTimeoutSeconds in 1..86400 && callTimeoutSeconds in 1..86400)
    }
}
