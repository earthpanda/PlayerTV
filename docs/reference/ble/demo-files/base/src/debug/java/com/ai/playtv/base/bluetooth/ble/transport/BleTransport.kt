package com.ai.playtv.base.bluetooth.ble.transport

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Unique endpoint within one discovered GATT. Duplicate service/characteristic UUIDs are rejected. */
data class BleEndpoint(val service: UUID, val characteristic: UUID)
enum class BleSubscriptionMode { NOTIFY, INDICATE }
data class BleSubscription(val endpoint: BleEndpoint, val mode: BleSubscriptionMode)
enum class BleWriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }
data class BleGattProfile(
    val writeEndpoint: BleEndpoint,
    val subscriptions: List<BleSubscription>,
    val readableEndpoints: List<BleEndpoint> = emptyList(),
    val writeMode: BleWriteMode = BleWriteMode.WITH_RESPONSE,
    val requestedMtu: Int? = null,
    val minimumMtu: Int = 23,
    val allowMtuFallback: Boolean = true,
    val maxWriteBytes: Int = 20,
) {
    init {
        require(subscriptions.isNotEmpty())
        require(subscriptions.map { it.endpoint }.distinct().size == subscriptions.size)
        require(minimumMtu in 23..517 && (requestedMtu == null || requestedMtu in minimumMtu..517))
        require(maxWriteBytes in 1..512)
    }
}
enum class BleLinkStage { CONNECTING, DISCOVERING, NEGOTIATING_MTU, SUBSCRIBING }
enum class BleTransportFault { LINK_LOST, CONNECT_TIMEOUT, OPERATION_TIMEOUT, PERMISSION, BLUETOOTH_OFF, PROFILE_MISMATCH, REJECTED, CALLBACK_FAILED, OVERFLOW, CLOSED }
class BleTransportException(val fault: BleTransportFault, cause: Throwable? = null) :
    Exception("BLE transport: $fault", cause)
data class BleNotification(val endpoint: BleEndpoint, val bytes: ByteArray)

/**
 * One connection; exactly one notification collector. Arrays delivered to callers are owned copies.
 * write/read return after local GATT completion, never after a protocol ACK. Packets are NOT split
 * here: the protocol adapter decides fragmentation using maxWriteBytes. Main-thread API.
 */
interface BleWireConnection : AutoCloseable {
    val notifications: Flow<BleNotification>
    val maxWriteBytes: Int
    val isOpen: Boolean
    suspend fun write(packet: ByteArray)
    suspend fun read(endpoint: BleEndpoint): ByteArray
    override fun close()
}

/** Ready-to-use wire means discovery, requested MTU policy and all required CCCDs completed.
 * It does not mean protocol handshake/authorization succeeded. open must close partial resources
 * on failure/cancellation. Implementations publish progress on the same serial owner thread.
 */
fun interface BleTransport {
    suspend fun open(address: String, profile: BleGattProfile, progress: (BleLinkStage) -> Unit): BleWireConnection
}
