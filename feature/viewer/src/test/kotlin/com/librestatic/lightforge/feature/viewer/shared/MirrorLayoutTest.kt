package com.librestatic.lightforge.feature.viewer.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorLayoutTest {
    private fun transform(matrix: FloatArray, x: Float, y: Float) =
        (matrix[0] * x + matrix[4] * y) to (matrix[1] * x + matrix[5] * y)

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-4f)
        assertEquals(expected.second, actual.second, 1e-4f)
    }

    @Test
    fun matchingAspectFillsTheOutput() {
        val m = MirrorLayout.quadMatrix(1000, 500, 1920f, 960f, 0)
        assertPoint(1f to 1f, transform(m, 1f, 1f))
        assertPoint(-1f to -1f, transform(m, -1f, -1f))
    }

    @Test
    fun wideVideoOnSquareOutputIsLetterboxed() {
        val m = MirrorLayout.quadMatrix(1000, 1000, 1600f, 900f, 0)
        assertPoint(1f to 0.5625f, transform(m, 1f, 1f))
    }

    @Test
    fun rotation180FlipsBothAxes() {
        val m = MirrorLayout.quadMatrix(800, 600, 800f, 600f, 180)
        assertPoint(-1f to -1f, transform(m, 1f, 1f))
        assertPoint(1f to -1f, transform(m, -1f, 1f))
    }

    @Test
    fun rotation90IsClockwiseAndSwapsTheFitAxes() {
        // 1600x900 video turned a quarter: 900x1600 on a 900x1600 output fills it exactly.
        val m = MirrorLayout.quadMatrix(900, 1600, 1600f, 900f, 90)
        // Top-right of the frame ends up at bottom-right.
        assertPoint(1f to -1f, transform(m, 1f, 1f))
        // Top-left ends up at top-right.
        assertPoint(1f to 1f, transform(m, -1f, 1f))
    }

    @Test
    fun rotation270IsCounterClockwise() {
        val m = MirrorLayout.quadMatrix(900, 1600, 1600f, 900f, 270)
        assertPoint(-1f to 1f, transform(m, 1f, 1f))
        // Top-left ends up at bottom-left.
        assertPoint(-1f to -1f, transform(m, -1f, 1f))
    }

    @Test
    fun rotationWrapsAndNegativeAnglesAreAccepted() {
        val a = MirrorLayout.quadMatrix(640, 480, 640f, 480f, -90)
        val b = MirrorLayout.quadMatrix(640, 480, 640f, 480f, 270)
        val c = MirrorLayout.quadMatrix(640, 480, 640f, 480f, 630)
        assertEquals(a.toList(), b.toList())
        assertEquals(b.toList(), c.toList())
    }

    @Test
    fun portraitVideoOnLandscapeOutputIsPillarboxed() {
        val m = MirrorLayout.quadMatrix(1600, 900, 900f, 1600f, 0)
        assertPoint(0.31640625f to 1f, transform(m, 1f, 1f))
    }
}
