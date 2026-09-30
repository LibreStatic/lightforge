package com.librestatic.lightforge.feature.subjectclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for color distance calculation.
 * The colorDistance function uses pure math (no android.graphics.Color),
 * so these tests run without Robolectric.
 */
class SubjectClipperTest {

    @Test
    fun colorDistance_sameColor_isZero() {
        val dist = SubjectClipper.colorDistance(0xFF0000.toInt(), 0xFF0000.toInt())
        assertEquals(0, dist)
    }

    @Test
    fun colorDistance_blackAndWhite_isMax() {
        val dist = SubjectClipper.colorDistance(0x000000, 0xFFFFFF)
        assertTrue("Should be 441 for black-white, got " + dist, dist in 440..442)
    }

    @Test
    fun colorDistance_redAndGreen_isExpected() {
        val dist = SubjectClipper.colorDistance(0xFF0000, 0x00FF00)
        assertTrue("Should be around 360, got " + dist, dist in 359..362)
    }
}
