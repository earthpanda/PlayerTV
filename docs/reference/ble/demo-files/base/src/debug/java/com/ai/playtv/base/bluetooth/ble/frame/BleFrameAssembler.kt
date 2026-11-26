package com.ai.playtv.base.bluetooth.ble.frame

/** A protocol inspects only [offset, offset + size); it owns framing and integrity checks. */
interface BleFrameFormat {
    /** Maximum complete wire frame, including headers, escaping and checksum. */
    val maxFrameBytes: Int
    fun inspect(bytes: ByteArray, offset: Int, size: Int): BleFrameProbe
}

sealed interface BleFrameProbe {
    data object NeedMore : BleFrameProbe
    /** Invalid prefix; protocol decides how many bytes may safely be discarded. */
    data class Discard(val byteCount: Int) : BleFrameProbe
    /** A complete, validated frame; not merely a plausible length field. */
    data class Complete(val byteCount: Int) : BleFrameProbe
}

class BleFrameBufferOverflowException : IllegalStateException("BLE receive buffer exhausted")

/**
 * Per connection and per ordered characteristic stream. Call from one serial owner only.
 * Handles fragmented/coalesced notifications without knowing UUIDs, marker or length offsets.
 * Pending bytes are bounded; callers must also bound notification/event and decoded-frame queues.
 * Protocol/format errors clear pending bytes and propagate; the session chooses recovery.
 */
class BleFrameAssembler(
    private val format: BleFrameFormat,
    private val maxBufferedBytes: Int,
) {
    init {
        require(format.maxFrameBytes > 0)
        require(maxBufferedBytes >= format.maxFrameBytes)
    }

    private val buffer = ByteArray(maxBufferedBytes)
    private var used = 0
    val pendingByteCount: Int get() = used

    /** Returns owned frame arrays; never retains or mutates the caller's notification array. */
    fun feed(fragment: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        try {
            var consumed = 0
            while (consumed < fragment.size) {
                drain(frames)
                val room = maxBufferedBytes - used
                if (room == 0) throw BleFrameBufferOverflowException()
                val count = minOf(room, fragment.size - consumed)
                fragment.copyInto(buffer, used, consumed, consumed + count)
                used += count
                consumed += count
            }
            drain(frames)
            return frames
        } catch (failure: Exception) {
            clear()
            throw failure
        }
    }

    fun clear() { used = 0 }

    private fun drain(frames: MutableList<ByteArray>) {
        var offset = 0
        while (offset < used) {
            val remaining = used - offset
            when (val probe = format.inspect(buffer, offset, remaining)) {
                BleFrameProbe.NeedMore -> {
                    // At a whole maximum-sized frame the format must complete or discard.
                    check(remaining < format.maxFrameBytes) { "Format exceeded maxFrameBytes" }
                    break
                }
                is BleFrameProbe.Discard -> {
                    check(probe.byteCount in 1..remaining) { "Format made invalid progress" }
                    offset += probe.byteCount
                }
                is BleFrameProbe.Complete -> {
                    check(probe.byteCount in 1..minOf(remaining, format.maxFrameBytes))
                    frames += buffer.copyOfRange(offset, offset + probe.byteCount)
                    offset += probe.byteCount
                }
            }
        }
        if (offset > 0) {
            buffer.copyInto(buffer, 0, offset, used)
            used -= offset
        }
    }
}
