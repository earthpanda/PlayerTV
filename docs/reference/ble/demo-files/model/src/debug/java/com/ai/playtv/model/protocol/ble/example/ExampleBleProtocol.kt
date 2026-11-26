package com.ai.playtv.model.protocol.ble.example

import java.util.UUID
import com.ai.playtv.base.bluetooth.ble.frame.BleFrameFormat
import com.ai.playtv.base.bluetooth.ble.frame.ExampleLengthFrameFormat
import com.ai.playtv.base.bluetooth.ble.transport.BleEndpoint
import com.ai.playtv.base.bluetooth.ble.transport.BleGattProfile
import com.ai.playtv.base.bluetooth.ble.transport.BleSubscription
import com.ai.playtv.base.bluetooth.ble.transport.BleSubscriptionMode
import com.ai.playtv.model.protocol.ble.*

sealed interface ExampleBleCommand : BleDeviceCommand {
    data object Hello : ExampleBleCommand
    data class Ping(val payload: ByteArray) : ExampleBleCommand
}
data class ExampleBleInfo(val version: Int) : BleDeviceInfo {
    override val protocolName = "example-length-v1"
}

/** Teaching protocol with a local peer. These UUIDs/commands do NOT describe a real product.
 * Frame: existing length+XOR format; request payload: id:u16 | opcode:u8 | body.
 * Response payload: id:u16 | responseOpcode:u8 | status:u8 | body. Status zero means accepted.
 */
class ExampleBleProtocol : BleDeviceProtocol {
    companion object {
        private val SERVICE = UUID.fromString("f0620001-663f-4b83-a6e4-713c71f40301")
        val TX = BleEndpoint(SERVICE, UUID.fromString("f0620002-663f-4b83-a6e4-713c71f40301"))
        val RX = BleEndpoint(SERVICE, UUID.fromString("f0620003-663f-4b83-a6e4-713c71f40301"))
        val PROFILE = BleGattProfile(TX, listOf(BleSubscription(RX, BleSubscriptionMode.NOTIFY)),
            requestedMtu = 64, maxWriteBytes = 60)
        const val HELLO = 1
        const val PING = 2
    }
    override val profile = PROFILE
    override fun newSession(): BleProtocolSession = Session()

    private class Session : BleProtocolSession {
        private val framing = ExampleLengthFrameFormat()
        override val requestIds = 1..65_535
        override fun frameFormat(endpoint: BleEndpoint): BleFrameFormat {
            require(endpoint == RX)
            return ExampleLengthFrameFormat()
        }
        override fun encode(requestId: Int, command: BleDeviceCommand, maxWriteBytes: Int): BleEncodedRequest {
            require(requestId in requestIds && maxWriteBytes > 0)
            val opcode: Int
            val body: ByteArray
            when (command) {
                ExampleBleCommand.Hello -> { opcode = HELLO; body = byteArrayOf(1) }
                is ExampleBleCommand.Ping -> { opcode = PING; body = command.payload.copyOf() }
                else -> throw BleProtocolException(BleSessionProblem.PROTOCOL)
            }
            val bytes = framing.encode(byteArrayOf((requestId ushr 8).toByte(), requestId.toByte(), opcode.toByte()) + body)
            // This example permits arbitrary byte-stream fragmentation; real protocols may not.
            val packets = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + maxWriteBytes)
                packets += bytes.copyOfRange(offset, end)
                offset = end
            }
            return BleEncodedRequest(BleResponseKey(requestId, opcode or 0x80), packets)
        }
        override fun decode(endpoint: BleEndpoint, frame: ByteArray): BleProtocolMessage {
            if (endpoint != RX || frame.size < 8) throw BleProtocolException(BleSessionProblem.PROTOCOL)
            val payload = frame.copyOfRange(3, frame.lastIndex) // Framing already validated size/XOR.
            val id = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
            val opcode = payload[2].toInt() and 255
            if (opcode !in setOf(HELLO or 0x80, PING or 0x80)) throw BleProtocolException(BleSessionProblem.PROTOCOL)
            return BleProtocolMessage.Response(BleResponseKey(id, opcode), payload[3] == 0.toByte(), payload.copyOfRange(4, payload.size))
        }
        override suspend fun handshake(exchange: BleCommandExchange): BleDeviceInfo {
            val reply = exchange.execute(ExampleBleCommand.Hello)
            if (!reply.payload.contentEquals(byteArrayOf(1))) throw BleProtocolException(BleSessionProblem.PROTOCOL)
            return ExampleBleInfo(version = 1)
        }
    }
}
