package com.librestatic.lightforge.feature.collage

import android.graphics.Bitmap
import java.io.OutputStream

/** Streaming GIF89a with a deterministic RGB332 palette. No retained bitmap list is required. */
object GifEncoder {
    data class GifFrame(val bitmap: Bitmap, val delayMs: Int)

    fun encode(frames: List<GifFrame>, output: OutputStream) {
        require(frames.isNotEmpty())
        encode(frames.first().bitmap.width, frames.first().bitmap.height, frames.size, output, frames::get)
    }

    fun encode(width: Int, height: Int, frameCount: Int, output: OutputStream,
        frameAt: (Int) -> GifFrame, checkpoint: () -> Unit = {}) {
        require(width in 1..4096 && height in 1..4096 && frameCount in 1..600)
        fun byte(value: Int) = output.write(value)
        fun word(value: Int) { byte(value and 255); byte(value ushr 8 and 255) }
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height); byte(0xf7); byte(0); byte(0)
        repeat(256) { index ->
            byte((index ushr 5) * 255 / 7)
            byte((index ushr 2 and 7) * 255 / 7)
            byte((index and 3) * 255 / 3)
        }
        output.write(byteArrayOf(0x21, 0xff.toByte(), 11))
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(3, 1, 0, 0, 0)) // Infinite loop.
        repeat(frameCount) { index ->
            checkpoint()
            val frame = frameAt(index)
            require(frame.bitmap.width == width && frame.bitmap.height == height)
            require(frame.delayMs in 10..655350)
            output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)) // GCE before the image; keep previous frame.
            word(frame.delayMs / 10); byte(0); byte(0)
            byte(0x2c); word(0); word(0); word(width); word(height); byte(0)
            byte(8) // LZW minimum code size.
            val blocks = Blocks(output)
            var bits = 0
            var bitCount = 0
            fun code(value: Int) {
                bits = bits or (value shl bitCount) // GIF LZW codes are least-significant-bit first.
                bitCount += 9
                while (bitCount >= 8) { blocks.write(bits and 255); bits = bits ushr 8; bitCount -= 8 }
            }
            // Bounded literal-LZW runs deliberately clear before the dictionary reaches 512.
            // This avoids a large dictionary and keeps encoding memory O(width), not O(all frames).
            code(256)
            var literals = 0
            val row = IntArray(width)
            repeat(height) { y ->
                checkpoint()
                frame.bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (pixel in row) {
                    if (literals == 254) { code(256); literals = 0 }
                    val r = ((pixel ushr 16 and 255) * 7 + 127) / 255
                    val g = ((pixel ushr 8 and 255) * 7 + 127) / 255
                    val b = ((pixel and 255) * 3 + 127) / 255
                    code((r shl 5) or (g shl 2) or b)
                    literals++
                }
            }
            code(257)
            if (bitCount > 0) blocks.write(bits and 255)
            blocks.finish()
        }
        byte(0x3b)
        output.flush()
    }

    private class Blocks(private val output: OutputStream) {
        private val bytes = ByteArray(255)
        private var size = 0
        fun write(value: Int) { bytes[size++] = value.toByte(); if (size == bytes.size) flush() }
        private fun flush() { if (size > 0) { output.write(size); output.write(bytes, 0, size); size = 0 } }
        fun finish() { flush(); output.write(0) }
    }
}
