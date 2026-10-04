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

    @Test
    fun connectedMask_ignoresSameColorRegionsThatAreNotConnected() {
        val red = 0xFFDC2828.toInt()
        val blue = 0xFF2040C8.toInt()
        // 6x1 strip: red red blue red red red -> seeding at x=0 must not reach x=3..5
        val pixels = intArrayOf(red, red, blue, red, red, red)
        val mask = SubjectClipper.connectedMask(pixels, 6, 1, 0, 0, 30)
        assertEquals(listOf(true, true, false, false, false, false), mask.toList())
    }

    @Test
    fun opaqueBounds_coversOnlyNonTransparentPixels() {
        val pixels = IntArray(5 * 4)
        pixels[1 * 5 + 2] = 0xFFFFFFFF.toInt()
        pixels[2 * 5 + 3] = 0xFFFFFFFF.toInt()
        assertEquals(SubjectClipper.PixelBounds(2, 1, 4, 3), SubjectClipper.opaqueBounds(pixels, 5, 4))
        assertEquals(null, SubjectClipper.opaqueBounds(IntArray(4), 2, 2))
    }

    @Test
    fun minSubjectPixels_hasFloorAndScalesWithFrame() {
        assertEquals(16, SubjectClipper.minSubjectPixels(10, 10))
        assertEquals(1000, SubjectClipper.minSubjectPixels(1000, 1000))
    }
}
