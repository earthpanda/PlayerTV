package com.ai.playtv.base.bluetooth.ble.gatt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Adapted from SonyEarphone BLEGattWriteGate; debug demo reference; see integration guide.
// This is a CHARACTERISTIC WRITE gate, not a complete GATT operation queue.
// The owner must retire the connection on a missing callback deadline.
/**
 * One connection's asynchronous GATT write slot, shared by audio and control commands.
 * A successful submission keeps the slot until its local characteristic-write callback. The
 * synchronous call must also have returned, including when Android delivers the callback early.
 * Waiting suspends a coroutine; no thread or payload lock is held and no media Throttle runs here.
 *
 * Usage: acquire(characteristic.instanceId), invoke GATT once, then call
 * turn.onInvocationReturned(accepted) in finally. Forward characteristic-write callbacks to
 * onWriteCompleted(...), and close() only when retiring this BluetoothGatt connection.
 */
class BleGattWriteGate {
    /** Fair coroutine admission prevents either audio direction from bypassing queued controls. */
    private val available = Semaphore(permits = 1)
    /** At most one granted turn belongs to this connection, independently of audio sessions. */
    private val current = AtomicReference<Turn?>()
    /** Closing releases old waiters without allowing them to submit on a replacement connection. */
    private val closed = AtomicBoolean(false)
    /** A local wake generation, never a wire index; explicit API busy has no callback of its own. */
    private val progress = MutableStateFlow(0L)

    /** Snapshot before an API attempt so a callback racing its busy return cannot be missed. */
    val progressVersion: Long get() = progress.value

    /** Acquire one cancellable turn; retired connections return null and pass the slot onward. */
    suspend fun acquire(characteristicId: Int): Turn? {
        available.acquire()
        val turn = Turn(this, characteristicId)
        current.set(turn)
        if (closed.get()) {
            turn.release()
            return null
        }
        return turn
    }

    /** Only the current characteristic write can release a turn; descriptor callbacks cannot. */
    fun onWriteCompleted(characteristicId: Int) {
        val turn = current.get()
        if (turn != null && turn.characteristicId == characteristicId) turn.onCallback()
        onExternalOperationCompleted()
    }

    /** Descriptor completion can wake a busy retry, but cannot release a characteristic's slot. */
    fun onExternalOperationCompleted() {
        progress.update { it + 1L }
    }

    /**
     * Prefer a completion wake; use a bounded asynchronous backoff when the platform rejects a
     * request without issuing a callback. No deadline or audio-expiration policy is introduced.
     */
    suspend fun awaitProgress(previousVersion: Long, fallbackWaitMs: Long) {
        withTimeoutOrNull(fallbackWaitMs) {
            progress.first { it != previousVersion || closed.get() }
        }
    }

    /** Wake pending old-connection admissions; an already submitted operation is never retried. */
    fun close() {
        closed.set(true)
        current.get()?.release()
        onExternalOperationCompleted()
    }

    /** Release exactly once, including races between close, API return and an early callback. */
    private fun release(turn: Turn) {
        if (current.compareAndSet(turn, null)) available.release()
    }

    /**
     * A slot reservation, not a protocol sequence or a diagnostic ID. Its two completion bits
     * prevent callback reentrancy from mutating the old-API Characteristic during invocation.
     * Usage: retain this turn only across the synchronous GATT call, then report its result.
     */
    class Turn internal constructor(
        /** Original connection's gate; it never resolves a newer global GATT. */
        private val gate: BleGattWriteGate,
        /** Android characteristic instance handle expected in the local completion callback. */
        val characteristicId: Int
    ) {
        /** Returned/callback bits change atomically; releasing ownership is separately idempotent. */
        private val completed = AtomicInteger()

        /** An immediate rejection/exception needs no callback; accepted writes retain their turn. */
        fun onInvocationReturned(accepted: Boolean) {
            complete(if (accepted) INVOCATION_RETURNED else (INVOCATION_RETURNED or CALLBACK_RECEIVED))
        }

        /** Remember even a callback arriving before the synchronous API has returned. */
        internal fun onCallback() = complete(CALLBACK_RECEIVED)

        /** Connection retirement can wake all waiters even when Android never completes its write. */
        internal fun release() = gate.release(this)

        /** No callback timing or diagnostic counter participates in this ownership decision. */
        private fun complete(bits: Int) {
            while (true) {
                val previous = completed.get()
                val next = previous or bits
                if (next == previous) return
                if (completed.compareAndSet(previous, next)) {
                    if (next == (INVOCATION_RETURNED or CALLBACK_RECEIVED)) release()
                    return
                }
            }
        }

        private companion object {
            /** Both events are required only for a submission accepted by the Android API. */
            const val INVOCATION_RETURNED = 1
            const val CALLBACK_RECEIVED = 2
        }
    }
}
