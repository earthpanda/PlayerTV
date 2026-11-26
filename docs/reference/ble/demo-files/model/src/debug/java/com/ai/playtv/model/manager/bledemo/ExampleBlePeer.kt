package com.ai.playtv.model.manager.bledemo

import com.ai.playtv.base.bluetooth.ble.frame.BleFrameAssembler
import com.ai.playtv.base.bluetooth.ble.frame.ExampleLengthFrameFormat
import com.ai.playtv.base.bluetooth.ble.transport.*
import com.ai.playtv.model.protocol.ble.example.ExampleBleProtocol
import com.ai.playtv.model.state.bledemo.BleFixtureScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Explicit local peer, not a Bluetooth peripheral. Implements the SAME transport contract as
 * AndroidBleTransport. It deliberately supplies early/duplicate/fragmented replies and old-link
 * callbacks. Delayed callbacks retain their original link, never look up a replacement by address.
 */
internal class ExampleBlePeer(parentScope: CoroutineScope, private val scenario: BleFixtureScenario) : BleTransport, AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var current: Wire? = null
    var opens = 0; private set
    var ignoredLateCallbacks = 0; private set

    override suspend fun open(address: String, profile: BleGattProfile, progress: (BleLinkStage) -> Unit): BleWireConnection {
        currentCoroutineContext().ensureActive()
        check(job.isActive && address == "local-example" && profile == ExampleBleProtocol.PROFILE)
        current?.close()
        progress(BleLinkStage.CONNECTING); delay(40)
        progress(BleLinkStage.DISCOVERING); delay(40)
        progress(BleLinkStage.NEGOTIATING_MTU); delay(40)
        progress(BleLinkStage.SUBSCRIBING); delay(40)
        return Wire().also { current = it; ++opens }
    }
    fun dropCurrent() {
        val old = checkNotNull(current)
        old.drop()
        if (scenario == BleFixtureScenario.STALE_CALLBACK) scope.launch {
            delay(600) // New link and handshake normally complete before this callback arrives.
            old.deliver(byteArrayOf(0x7E))
        }
    }
    suspend fun awaitLateCallback() {
        check(withTimeoutOrNull(2_000) { while (ignoredLateCallbacks == 0) delay(10); true } == true)
    }
    override fun close() { current?.close(); current = null; job.cancel() }

    private inner class Wire : BleWireConnection {
        private val channel = Channel<BleNotification>(16)
        private val framing = ExampleLengthFrameFormat()
        private val assembler = BleFrameAssembler(framing, framing.maxFrameBytes)
        private var open = true
        override val isOpen get() = open
        override val maxWriteBytes = 20 // Exercise protocol fragmentation even with the requested MTU64.
        override val notifications = channel.receiveAsFlow()
        override suspend fun write(packet: ByteArray) {
            currentCoroutineContext().ensureActive()
            check(open && current === this && packet.size in 1..maxWriteBytes)
            val requests = assembler.feed(packet.copyOf())
            for (frame in requests) {
                val body = frame.copyOfRange(3, frame.lastIndex)
                check(body.size >= 3)
                val opcode = body[2].toInt() and 255
                check(opcode == ExampleBleProtocol.HELLO || opcode == ExampleBleProtocol.PING)
                if (opcode == ExampleBleProtocol.PING && scenario == BleFixtureScenario.MISSING_CALLBACK)
                    awaitCancellation() // Accepted write, no local completion. Deadline must retire it.
                val response = framing.encode(byteArrayOf(body[0], body[1], (opcode or 0x80).toByte(), 0) + body.copyOfRange(3, body.size))
                deliver(response.copyOfRange(0, 2))
                // Complete one partial response, followed by its duplicate in the same notification.
                deliver(response.copyOfRange(2, response.size) + response)
            }
            delay(15) // Notification can arrive before the local write callback returns.
            check(open)
        }
        fun deliver(bytes: ByteArray) {
            if (!open || current !== this) { ++ignoredLateCallbacks; return }
            check(channel.trySend(BleNotification(ExampleBleProtocol.RX, bytes.copyOf())).isSuccess)
        }
        override suspend fun read(endpoint: BleEndpoint): ByteArray = throw BleTransportException(BleTransportFault.PROFILE_MISMATCH)
        fun drop() { if (open) { open = false; assembler.clear(); channel.close(BleTransportException(BleTransportFault.LINK_LOST)) } }
        override fun close() { if (open) { open = false; assembler.clear(); channel.close(BleTransportException(BleTransportFault.CLOSED)) } }
    }
}
