package com.librestatic.lightforge.core.editing.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class StillGifEncoderTest {
    @Test
    fun fewColoursRoundTripExactly() {
        val colors = intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff, 0x000000, 0x808080)
        val width = 37
        val height = 23
        val pixels = IntArray(width * height) { (0xff shl 24) or colors[(it / 5 + it % 7) % colors.size] }
        val decoded = decode(encode(width, height, pixels, GifOutputOptions(colors = 16, dither = false)))
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertArrayEquals(pixels.map { it and 0xffffff }.toIntArray(), decoded.rgb)
    }

    @Test
    fun largeGradientStaysCloseAndExercisesCodeTableResets() {
        val width = 640
        val height = 480
        val pixels = IntArray(width * height) {
            val x = it % width
            val y = it / width
            (0xff shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or ((x + y) % 256)
        }
        listOf(true, false).forEach { dither ->
            val decoded = decode(encode(width, height, pixels, GifOutputOptions(colors = 256, dither = dither)))
            assertTrue(decoded.paletteSize <= 256)
            var error = 0L
            pixels.indices.forEach { i ->
                val a = pixels[i]
                val b = decoded.rgb[i]
                error += abs((a ushr 16 and 0xff) - (b ushr 16 and 0xff)) +
                    abs((a ushr 8 and 0xff) - (b ushr 8 and 0xff)) + abs((a and 0xff) - (b and 0xff))
            }
            val meanPerChannel = error.toDouble() / (pixels.size * 3)
            assertTrue("mean error $meanPerChannel (dither=$dither)", meanPerChannel < 12.0)
        }
    }

    @Test
    fun paletteNeverExceedsTheRequestedColours() {
        val width = 128
        val height = 64
        val pixels = IntArray(width * height) { (0xff shl 24) or (it * 2654435761L).toInt().and(0xffffff) }
        val decoded = decode(encode(width, height, pixels, GifOutputOptions(colors = 16, dither = true)))
        assertEquals(16, decoded.paletteSize)
        assertTrue(decoded.rgb.toSet().size <= 16)
    }

    @Test
    fun lowAlphaPixelsBecomeTransparent() {
        val width = 8
        val height = 4
        val pixels = IntArray(width * height) { if (it % 2 == 0) 0x00000000 else 0xff336699.toInt() }
        val decoded = decode(encode(width, height, pixels, GifOutputOptions(colors = 4, dither = false)))
        val transparent = decoded.transparentIndex
        assertEquals(0, transparent)
        pixels.indices.forEach { i ->
            if (i % 2 == 0) assertEquals(transparent, decoded.indices[i]) else assertEquals(0x336699, decoded.rgb[i])
        }
    }

    @Test
    fun medianCutSplitsDistinctClusters() {
        val colors = IntArray(300) { if (it < 100) 0x100000 else if (it < 200) 0x001000 else 0x000010 }
        val palette = StillGifEncoder.buildPalette(colors, colors.size, 3)
        assertEquals(setOf(0x100000, 0x001000, 0x000010), palette.toSet())
    }

    private fun encode(width: Int, height: Int, pixels: IntArray, options: GifOutputOptions): ByteArray =
        ByteArrayOutputStream().also { out ->
            StillGifEncoder.encode(width, height, options, out) { y, row -> pixels.copyInto(row, 0, y * width, (y + 1) * width) }
        }.toByteArray()

    private class Decoded(
        val width: Int,
        val height: Int,
        val indices: IntArray,
        val rgb: IntArray,
        val paletteSize: Int,
        val transparentIndex: Int?,
    )

    /** Minimal single-frame GIF decoder: enough to verify the encoder's container and LZW stream. */
    private fun decode(bytes: ByteArray): Decoded {
        var p = 0
        fun u8() = bytes[p++].toInt() and 0xff
        fun u16() = u8() or (u8() shl 8)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        p = 6
        val width = u16()
        val height = u16()
        val packed = u8()
        u8(); u8()
        val tableSize = 1 shl ((packed and 7) + 1)
        val palette = IntArray(tableSize) { (u8() shl 16) or (u8() shl 8) or u8() }
        var transparent: Int? = null
        while (bytes[p].toInt() and 0xff == 0x21) {
            p++
            val label = u8()
            val size = u8()
            if (label == 0xf9) {
                val flags = u8(); u16(); val index = u8()
                if (flags and 1 == 1) transparent = index
                p += size - 4
            } else p += size
            assertEquals(0, u8())
        }
        assertEquals(0x2c, u8())
        u16(); u16(); assertEquals(width, u16()); assertEquals(height, u16()); u8()
        val minCodeSize = u8()
        val data = ByteArrayOutputStream()
        while (true) {
            val length = u8()
            if (length == 0) break
            data.write(bytes, p, length)
            p += length
        }
        assertEquals(0x3b, u8())
        val stream = data.toByteArray()
        val indices = IntArray(width * height)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefixes = IntArray(4096)
        val suffixes = IntArray(4096)
        val lengths = IntArray(4096)
        for (i in 0 until clear) { suffixes[i] = i; lengths[i] = 1 }
        var codeSize = minCodeSize + 1
        var next = end + 1
        var previous = -1
        var bitPos = 0
        var out = 0
        val scratch = IntArray(4096)
        fun readCode(): Int {
            var code = 0
            for (bit in 0 until codeSize) {
                val byte = stream[(bitPos + bit) ushr 3].toInt() and 0xff
                code = code or (((byte ushr ((bitPos + bit) and 7)) and 1) shl bit)
            }
            bitPos += codeSize
            return code
        }
        fun expand(code: Int): Int {
            var c = code
            val length = lengths[c]
            for (i in length - 1 downTo 0) { scratch[i] = suffixes[c]; c = prefixes[c] }
            return length
        }
        while (true) {
            val code = readCode()
            if (code == clear) {
                codeSize = minCodeSize + 1; next = end + 1; previous = -1
                continue
            }
            if (code == end) break
            val length: Int
            if (previous < 0) {
                length = expand(code)
            } else {
                if (code < next) {
                    length = expand(code)
                    prefixes[next] = previous; suffixes[next] = scratch[0]; lengths[next] = lengths[previous] + 1
                } else {
                    assertEquals(next, code)
                    val first = expand(previous).let { scratch[0] }
                    prefixes[next] = previous; suffixes[next] = first; lengths[next] = lengths[previous] + 1
                    length = expand(next)
                }
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            for (i in 0 until length) indices[out++] = scratch[i]
            previous = code
        }
        assertEquals(width * height, out)
        val used = indices.filter { it != transparent }.toSet()
        return Decoded(
            width, height, indices,
            IntArray(indices.size) { palette[indices[it]] },
            paletteSize = used.size,
            transparentIndex = transparent,
        )
    }
}
