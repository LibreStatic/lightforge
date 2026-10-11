package com.librestatic.lightforge.core.editing.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class AutoStraightenTest {
    /** A soft 2 px line every [period] px, 0..1. */
    private fun line(d: Double, period: Double): Double {
        val offset = ((d % period) + period) % period
        val distance = minOf(offset, period - offset)
        return (1.5 - distance).coerceIn(0.0, 1.0)
    }

    /** A grid whose "horizontal" lines slope clockwise by [degrees]. */
    private fun grid(width: Int, height: Int, degrees: Double): FloatArray {
        val t = degrees * PI / 180.0
        val period = 24.0
        return FloatArray(width * height) {
            val x = (it % width).toDouble()
            val y = (it / width).toDouble()
            val along = x * cos(t) + y * sin(t)
            val across = -x * sin(t) + y * cos(t)
            (1.0 - maxOf(line(across, period), line(along, period))).toFloat()
        }
    }

    @Test
    fun detectsTiltBothWays() {
        for (tilt in listOf(-8.0, -3.0, 3.0, 8.0)) {
            val result = AutoStraighten.detect(grid(400, 300, tilt), 400, 300)
            assertNotNull("tilt $tilt", result)
            assertEquals("tilt $tilt", -tilt.toFloat(), result!!, 0.5f)
        }
    }

    @Test
    fun levelImageNeedsNoCorrection() {
        assertEquals(0f, AutoStraighten.detect(grid(400, 300, 0.0), 400, 300)!!, 0.3f)
    }

    @Test
    fun noiseHasNoDominantTilt() {
        val random = Random(42)
        val noise = FloatArray(400 * 300) { random.nextFloat() }
        assertNull(AutoStraighten.detect(noise, 400, 300))
    }
}
