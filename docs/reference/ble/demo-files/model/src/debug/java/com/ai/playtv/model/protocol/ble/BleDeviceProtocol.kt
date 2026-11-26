package com.ai.playtv.model.protocol.ble

import com.ai.playtv.base.bluetooth.ble.frame.BleFrameFormat
import com.ai.playtv.base.bluetooth.ble.transport.BleEndpoint
import com.ai.playtv.base.bluetooth.ble.transport.BleGattProfile

/** Device-specific command and negotiated device information; no Android/SDK types in this API. */
interface BleDeviceCommand
interface BleDeviceInfo { val protocolName: String }
data class BleResponseKey(val requestId: Int, val responseCode: Int)
data class BleEncodedRequest(val key: BleResponseKey, val packets: List<ByteArray>)
sealed interface BleProtocolMessage {
    data class Response(val key: BleResponseKey, val accepted: Boolean, val payload: ByteArray) : BleProtocolMessage
    data class Event(val topic: String, val payload: ByteArray) : BleProtocolMessage
}
fun interface BleCommandExchange {
    suspend fun execute(command: BleDeviceCommand): BleProtocolMessage.Response
}

/** One codec per connection: no sequence, crypto or assembly state leaks across reconnections. */
interface BleProtocolSession {
    /** IDs are never reused within this connection in the reference broker. */
    val requestIds: IntRange
    fun frameFormat(endpoint: BleEndpoint): BleFrameFormat
    fun encode(requestId: Int, command: BleDeviceCommand, maxWriteBytes: Int): BleEncodedRequest
    fun decode(endpoint: BleEndpoint, frame: ByteArray): BleProtocolMessage
    suspend fun handshake(exchange: BleCommandExchange): BleDeviceInfo
}
interface BleDeviceProtocol {
    val profile: BleGattProfile
    fun newSession(): BleProtocolSession
}

enum class BleSessionProblem { NOT_READY, BUSY, PROTOCOL, RESPONSE_TIMEOUT, REQUEST_IDS_EXHAUSTED, COMMAND_REJECTED, EVENT_OVERFLOW, TRANSPORT, CLOSED }
class BleProtocolException(val problem: BleSessionProblem, cause: Throwable? = null) :
    Exception("BLE protocol: $problem", cause)
