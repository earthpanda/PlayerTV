package com.ai.playtv.support.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ai.playtv.support.network.converter.DiagnosticGsonConverterFactory
import com.ai.playtv.support.network.interceptor.GzipCompatibilityInterceptor
import com.ai.playtv.support.network.interceptor.NetworkDiagnosticsInterceptor
import com.ai.playtv.support.network.interceptor.RequestTimeoutInterceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Sony NetworkClient 的集中组装方式；仅负责 SDK，不含登录、环境存储和业务 Service。 */
object NetworkClientFactory {
    fun createHttpClient(
        config: NetworkConfig,
        diagnostics: NetworkDiagnostics = NetworkDiagnostics.DEFAULT,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
        // POST/写操作的幂等与重试由业务决定；重定向需明确目标和 Header 边界。
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(NetworkDiagnosticsInterceptor(diagnostics))
        .apply { config.interceptors.toList().forEach { addInterceptor(it) } }
        .addInterceptor(RequestTimeoutInterceptor())
        .apply {
            addNetworkInterceptor { chain ->
                if (!config.allowCleartext && !chain.request().url.isHttps) {
                    throw java.io.IOException("Cleartext transport disabled")
                }
                chain.proceed(chain.request())
            }
            config.networkInterceptors.toList().forEach { addNetworkInterceptor(it) }
            if (config.gzipCompatibility) addNetworkInterceptor(GzipCompatibilityInterceptor())
        }
        .build()

    /** Gson 由组合根一次配置，可注册明确类型的 TypeAdapterFactory；不启用全局 lenient。 */
    fun createRetrofit(
        config: NetworkConfig,
        client: OkHttpClient,
        gson: Gson = GsonBuilder().create(),
        diagnostics: NetworkDiagnostics = NetworkDiagnostics.DEFAULT,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(DiagnosticGsonConverterFactory.create(gson, diagnostics))
        .build()
}
