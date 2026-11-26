package com.ai.playtv.model.manager.bledemo

import com.ai.playtv.base.bluetooth.ble.retry.BleReconnectPolicy
import com.ai.playtv.model.manager.ble.BleDeviceSessionManager
import com.ai.playtv.model.protocol.ble.BleProtocolException
import com.ai.playtv.model.protocol.ble.BleSessionProblem
import com.ai.playtv.model.protocol.ble.example.ExampleBleCommand
import com.ai.playtv.model.protocol.ble.example.ExampleBleProtocol
import com.ai.playtv.model.state.ble.BleSessionPhase
import com.ai.playtv.model.state.bledemo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/** Calls the production-shaped session interfaces with a LOCAL peer. No radio or real UUID
 * assumptions. The page owns this run; stopping cancels collector, retry, peer and wire together.
 */
class BleProtocolFixture {
    suspend fun run(scenario: BleFixtureScenario, publish: (BleFixtureSnapshot) -> Unit) = coroutineScope {
        val peer = ExampleBlePeer(this, scenario)
        val manager = BleDeviceSessionManager(this, peer, ExampleBleProtocol(),
            BleReconnectPolicy(1, 120, 160), requestTimeoutMs = 600, handshakeTimeoutMs = 1_200)
        var snapshot = BleFixtureSnapshot(busy = true)
        var terminal = false
        fun show(phase: BleFixturePhase) {
            val state = manager.state.value
            snapshot = snapshot.copy(phase = phase, connectionGeneration = state.generation,
                retries = state.retries, validatedFrames = state.validatedFrames,
                ignoredLateCallbacks = peer.ignoredLateCallbacks)
            publish(snapshot)
        }
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.state.collect { state ->
                if (!terminal) show(when (state.phase) {
                    BleSessionPhase.CONNECTING -> BleFixturePhase.CONNECTING
                    BleSessionPhase.INITIALIZING -> BleFixturePhase.SUBSCRIBING
                    BleSessionPhase.HANDSHAKING -> BleFixturePhase.HANDSHAKING
                    BleSessionPhase.WAITING_RETRY -> BleFixturePhase.WAITING_RETRY
                    else -> snapshot.phase
                })
            }
        }
        try {
            manager.connect("local-example")
            check(withTimeoutOrNull(2_000) { manager.state.first { it.phase == BleSessionPhase.READY } } != null)
            if (scenario == BleFixtureScenario.RECONNECT || scenario == BleFixtureScenario.STALE_CALLBACK) {
                val previous = manager.state.value.generation
                peer.dropCurrent()
                check(withTimeoutOrNull(2_000) { manager.state.first { it.phase == BleSessionPhase.READY && it.generation > previous } } != null)
                check(peer.opens == 2 && manager.state.value.retries == 1)
                if (scenario == BleFixtureScenario.STALE_CALLBACK) peer.awaitLateCallback()
            }
            show(BleFixturePhase.WRITING)
            val payload = ByteArray(40) { it.toByte() }.also { it[4] = 0x7E }
            show(BleFixturePhase.WAITING_RESPONSE)
            val reply = manager.execute(ExampleBleCommand.Ping(payload))
            check(reply.payload.contentEquals(payload))
            check(manager.state.value.validatedFrames >= 4 && manager.state.value.ignoredResponses >= 2)
            terminal = true
            snapshot = snapshot.copy(busy = false)
            show(BleFixturePhase.COMPLETED)
        } catch (cancelled: CancellationException) {
            terminal = true
            snapshot = snapshot.copy(busy = false)
            show(BleFixturePhase.CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            terminal = true
            snapshot = snapshot.copy(busy = false)
            val timedOut = failure is BleProtocolException && failure.problem == BleSessionProblem.RESPONSE_TIMEOUT
            show(if (timedOut) BleFixturePhase.TIMED_OUT else BleFixturePhase.FAILED)
        } finally {
            observer.cancel()
            manager.close()
            peer.close()
        }
    }
}
