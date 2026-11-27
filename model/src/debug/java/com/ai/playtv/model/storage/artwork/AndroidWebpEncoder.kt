package com.ai.playtv.model.storage.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.math.min

/** Static JPEG/PNG compression; existing WebP, animation and oriented JPEG remain unchanged. */
class AndroidWebpEncoder(
    private val smallMaxWidthPx: Int,
    private val smallMaxHeightPx: Int,
    private val backgroundMaxWidthPx: Int,
    private val backgroundMaxHeightPx: Int,
    private val backgroundQuality: Int,
) : ArtworkImageEncoder {
    init {
        require(smallMaxWidthPx > 0 && smallMaxHeightPx > 0 && backgroundMaxWidthPx > 0 && backgroundMaxHeightPx > 0)
        require(backgroundQuality in 0..100)
    }

    override fun encode(input: File, output: File, slot: ArtworkSlot): Boolean {
        val header = ByteArray(12)
        val headerSize = input.inputStream().use { it.read(header) }
        val jpeg = headerSize >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
        val png = headerSize >= 8 && header.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        if (!jpeg && !png) return false
        if (png && isAnimatedPng(input)) return false
        if (jpeg) {
            val orientation = ExifInterface(input.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            if (orientation != ExifInterface.ORIENTATION_UNDEFINED && orientation != ExifInterface.ORIENTATION_NORMAL) return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            throw IOException("Invalid or excessive artwork dimensions")
        }
        val maxWidth = if (slot == ArtworkSlot.SMALL) smallMaxWidthPx else backgroundMaxWidthPx
        val maxHeight = if (slot == ArtworkSlot.SMALL) smallMaxHeightPx else backgroundMaxHeightPx
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2L || bounds.outHeight / sample > maxHeight * 2L) sample *= 2
        val bitmap = BitmapFactory.decodeFile(input.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Artwork decoding failed")
        var resized: Bitmap? = null
        try {
            val scale = min(1.0, min(maxWidth.toDouble() / bitmap.width, maxHeight.toDouble() / bitmap.height))
            val chosen = if (scale < 1.0) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { resized = it }
            } else bitmap
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) {
                if (slot == ArtworkSlot.SMALL) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            } else Bitmap.CompressFormat.WEBP
            val quality = if (slot == ArtworkSlot.SMALL) 100 else backgroundQuality
            output.outputStream().use { stream ->
                if (!chosen.compress(format, quality, stream)) throw IOException("WebP encoding failed")
                stream.fd.sync()
            }
            // Retain a smaller original rather than increasing storage for no benefit.
            return output.length() < input.length()
        } finally {
            resized?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }

    private fun isAnimatedPng(file: File): Boolean = DataInputStream(file.inputStream().buffered()).use { stream ->
        stream.readFully(ByteArray(8))
        while (true) {
            val length = try { stream.readInt() } catch (_: EOFException) { throw IOException("Incomplete PNG") }
            if (length < 0 || length.toLong() > file.length()) throw IOException("Invalid PNG chunk")
            val kind = ByteArray(4); stream.readFully(kind)
            val type = String(kind, Charsets.US_ASCII)
            if (type == "acTL") return@use true
            if (type == "IEND") return@use false
            var remaining = length.toLong() + 4
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) remaining -= skipped else if (stream.read() < 0) throw IOException("Incomplete PNG") else remaining--
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    private companion object { const val MAX_PIXELS = 100_000_000L }
}
