package com.librestatic.lightforge.feature.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFilmstripLoaderTest {
    @Test
    fun progressiveOrderCoversTheStripCoarseToFine() {
        assertEquals(listOf(0, 7, 4, 2, 6, 1, 3, 5), VideoFilmstripLoader.progressiveOrder(8))
        assertEquals(listOf(0), VideoFilmstripLoader.progressiveOrder(1))
        assertEquals(emptyList<Int>(), VideoFilmstripLoader.progressiveOrder(0))
        // Every slot appears exactly once whatever the count.
        (1..13).forEach { count ->
            assertEquals((0 until count).toList(), VideoFilmstripLoader.progressiveOrder(count).sorted())
        }
    }

    @Test
    fun slotsSampleTheirCentreInsideTheClip() {
        assertEquals(1_000_000L, VideoFilmstripLoader.slotTimeMicros(0, 8, 16_000))
        assertEquals(15_000_000L, VideoFilmstripLoader.slotTimeMicros(7, 8, 16_000))
        assertEquals(0L, VideoFilmstripLoader.slotTimeMicros(0, 1, 1))
    }
}
