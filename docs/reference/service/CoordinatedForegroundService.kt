package com.ai.playtv.service.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.annotation.MainThread
import com.ai.playtv.service.runtime.RuntimeNotificationCoordinator.RuntimeNotice

/**
 * 可选的 started Service 基类参考：只封装前台登记、更新和结束，不负责业务 SDK。
 * 使用范围：同进程、每个 Service 单一运行会话、固定前台类型、START_NOT_STICKY。
 * 绑定、同服务多会话、动态类型切换需要按实际功能扩展，不能据此推定已实现。
 */
abstract class CoordinatedForegroundService : Service() {
    protected abstract val notificationCoordinator: RuntimeNotificationCoordinator
    protected abstract val requiredForegroundServiceType: Int
    private var registration: RuntimeNotificationCoordinator.Registration? = null

    /** 子类明确命令、重复启动与空 Intent 的行为，并返回实际恢复策略。 */
    abstract override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int

    /**
     * 子类在 onStartCommand 开始工作前调用；通知先表达准备中，业务确认后再更新。
     * 权限申请由 UI 承担，本方法仍可能因系统启动条件/类型不符而失败。
     */
    @MainThread
    protected fun enterForeground(notice: RuntimeNotice): Result<Unit> = try {
        val current = registration
        if (current == null) {
            registration = notificationCoordinator.register(this, requiredForegroundServiceType, notice)
            Result.success(Unit)
        } else {
            current.update(notice)
        }
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** 由业务成功/状态变化回调触发；回调先切回主线程并校验会话代次。 */
    @MainThread
    protected fun updateForegroundNotice(notice: RuntimeNotice): Result<Unit> {
        val current = checkNotNull(registration) { "Service has not entered foreground" }
        return current.update(notice)
    }

    /**
     * 业务停止、初始化失败、系统超时均进入同一清理路径。
     * started 命令传入 startId，防止旧命令结束排队中的新请求；任务队列需另行设计。
     */
    @MainThread
    protected fun finishRuntime(startId: Int? = null) {
        try {
            releaseRuntimeSafely()
        } finally {
            releaseRegistration()
            if (startId == null) stopSelf() else stopSelf(startId)
        }
    }

    /** 必须幂等，取消 Job/监听并释放自己的 SDK；主线程内不做耗时关闭。 */
    protected abstract fun releaseRuntime()

    /** 只提供 started 模式；有真实 bind 需求时另行设计 typed Binder 与连接所有权。 */
    final override fun onBind(intent: Intent?): IBinder? = null

    final override fun onDestroy() {
        try {
            releaseRuntimeSafely()
        } finally {
            releaseRegistration()
            super.onDestroy()
        }
    }

    /** 两参数回调覆盖 Android 15 的有时限类型，实际接入仍需按所选类型核对。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finishRuntime()
    }

    /** Android 14 shortService 回调；未使用 shortService 时不应照抄其 Manifest 类型。 */
    override fun onTimeout(startId: Int) {
        finishRuntime()
    }

    private fun releaseRuntimeSafely() {
        try {
            releaseRuntime()
        } catch (error: Exception) {
            Log.e(javaClass.simpleName, "Runtime cleanup failed", error)
        }
    }

    private fun releaseRegistration() {
        val current = registration
        registration = null
        current?.release()?.exceptionOrNull()?.let { error ->
            Log.e(javaClass.simpleName, "Foreground notification cleanup failed", error)
        }
    }
}
