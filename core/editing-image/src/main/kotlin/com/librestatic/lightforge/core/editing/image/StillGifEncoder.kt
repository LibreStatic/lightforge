package com.librestatic.lightforge.core.editing.image

import java.io.OutputStream

/**
 * Single-frame GIF89a encoder for photo exports.
 *
 * Unlike the fixed RGB332 palette of the creation GIF encoder, this builds an adaptive
 * median-cut palette from a sample of the frame, optionally diffuses the quantisation error
 * (Floyd–Steinberg) and keeps pixels with alpha below 128 transparent. Rows are pulled on demand
 * through [RowSource], so the encoder never copies the whole frame into an int array.
 */
internal object StillGifEncoder {
    /** Fills [row] (length = width) with the unpremultiplied ARGB pixels of row [y]. */
    fun interface RowSource {
        fun read(y: Int, row: IntArray)
    }

    const val MaxSide: Int = 65_535
    private const val MaxSamples: Int = 160_000

    fun encode(
        width: Int,
        height: Int,
        options: GifOutputOptions,
        output: OutputStream,
        checkpoint: () -> Unit = {},
        rows: RowSource,
    ) {
        require(width in 1..MaxSide && height in 1..MaxSide)
        val sample = sample(width, height, rows)
        val transparent = sample.hasTransparency
        val paletteColors = buildPalette(sample.colors, sample.count, if (transparent) options.colors - 1 else options.colors)
        // Index 0 is reserved for transparency so the opaque palette starts at 1.
        val offset = if (transparent) 1 else 0
        val entries = paletteColors.size + offset
        val tableBits = bitsFor(entries)
        val mapper = PaletteMapper(paletteColors)

        fun byte(value: Int) = output.write(value and 0xff)
        fun word(value: Int) { byte(value); byte(value ushr 8) }

        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height)
        byte(0x80 or (7 shl 4) or (tableBits - 1)) // Global colour table, 8-bit resolution.
        byte(0); byte(0)
        for (index in 0 until (1 shl tableBits)) {
            val color = (index - offset).let { if (it in paletteColors.indices) paletteColors[it] else 0 }
            byte(color ushr 16); byte(color ushr 8); byte(color)
        }
        if (transparent) {
            // Graphic control extension: transparent colour flag, index 0.
            output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 1, 0, 0, 0, 0))
        }
        byte(0x2c); word(0); word(0); word(width); word(height); byte(0)
        val minCodeSize = maxOf(2, tableBits)
        byte(minCodeSize)
        val lzw = LzwWriter(output, minCodeSize)

        val row = IntArray(width)
        val dither = options.dither
        // Error buffers for this and the next row, three channels each, with a one pixel margin.
        var current = FloatArray((width + 2) * 3)
        var next = FloatArray((width + 2) * 3)
        for (y in 0 until height) {
            if (y and 31 == 0) checkpoint()
            rows.read(y, row)
            for (x in 0 until width) {
                val argb = row[x]
                if (transparent && (argb ushr 24) < 128) {
                    lzw.write(0)
                    continue
                }
                var r = argb ushr 16 and 0xff
                var g = argb ushr 8 and 0xff
                var b = argb and 0xff
                if (dither) {
                    val e = (x + 1) * 3
                    r = (r + current[e]).toInt().coerceIn(0, 255)
                    g = (g + current[e + 1]).toInt().coerceIn(0, 255)
                    b = (b + current[e + 2]).toInt().coerceIn(0, 255)
                }
                val index = mapper.nearest(r, g, b)
                lzw.write(index + offset)
                if (dither) {
                    val chosen = paletteColors[index]
                    diffuse(current, next, x, r - (chosen ushr 16 and 0xff), g - (chosen ushr 8 and 0xff), b - (chosen and 0xff))
                }
            }
            if (dither) {
                val swap = current
                current = next
                next = swap
                next.fill(0f)
            }
        }
        lzw.finish()
        byte(0) // Block terminator.
        byte(0x3b) // Trailer.
    }

    private fun diffuse(current: FloatArray, next: FloatArray, x: Int, er: Int, eg: Int, eb: Int) {
        fun add(buffer: FloatArray, column: Int, weight: Float) {
            val i = (column + 1) * 3
            buffer[i] += er * weight
            buffer[i + 1] += eg * weight
            buffer[i + 2] += eb * weight
        }
        add(current, x + 1, 7f / 16f)
        add(next, x - 1, 3f / 16f)
        add(next, x, 5f / 16f)
        add(next, x + 1, 1f / 16f)
    }

    private class Sample(val colors: IntArray, val count: Int, val hasTransparency: Boolean)

    /** Up to [MaxSamples] opaque RGB values spread evenly over the frame. */
    private fun sample(width: Int, height: Int, rows: RowSource): Sample {
        val total = width.toLong() * height
        val step = maxOf(1, kotlin.math.sqrt(total.toDouble() / MaxSamples).toInt())
        val colors = IntArray(((width + step - 1) / step) * ((height + step - 1) / step))
        val row = IntArray(width)
        var count = 0
        var transparent = false
        var y = 0
        while (y < height) {
            rows.read(y, row)
            var x = 0
            while (x < width) {
                val argb = row[x]
                if ((argb ushr 24) < 128) transparent = true else colors[count++] = argb and 0xffffff
                x += step
            }
            y += step
        }
        return Sample(colors, count, transparent)
    }

    /**
     * Median cut: repeatedly splits the box with the widest channel range (weighted by its pixel
     * count) at the median, then averages each box. Returns at most [maxColors] RGB colours.
     */
    internal fun buildPalette(colors: IntArray, count: Int, maxColors: Int): IntArray {
        if (count == 0) return intArrayOf(0)
        val pixels = colors.copyOf(count)
        // Boxes are [start, end) ranges of [pixels].
        val starts = IntArray(maxColors)
        val ends = IntArray(maxColors)
        starts[0] = 0
        ends[0] = count
        var boxes = 1
        while (boxes < maxColors) {
            var best = -1
            var bestScore = 0L
            var bestChannel = 0
            for (box in 0 until boxes) {
                val size = ends[box] - starts[box]
                if (size < 2) continue
                val (channel, range) = widestChannel(pixels, starts[box], ends[box])
                val score = range.toLong() * size
                if (range > 0 && score > bestScore) {
                    best = box; bestScore = score; bestChannel = channel
                }
            }
            if (best < 0) break
            val start = starts[best]
            val end = ends[best]
            val shift = 16 - bestChannel * 8
            sortByChannel(pixels, start, end, shift)
            // Split at the value boundary nearest the median so equal values stay in one box. The
            // channel range is non-zero, so at least one boundary exists inside the box.
            val median = (start + end) / 2
            val pivot = pixels[median] ushr shift and 0xff
            var below = median
            while (below > start && (pixels[below - 1] ushr shift and 0xff) == pivot) below--
            var above = median
            while (above < end && (pixels[above] ushr shift and 0xff) == pivot) above++
            val middle = when {
                below == start -> above
                above == end -> below
                median - below <= above - median -> below
                else -> above
            }
            ends[best] = middle
            starts[boxes] = middle
            ends[boxes] = end
            boxes++
        }
        return IntArray(boxes) { box ->
            var r = 0L; var g = 0L; var b = 0L
            for (i in starts[box] until ends[box]) {
                val c = pixels[i]
                r += c ushr 16 and 0xff; g += c ushr 8 and 0xff; b += c and 0xff
            }
            val n = (ends[box] - starts[box]).coerceAtLeast(1)
            ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }.distinct().toIntArray()
    }

    private fun widestChannel(pixels: IntArray, start: Int, end: Int): Pair<Int, Int> {
        var minR = 255; var maxR = 0; var minG = 255; var maxG = 0; var minB = 255; var maxB = 0
        for (i in start until end) {
            val c = pixels[i]
            val r = c ushr 16 and 0xff; val g = c ushr 8 and 0xff; val b = c and 0xff
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        val r = maxR - minR; val g = maxG - minG; val b = maxB - minB
        return when {
            g >= r && g >= b -> 1 to g
            r >= b -> 0 to r
            else -> 2 to b
        }
    }

    /** Counting sort of [start, end) by one 8-bit channel. */
    private fun sortByChannel(pixels: IntArray, start: Int, end: Int, shift: Int) {
        val counts = IntArray(257)
        for (i in start until end) counts[(pixels[i] ushr shift and 0xff) + 1]++
        for (i in 1..256) counts[i] += counts[i - 1]
        val sorted = IntArray(end - start)
        for (i in start until end) {
            val c = pixels[i]
            sorted[counts[c ushr shift and 0xff]++] = c
        }
        sorted.copyInto(pixels, start)
    }

    private fun bitsFor(entries: Int): Int {
        var bits = 1
        while ((1 shl bits) < entries) bits++
        return bits
    }

    /** Nearest palette entry, memoised on a 6-bit-per-channel grid. */
    private class PaletteMapper(private val palette: IntArray) {
        private val cache = IntArray(1 shl 18) { -1 }

        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = (r ushr 2 shl 12) or (g ushr 2 shl 6) or (b ushr 2)
            val cached = cache[key]
            if (cached >= 0) return cached
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in palette.indices) {
                val c = palette[i]
                val dr = r - (c ushr 16 and 0xff)
                val dg = g - (c ushr 8 and 0xff)
                val db = b - (c and 0xff)
                // Weighted towards green, as the eye is most sensitive to it.
                val distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            cache[key] = best
            return best
        }
    }

    /** Variable-width LZW as GIF expects it, packed LSB-first into 255-byte sub-blocks. */
    private class LzwWriter(private val output: OutputStream, private val minCodeSize: Int) {
        private val clearCode = 1 shl minCodeSize
        private val endCode = clearCode + 1
        private var codeSize = minCodeSize + 1
        private var nextCode = endCode + 1
        private var prefix = -1
        // Open addressing table: key = prefix << 8 | index (+1 so 0 means empty) -> code.
        private val keys = IntArray(TableSize)
        private val codes = IntArray(TableSize)
        private val block = ByteArray(255)
        private var blockLength = 0
        private var bitBuffer = 0L
        private var bitCount = 0

        init { emit(clearCode) }

        fun write(index: Int) {
            if (prefix < 0) {
                prefix = index
                return
            }
            val key = (prefix shl 8 or index) + 1
            var slot = (key * -0x61c88647 ushr 18) and (TableSize - 1)
            while (true) {
                val existing = keys[slot]
                if (existing == 0) break
                if (existing == key) {
                    prefix = codes[slot]
                    return
                }
                slot = (slot + 1) and (TableSize - 1)
            }
            emit(prefix)
            if (nextCode == MaxCode) {
                emit(clearCode)
                keys.fill(0)
                nextCode = endCode + 1
                codeSize = minCodeSize + 1
            } else {
                if (nextCode >= (1 shl codeSize)) codeSize++
                keys[slot] = key
                codes[slot] = nextCode++
            }
            prefix = index
        }

        fun finish() {
            if (prefix >= 0) {
                emit(prefix)
                // The decoder adds one more entry after reading this code and may widen the code
                // size before the end code, exactly as it would for the next data code.
                if (nextCode < MaxCode && nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
            }
            emit(endCode)
            if (bitCount > 0) push((bitBuffer and 0xff).toInt())
            flushBlock()
        }

        private fun emit(code: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                push((bitBuffer and 0xff).toInt())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        private fun push(value: Int) {
            block[blockLength++] = value.toByte()
            if (blockLength == block.size) flushBlock()
        }

        private fun flushBlock() {
            if (blockLength == 0) return
            output.write(blockLength)
            output.write(block, 0, blockLength)
            blockLength = 0
        }

        private companion object {
            const val MaxCode = 4_096
            const val TableSize = 1 shl 14
        }
    }
}
