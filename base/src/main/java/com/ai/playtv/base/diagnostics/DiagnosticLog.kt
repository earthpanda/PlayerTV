package com.ai.playtv.base.diagnostics

import android.util.Log

enum class DiagnosticLevel(val priority: Int) { DEBUG(Log.DEBUG), INFO(Log.INFO), WARN(Log.WARN), ERROR(Log.ERROR) }

/** 只接受分类和标量，禁止 URL、Header、文件内容和 Throwable.message。标识须为无敏感信息的常量。 */
data class DiagnosticEvent(
    val component: String,
    val operation: String,
    val outcome: String,
    val requestId: Long? = null,
    val statusCode: Int? = null,
    val durationMs: Long? = null,
    val capacity: Int? = null,
    val errorType: String? = null,
    val ownerType: String? = null,
    val itemType: String? = null,
)

/** 默认只写 WARN/ERROR，不做磁盘持久化或上传。组合根可调整等级，不记录任意原始消息。 */
object DiagnosticLog {
    @Volatile var minimumLevel: DiagnosticLevel = DiagnosticLevel.WARN

    fun write(level: DiagnosticLevel, event: DiagnosticEvent) {
        if (level.priority < minimumLevel.priority) return
        Log.println(level.priority, "Foundation", buildString {
            append("component=${safe(event.component)} operation=${safe(event.operation)} outcome=${safe(event.outcome)}")
            event.requestId?.let { append(" requestId=$it") }
            event.statusCode?.let { append(" statusCode=$it") }
            event.durationMs?.let { append(" durationMs=$it") }
            event.capacity?.let { append(" capacity=$it") }
            event.errorType?.let { append(" errorType=${safe(it)}") }
            event.ownerType?.let { append(" ownerType=${safe(it)}") }
            event.itemType?.let { append(" itemType=${safe(it)}") }
        })
    }

    private val identifier = Regex("[A-Za-z0-9_.:-]{1,80}")
    private fun safe(value: String) = if (identifier.matches(value)) value else "redacted"
}
