package com.ai.playtv.service.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.annotation.MainThread

/**
 * 可选接入参考，不在 Android 源集。由 app 组合根按需构造，同一进程共用一份。
 * 各 Service 独立提升前台，统一使用 notificationId 和 render 生成的汇总通知。
 * 本类仅保存平台展示文本；业务事实与任务所有权由各运行控制器负责。
 */
class RuntimeNotificationCoordinator(
    context: Context,
    private val notificationId: Int,
    private val render: (List<RuntimeNotice>) -> Notification,
) {
    private val manager = checkNotNull(context.applicationContext
        .getSystemService(NotificationManager::class.java))
    private val registrations = linkedMapOf<Service, Registration>()

    init {
        require(notificationId > 0) { "Foreground notification ID must be positive" }
    }

    /** app 已解析的展示数据，不作为 model 的业务成功状态。可显示“正在准备”。 */
    data class RuntimeNotice(val title: CharSequence, val detail: CharSequence)

    /**
     * 每个真实 Service 实例一份令牌，不按类名猜所有者；旧实例不能注销新实例。
     * 同一个 Service 内有多个任务时，先在业务控制器聚合，再更新这一份登记。
     */
    inner class Registration internal constructor(
        internal val service: Service,
        internal var notice: RuntimeNotice,
    ) {
        @MainThread
        fun update(notice: RuntimeNotice): Result<Unit> {
            checkMainThread()
            check(registrations[service] === this) { "Registration is closed" }
            this.notice = notice
            // 展示失败不回滚真实运行事实；调用方记录错误，并按功能策略重试/降级。
            return publishCurrent()
        }

        /** 先 detach 当前服务，通知是否保留由剩余登记决定；重复释放无副作用。 */
        @MainThread
        fun release(): Result<Unit> {
            checkMainThread()
            if (registrations[service] !== this) return Result.success(Unit)
            registrations.remove(service)
            var detachFailure: Exception? = null
            try {
                service.stopForeground(Service.STOP_FOREGROUND_DETACH)
            } catch (error: Exception) {
                detachFailure = error
            }
            val displayResult = publishCurrent()
            val failure = detachFailure ?: return displayResult
            displayResult.exceptionOrNull()?.let { failure.addSuppressed(it) }
            return Result.failure(failure)
        }
    }

    /**
     * 必须在接收前台启动命令后及时调用，耗时初始化放在本方法成功之后。
     * serviceType 是这个 Service 自己的类型，不是所有服务类型的按位或。
     * startForeground 抛错时不发布登记；调用方停止本次服务并报告真实失败。
     */
    @MainThread
    fun register(
        service: Service,
        serviceType: Int,
        notice: RuntimeNotice,
    ): Registration {
        checkMainThread()
        require(serviceType >= 0 && (Build.VERSION.SDK_INT < 34 || serviceType > 0)) {
            "A real foreground service type is required on Android 14+"
        }
        check(service !in registrations) { "Service is already registered" }
        val notification = render(registrations.values.map { it.notice } + notice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(notificationId, notification, serviceType)
        } else {
            service.startForeground(notificationId, notification)
        }
        return Registration(service, notice).also { registrations[service] = it }
    }

    /** 仅供诊断；运行注册表不持久化，不把上次进程的服务恢复成“仍在运行”。 */
    @MainThread
    fun currentNotices(): List<RuntimeNotice> {
        checkMainThread()
        return registrations.values.map { it.notice }
    }

    private fun publishCurrent(): Result<Unit> = try {
        if (registrations.isEmpty()) {
            manager.cancel(notificationId)
        } else {
            // 同一个稳定 ID 覆盖同一条通知。通知构造、更新、取消都由此入口协调。
            manager.notify(notificationId, render(registrations.values.map { it.notice }))
        }
        Result.success(Unit)
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Service notification coordination must run on the main thread"
        }
    }
}
