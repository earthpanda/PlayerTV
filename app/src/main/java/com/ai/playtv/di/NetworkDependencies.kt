package com.ai.playtv.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ai.playtv.support.network.NetworkClientFactory
import com.ai.playtv.support.network.NetworkConfig
import com.ai.playtv.model.network.protocol.RequestExecutor
import com.ai.playtv.base.diagnostics.DiagnosticEvent
import com.ai.playtv.base.diagnostics.DiagnosticLevel
import com.ai.playtv.base.diagnostics.DiagnosticLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 一个后端/作用域一份网络依赖。config 由 app/config 提供，创建 Service 后注入 model Repository。
 * 此类独占自己创建的客户端；共享给多个页面时由更长作用域持有，页面不得自行 close。
 * 环境切换先使旧任务失效并取消/等待，再 close 和 awaitClosed，最后发布新依赖图。
 */
class NetworkDependencies(config: NetworkConfig, gson: Gson = GsonBuilder().create()) : AutoCloseable {
    private val client = NetworkClientFactory.createHttpClient(config)
    private val retrofit = try { NetworkClientFactory.createRetrofit(config, client, gson) }
    catch (failure: Throwable) {
        // 新建客户端尚未暴露、未发起请求，不存在需要驱逐的连接。
        client.dispatcher.executorService.shutdown()
        throw failure
    }
    private var closed = false
    private val cleanupFinished = CompletableDeferred<Unit>()
    val requests = RequestExecutor()

    /** Service 在功能依赖类中按类型保存；不把所有业务塞进一个 ApiService。 */
    @Synchronized fun <T> service(type: Class<T>): T {
        check(!closed) { "Network dependencies are closed" }
        return retrofit.create(type)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        // TLS socket 的取消和关闭可能阻塞或触发网络检查，不能在 onCleared 的主线程执行。
        CoroutineScope(Dispatchers.IO).launch {
            var failure: Exception? = null
            try { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
            catch (error: Exception) { failure = error }
            try { client.dispatcher.executorService.shutdown() }
            catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
            if (failure == null) cleanupFinished.complete(Unit)
            else {
                DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("network", "close", "failed", errorType = failure.javaClass.simpleName))
                cleanupFinished.completeExceptionally(failure)
            }
        }
    }

    /** 需要在切换环境等流程中等待实际关闭时调用；close 后才允许等待。 */
    suspend fun awaitClosed() {
        synchronized(this) { check(closed) { "Close must be requested first" } }
        cleanupFinished.await()
    }
}
