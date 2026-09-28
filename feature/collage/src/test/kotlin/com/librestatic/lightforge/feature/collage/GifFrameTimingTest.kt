package com.librestatic.lightforge.feature.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifFrameTimingTest {
    @Test
    fun `legacy seconds codes keep their meaning`() {
        assertTrue((1..5).all(GifFrameTiming::isValid))
        assertEquals(List(3) { 2000 }, GifFrameTiming.delaysMillis(2, 3))
    }

    @Test
    fun `frame rates that divide 100 use a constant delay`() {
        assertEquals(List(4) { 40 }, GifFrameTiming.delaysMillis(GifFrameTiming.fps(25), 4))
        assertEquals(List(2) { 200 }, GifFrameTiming.delaysMillis(GifFrameTiming.fps(5), 2))
    }

    @Test
    fun `other frame rates keep their exact average`() {
        // 24 frames at 24 FPS last exactly one second; 30 frames at 30 FPS too.
        assertEquals(1000, GifFrameTiming.totalMillis(GifFrameTiming.fps(24), 24))
        assertEquals(1000, GifFrameTiming.totalMillis(GifFrameTiming.fps(30), 30))
        assertEquals(2000, GifFrameTiming.totalMillis(GifFrameTiming.fps(15), 30))
        GifFrameTiming.FpsChoices.forEach { fps ->
            assertTrue(GifFrameTiming.delaysMillis(GifFrameTiming.fps(fps), 60).all { it >= 20 && it % 10 == 0 })
        }
    }

    @Test
    fun `rates beyond what GIF plays reliably are capped honestly`() {
        assertEquals(50, GifFrameTiming.effectiveFps(GifFrameTiming.fps(120)))
        assertEquals(List(3) { 20 }, GifFrameTiming.delaysMillis(GifFrameTiming.fps(60), 3))
    }

    @Test
    fun `unknown codes are rejected`() {
        listOf(0, 6, 999, 1000, GifFrameTiming.fps(12), GifFrameTiming.fps(240)).forEach { assertFalse(GifFrameTiming.isValid(it)) }
    }
}
