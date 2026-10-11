package com.librestatic.lightforge.core.editing.image.grade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLocalBaseTest {
    @Test
    fun constantImageGivesConstantBase() {
        val grey = 0xFF808080.toInt()
        val expected = PhotoGradeMath.lin(128 / 255f)
        for ((w, h) in listOf(100 to 60, 40 to 300, 1000 to 700)) {
            val base = PhotoLocalBase.from(IntArray(w * h) { grey }, w, h)
            assertTrue(maxOf(base.width, base.height) <= 256)
            for ((u, v) in listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f, 0.13f to 0.9f)) {
                assertEquals(expected, base.sample(u, v), 1e-4f)
            }
        }
    }

    @Test
    fun blurSpreadsAnEdgeAndSamplingIsBilinear() {
        val w = 64
        val h = 8
        val pixels = IntArray(w * h) { if (it % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val base = PhotoLocalBase.from(pixels, w, h)
        assertEquals(0f, base.sample(0.05f, 0.5f), 1e-3f)
        assertEquals(1f, base.sample(0.95f, 0.5f), 1e-3f)
        assertEquals(0.5f, base.sample(0.5f, 0.5f), 1e-3f)
        val a = base.sample(0.46f, 0.5f)
        val b = base.sample(0.54f, 0.5f)
        assertTrue(a in 0.05f..0.45f && b in 0.55f..0.95f)
    }
}
