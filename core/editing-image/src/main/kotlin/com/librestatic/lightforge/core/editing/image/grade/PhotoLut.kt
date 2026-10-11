package com.librestatic.lightforge.core.editing.image.grade

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An [size]³ RGB colour lookup table over encoded sRGB 0..1, red index fastest. [data] holds
 * `size³ * 3` floats. Sampling is trilinear over the grid, which is exactly what a linear-filtered
 * 3D texture addressed with `p * (N - 1) / N + 0.5 / N` returns.
 */
class PhotoLut(val size: Int, val data: FloatArray) {
    init {
        require(size in 2..MaxSize) { "Unsupported LUT size $size" }
        require(data.size == size * size * size * 3) { "LUT data does not match size $size" }
    }

    /** Trilinear lookup of the encoded colour ([r], [g], [b]) into [out] (3 floats). */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = size - 1
        val fr = r.coerceIn(0f, 1f) * max
        val fg = g.coerceIn(0f, 1f) * max
        val fb = b.coerceIn(0f, 1f) * max
        val r0 = fr.toInt().coerceAtMost(max - 1)
        val g0 = fg.toInt().coerceAtMost(max - 1)
        val b0 = fb.toInt().coerceAtMost(max - 1)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        var outR = 0f
        var outG = 0f
        var outB = 0f
        for (db in 0..1) for (dg in 0..1) for (dr in 0..1) {
            val weight = (if (dr == 1) tr else 1f - tr) *
                (if (dg == 1) tg else 1f - tg) *
                (if (db == 1) tb else 1f - tb)
            if (weight == 0f) continue
            val i = (((b0 + db) * size + (g0 + dg)) * size + (r0 + dr)) * 3
            outR += data[i] * weight
            outG += data[i + 1] * weight
            outB += data[i + 2] * weight
        }
        out[0] = outR
        out[1] = outG
        out[2] = outB
    }

    fun sample(r: Float, g: Float, b: Float): FloatArray = FloatArray(3).also { sample(r, g, b, it) }

    /** Writes `LFLUT1`, the int size and float16 RGB triplets, all little-endian. */
    fun write(output: OutputStream) {
        val buffer = ByteBuffer.allocate(Magic.size + 4 + data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(Magic)
        buffer.putInt(size)
        data.forEach { buffer.putShort(floatToHalf(it)) }
        output.write(buffer.array())
    }

    fun toByteArray(): ByteArray = java.io.ByteArrayOutputStream().also { write(it) }.toByteArray()

    companion object {
        const val DefaultSize = 33
        private const val MaxSize = 65
        private val Magic = "LFLUT1".toByteArray(Charsets.US_ASCII)

        fun identity(n: Int = DefaultSize): PhotoLut {
            val data = FloatArray(n * n * n * 3)
            var i = 0
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                data[i++] = r / (n - 1f)
                data[i++] = g / (n - 1f)
                data[i++] = b / (n - 1f)
            }
            return PhotoLut(n, data)
        }

        /** Reads a LUT written by [write]; throws [java.io.IOException] on a malformed stream. */
        fun read(input: InputStream): PhotoLut {
            val stream = DataInputStream(input)
            val magic = ByteArray(Magic.size)
            stream.readFully(magic)
            if (!magic.contentEquals(Magic)) throw java.io.IOException("Not a LUT file")
            val header = ByteArray(4).also { stream.readFully(it) }
            val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            if (size !in 2..MaxSize) throw java.io.IOException("Unsupported LUT size $size")
            val raw = ByteArray(size * size * size * 3 * 2).also { stream.readFully(it) }
            val halves = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return PhotoLut(size, FloatArray(raw.size / 2) { halfToFloat(halves.short) })
        }

        fun fromByteArray(bytes: ByteArray): PhotoLut = read(bytes.inputStream())

        internal fun floatToHalf(value: Float): Short {
            val bits = java.lang.Float.floatToRawIntBits(value)
            val sign = (bits ushr 16) and 0x8000
            val absBits = bits and 0x7fffffff
            if (absBits >= 0x7f800000) return (sign or if (absBits > 0x7f800000) 0x7e00 else 0x7c00).toShort()
            if (absBits >= 0x477ff000) return (sign or 0x7c00).toShort() // rounds to infinity
            if (absBits < 0x38800000) { // subnormal half or zero
                if (absBits < 0x33000000) return sign.toShort()
                val shift = 126 - (absBits ushr 23)
                val mantissa = (absBits and 0x7fffff) or 0x800000
                var half = mantissa ushr shift
                val remainder = mantissa and ((1 shl shift) - 1)
                val halfway = 1 shl (shift - 1)
                if (remainder > halfway || (remainder == halfway && (half and 1) == 1)) half++
                return (sign or half).toShort()
            }
            var half = ((absBits - 0x38000000) ushr 13)
            val remainder = absBits and 0x1fff
            if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }

        internal fun halfToFloat(half: Short): Float {
            val bits = half.toInt() and 0xffff
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits ushr 10) and 0x1f
            val mantissa = bits and 0x3ff
            val result = when {
                exponent == 0 -> {
                    if (mantissa == 0) return java.lang.Float.intBitsToFloat(sign)
                    val magnitude = mantissa * (1f / 16_777_216f) // 2^-24
                    return if (sign != 0) -magnitude else magnitude
                }
                exponent == 31 -> sign or 0x7f800000 or (mantissa shl 13)
                else -> sign or ((exponent + 112) shl 23) or (mantissa shl 13)
            }
            return java.lang.Float.intBitsToFloat(result)
        }
    }
}
