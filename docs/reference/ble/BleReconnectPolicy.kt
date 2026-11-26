package com.ai.playtv.base.reference.ble

import kotlin.random.Random

enum class BleDisconnectCause {
    LINK_LOST, CONNECT_TIMEOUT, GATT_OPERATION_TIMEOUT,
    USER_STOP, PERMISSION_REQUIRED, BLUETOOTH_OFF, PROTOCOL_UNSUPPORTED, AUTH_REJECTED,
}

/**
 * Pure decision only: no scanner, coroutine, timer or automatic connection is started here.
 * Values are injected per device/product. A reconnect never replays the old business command.
 */
class BleReconnectPolicy(
    private val maxAttempts: Int,
    private val initialDelayMs: Long,
    private val maxDelayMs: Long,
) {
    init {
        require(maxAttempts >= 0)
        require(initialDelayMs > 0 && maxDelayMs >= initialDelayMs)
        require(maxDelayMs < Long.MAX_VALUE)
    }

    /**
     * attempt is 1-based, counted by the session owner before scheduling each retry.
     * Return null to stop. A paused/changed owner must cancel its existing retry Job separately.
     * Inject Random for deterministic callers; the default uses equal jitter, bounded by cap.
     */
    fun nextDelayMs(
        cause: BleDisconnectCause,
        attempt: Int,
        wantsConnection: Boolean,
        permissionGranted: Boolean,
        bluetoothEnabled: Boolean,
        random: Random = Random.Default,
    ): Long? {
        require(attempt >= 1)
        if (!wantsConnection || !permissionGranted || !bluetoothEnabled) return null
        if (attempt > maxAttempts) return null
        if (cause !in setOf(
                BleDisconnectCause.LINK_LOST,
                BleDisconnectCause.CONNECT_TIMEOUT,
                BleDisconnectCause.GATT_OPERATION_TIMEOUT,
            )) return null
        var cap = initialDelayMs
        // Saturating arithmetic; work is bounded by the number of doublings to maxDelayMs.
        var remaining = attempt - 1
        while (remaining > 0 && cap < maxDelayMs) {
            cap = if (cap > maxDelayMs / 2) maxDelayMs else minOf(cap * 2, maxDelayMs)
            remaining--
        }
        val floor = maxOf(1L, cap / 2)
        return random.nextLong(floor, cap + 1)
    }
}
