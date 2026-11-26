package com.ai.playtv.base.diagnostics

import android.os.SystemClock

/** 必需初始化逐项计时并重抛失败；仅在整个 block 成功后由调用方发布依赖。标识必须为常量。 */
object StartupStep {
    fun <T> run(name: String, initialize: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val result = initialize()
            DiagnosticLog.write(DiagnosticLevel.INFO, DiagnosticEvent("startup", name, "ready",
                durationMs = SystemClock.elapsedRealtime() - startedAt))
            return result
        } catch (failure: Throwable) {
            DiagnosticLog.write(DiagnosticLevel.ERROR, DiagnosticEvent("startup", name, "failed",
                durationMs = SystemClock.elapsedRealtime() - startedAt, errorType = failure.javaClass.simpleName))
            throw failure
        }
    }
}
