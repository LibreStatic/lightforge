package com.ugallery.feature.collage

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Local-only GIF encoder using minimal GIF89a format with LZW compression.
 * No cloud, no network — all encoding is on-device.
 */
object GifEncoder {

    data class GifFrame(
        val bitmap: Bitmap,
        val delayMs: Int,
    )

    fun encode(frames: List<GifFrame>, output: OutputStream) {
        require(frames.isNotEmpty()) { "At least one frame required" }
        val writer = Gif89aWriter(output)
        writer.writeHeader()
        val first = frames[0].bitmap
        writer.writeLogicalScreenDescriptor(first.width, first.height)
        writer.writeNetscapeExtension()
        for (frame in frames) {
            writer.writeFrame(frame.bitmap, frame.delayMs)
        }
        writer.writeTrailer()
        writer.flush()
    }
}

private class Gif89aWriter(private val out: OutputStream) {

    fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    fun writeLogicalScreenDescriptor(width: Int, height: Int) {
        out.write16(width)
        out.write16(height)
        out.write(0x70)
        out.write(0)
        out.write(0)
    }

    fun writeNetscapeExtension() {
        out.write(0x21); out.write(0xFF); out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03); out.write(0x01); out.write16(0); out.write(0x00)
    }

    fun writeFrame(bitmap: Bitmap, delayMs: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val delayCentiSeconds = (delayMs / 10).coerceIn(1, 65535)

        out.write(0x2C)
        out.write16(0); out.write16(0)
        out.write16(width); out.write16(height)
        out.write(0x80)

        val palette = buildPalette()
        val indexed = indexBitmap(bitmap, palette)

        for (color in palette) {
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }

        out.write(0x21); out.write(0xF9); out.write(0x04)
        out.write(0x00)
        out.write16(delayCentiSeconds)
        out.write(0); out.write(0x00)

        val minCodeSize = 8
        out.write(minCodeSize)
        val compressed = lzwCompress(indexed, minCodeSize)
        writeDataBlocks(compressed)
    }

    fun writeTrailer() { out.write(0x3B) }
    fun flush() { out.flush() }

    private fun buildPalette(): IntArray {
        val palette = IntArray(256)
        var idx = 0
        for (r in 0..5) for (g in 0..6) for (b in 0..5) {
            if (idx < 256) palette[idx++] = (r * 51 shl 16) or (g * 36 shl 8) or (b * 51)
        }
        while (idx < 256) palette[idx++] = 0
        return palette
    }

    private fun indexBitmap(bitmap: Bitmap, palette: IntArray): ByteArray {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val indexed = ByteArray(w * h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val ri = ((px shr 16) and 0xFF) / 51
            val gi = ((px shr 8) and 0xFF) / 36
            val bi = (px and 0xFF) / 51
            indexed[i] = (ri * 42 + gi * 6 + bi).toByte()
        }
        return indexed
    }

    private fun lzwCompress(data: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        val initialCodeSize = minCodeSize + 1
        val baos = java.io.ByteArrayOutputStream()
        val bw = BitWriter(baos)

        var dict = HashMap<List<Byte>, Int>(4096)
        var nextCode = endCode + 1
        var codeSize = initialCodeSize

        for (i in 0 until clearCode) dict[listOf(i.toByte())] = i
        bw.writeBits(clearCode, codeSize)

        var current = listOf(data[0])
        for (pos in 1 until data.size) {
            val candidate = current + data[pos]
            if (dict.containsKey(candidate)) {
                current = candidate
            } else {
                bw.writeBits(dict[current]!!, codeSize)
                if (nextCode < 4096) {
                    dict[candidate] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    bw.writeBits(clearCode, codeSize)
                    dict.clear()
                    for (i in 0 until clearCode) dict[listOf(i.toByte())] = i
                    nextCode = endCode + 1
                    codeSize = initialCodeSize
                }
                current = listOf(data[pos])
            }
        }
        bw.writeBits(dict[current]!!, codeSize)
        bw.writeBits(endCode, codeSize)
        bw.flush()
        return baos.toByteArray()
    }

    private fun writeDataBlocks(data: ByteArray) {
        var pos = 0
        while (pos < data.size) {
            val blockLen = minOf(255, data.size - pos)
            out.write(blockLen)
            out.write(data, pos, blockLen)
            pos += blockLen
        }
        out.write(0)
    }

    private fun OutputStream.write16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}

private class BitWriter(private val out: java.io.OutputStream) {
    private var buffer = 0
    private var bitsInBuffer = 0

    fun writeBits(value: Int, numBits: Int) {
        buffer = (buffer shl numBits) or (value and ((1 shl numBits) - 1))
        bitsInBuffer += numBits
        while (bitsInBuffer >= 8) {
            out.write((buffer ushr (bitsInBuffer - 8)) and 0xFF)
            bitsInBuffer -= 8
        }
    }

    fun flush() {
        if (bitsInBuffer > 0) {
            out.write((buffer shl (8 - bitsInBuffer)) and 0xFF)
            buffer = 0
            bitsInBuffer = 0
        }
    }
}

