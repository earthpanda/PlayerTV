package com.ai.playtv.base.bluetooth.ble.frame

/**
 * Documentation fixture, NOT Sony or any supported device protocol.
 * Wire frame: 0x7E | payload length u16 big endian | payload | XOR(length bytes + payload).
 * The marker may occur in payload; a delimiter-only split would corrupt it.
 * XOR illustrates integrity validation only, and provides no authentication or security.
 */
class ExampleLengthFrameFormat(private val maxPayloadBytes: Int = 256) : BleFrameFormat {
    init { require(maxPayloadBytes in 0..65_535) }
    override val maxFrameBytes: Int = maxPayloadBytes + 4

    override fun inspect(bytes: ByteArray, offset: Int, size: Int): BleFrameProbe {
        if (bytes[offset] != 0x7E.toByte()) return BleFrameProbe.Discard(1)
        if (size < 3) return BleFrameProbe.NeedMore
        val length = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
        if (length > maxPayloadBytes) return BleFrameProbe.Discard(1)
        val frameBytes = length + 4
        if (size < frameBytes) return BleFrameProbe.NeedMore
        var checksum = 0
        for (i in offset + 1 until offset + frameBytes - 1) {
            checksum = checksum xor (bytes[i].toInt() and 0xFF)
        }
        return if (checksum == (bytes[offset + frameBytes - 1].toInt() and 0xFF)) {
            BleFrameProbe.Complete(frameBytes)
        } else BleFrameProbe.Discard(1)
    }

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= maxPayloadBytes)
        val frame = ByteArray(payload.size + 4)
        frame[0] = 0x7E
        frame[1] = (payload.size ushr 8).toByte()
        frame[2] = payload.size.toByte()
        payload.copyInto(frame, 3)
        var checksum = 0
        for (i in 1 until frame.lastIndex) checksum = checksum xor (frame[i].toInt() and 0xFF)
        frame[frame.lastIndex] = checksum.toByte()
        return frame
    }
}

/** Concrete local use: one split frame followed by two frames in the same notification. */
fun exampleFragmentAssembly(): List<ByteArray> {
    val format = ExampleLengthFrameFormat()
    val assembler = BleFrameAssembler(format, maxBufferedBytes = format.maxFrameBytes)
    val frame = format.encode(byteArrayOf(0x01, 0x7E, 0x02))
    val frames = mutableListOf<ByteArray>()
    frames += assembler.feed(frame.copyOfRange(0, 2))
    frames += assembler.feed(frame.copyOfRange(2, frame.size) + frame + frame)
    assembler.clear()
    return frames // Three wire frames; production decoding belongs to that device's adapter.
}
