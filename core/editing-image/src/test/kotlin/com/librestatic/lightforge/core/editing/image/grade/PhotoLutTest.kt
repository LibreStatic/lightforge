package com.librestatic.lightforge.core.editing.image.grade

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PhotoLutTest {
    @Test
    fun binaryRoundTripKeepsHalfPrecision() {
        val random = Random(1)
        val lut = PhotoLut(5, FloatArray(5 * 5 * 5 * 3) { random.nextFloat() })
        val back = PhotoLut.fromByteArray(lut.toByteArray())
        assertEquals(5, back.size)
        lut.data.indices.forEach { assertEquals(lut.data[it], back.data[it], 0.0006f) }
        assertArrayEquals(back.toByteArray(), PhotoLut.fromByteArray(back.toByteArray()).toByteArray())
    }

    @Test
    fun halfConversionHandlesSpecialValues() {
        listOf(0f, 1f, 0.5f, 0.333f, 1e-6f, 65000f).forEach {
            val back = PhotoLut.halfToFloat(PhotoLut.floatToHalf(it))
            assertEquals(it, back, maxOf(abs(it) * 0.001f, 1e-7f))
        }
    }

    @Test
    fun trilinearIsExactAtGridPointsAndLinearBetween() {
        val n = 9
        val random = Random(2)
        val lut = PhotoLut(n, FloatArray(n * n * n * 3) { random.nextFloat() })
        val out = FloatArray(3)
        for ((r, g, b) in listOf(Triple(0, 0, 0), Triple(8, 8, 8), Triple(3, 5, 7), Triple(8, 0, 4))) {
            lut.sample(r / 8f, g / 8f, b / 8f, out)
            val i = ((b * n + g) * n + r) * 3
            assertEquals(lut.data[i], out[0], 1e-6f)
            assertEquals(lut.data[i + 1], out[1], 1e-6f)
            assertEquals(lut.data[i + 2], out[2], 1e-6f)
        }
        val a = lut.sample(2 / 8f, 3 / 8f, 4 / 8f)
        val b = lut.sample(3 / 8f, 3 / 8f, 4 / 8f)
        val mid = lut.sample(2.5f / 8f, 3 / 8f, 4 / 8f)
        for (c in 0..2) assertEquals((a[c] + b[c]) / 2f, mid[c], 1e-5f)
    }

    @Test
    fun identityLutReturnsItsInput() {
        val lut = PhotoLut.identity()
        val random = Random(3)
        repeat(100) {
            val r = random.nextFloat()
            val g = random.nextFloat()
            val b = random.nextFloat()
            val out = lut.sample(r, g, b)
            assertEquals(r, out[0], 1e-5f)
            assertEquals(g, out[1], 1e-5f)
            assertEquals(b, out[2], 1e-5f)
        }
    }
}
