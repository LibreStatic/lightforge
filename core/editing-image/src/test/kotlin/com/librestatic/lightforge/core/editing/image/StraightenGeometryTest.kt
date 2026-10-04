package com.librestatic.lightforge.core.editing.image

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightenGeometryTest {
    @Test
    fun noTiltKeepsTheFrame() {
        assertEquals(1600 to 1000, StraightenGeometry.inscribedSize(1600, 1000, 0f))
    }

    @Test
    fun tiltKeepsTheAspectRatio() {
        listOf(2f, 6f, -6f, 15f, 30f, 45f).forEach { degrees ->
            val (w, h) = StraightenGeometry.inscribedSize(703, 935, degrees)
            assertTrue("$degrees: ${w}x$h", abs(w / h.toDouble() - 703 / 935.0) < 0.01)
            assertTrue(w < 703 && h < 935)
        }
    }

    @Test
    fun resultFitsInsideTheTiltedFrame() {
        val degrees = 10f
        val (w, h) = StraightenGeometry.inscribedSize(1600, 1000, degrees)
        val r = Math.toRadians(degrees.toDouble())
        assertTrue(w * Math.cos(r) + h * Math.sin(r) <= 1600 + 1)
        assertTrue(w * Math.sin(r) + h * Math.cos(r) <= 1000 + 1)
    }
}
