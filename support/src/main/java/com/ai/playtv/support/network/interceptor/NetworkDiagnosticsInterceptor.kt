package com.ai.playtv.support.network.interceptor

import com.ai.playtv.support.network.NetworkDiagnostic
import com.ai.playtv.support.network.NetworkDiagnostics
import com.ai.playtv.support.network.report
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

/** 只看状态和耗时，不消费响应流；SSE 耗时由连接管理器另行记录。 */
class NetworkDiagnosticsInterceptor(private val diagnostics: NetworkDiagnostics) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val id = ids.incrementAndGet()
        val start = System.nanoTime()
        try {
            return chain.proceed(chain.request()).also {
                diagnostics.report(NetworkDiagnostic("http", id, it.code, elapsed(start)))
            }
        } catch (exception: Exception) {
            diagnostics.report(NetworkDiagnostic("http", id, elapsedMs = elapsed(start),
                failureType = exception.javaClass.simpleName))
            throw exception
        }
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
    private companion object { val ids = AtomicLong() }
}
