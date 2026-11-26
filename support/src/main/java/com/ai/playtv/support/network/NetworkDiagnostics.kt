package com.ai.playtv.support.network

import com.ai.playtv.base.diagnostics.DiagnosticEvent
import com.ai.playtv.base.diagnostics.DiagnosticLevel
import com.ai.playtv.base.diagnostics.DiagnosticLog

/** 仅技术元数据；禁止加入 URL、Header、body 或 Throwable.message。 */
data class NetworkDiagnostic(
    val stage: String,
    val requestId: Long? = null,
    val statusCode: Int? = null,
    val elapsedMs: Long? = null,
    val failureType: String? = null,
    val bufferCapacity: Int? = null,
)

fun interface NetworkDiagnostics {
    fun record(event: NetworkDiagnostic)

    companion object {
        /** 默认仅记录失败；成功追踪可由组合根注入本地实现。 */
        val DEFAULT = NetworkDiagnostics { event ->
            if (event.failureType != null || (event.statusCode ?: 0) >= 400) {
                DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("network", event.stage,
                    "failed", event.requestId, event.statusCode, event.elapsedMs, event.bufferCapacity, event.failureType))
            }
        }
    }
}

/** 诊断回调故障不改变网络结果；不输出回调异常的敏感消息。 */
internal fun NetworkDiagnostics.report(event: NetworkDiagnostic) {
    try {
        record(event)
    } catch (exception: Exception) {
        DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("network", "diagnostics", "failed",
            errorType = exception.javaClass.simpleName))
    }
}
