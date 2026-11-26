package com.ai.playtv.service.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * app 提供真实渠道/文案/图标/明确点击目标；参考实现不读取业务单例或猜资源。
 * 此配置仅用于同一前台通知组，普通提醒与消息仍有自己的渠道和通知身份。
 */
data class RuntimeNotificationConfig(
    val notificationId: Int,
    val channelId: String,
    val channelName: String,
    val title: String,
    val smallIconResId: Int,
    val contentIntent: PendingIntent,
)

/** minSdk 26；仅在业务选择接入时由 app 容器懒构造，同进程复用返回的一份协调器。 */
fun createRuntimeNotificationCoordinator(
    context: Context,
    config: RuntimeNotificationConfig,
): RuntimeNotificationCoordinator {
    require(config.channelId.isNotBlank())
    require(config.smallIconResId > 0)
    val appContext = context.applicationContext
    val manager = checkNotNull(appContext.getSystemService(NotificationManager::class.java))
    manager.createNotificationChannel(
        NotificationChannel(
            config.channelId,
            config.channelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        },
    )
    return RuntimeNotificationCoordinator(appContext, config.notificationId) { notices ->
        val lines = notices.map { "${it.title}: ${it.detail}" }
        NotificationCompat.Builder(appContext, config.channelId)
            .setSmallIcon(config.smallIconResId)
            .setContentTitle(config.title)
            .setContentText(lines.joinToString("; "))
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                lines.forEach { style.addLine(it) }
            })
            .setContentIntent(config.contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
