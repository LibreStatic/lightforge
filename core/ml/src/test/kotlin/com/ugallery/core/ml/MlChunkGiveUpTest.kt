package com.ugallery.core.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class MlChunkGiveUpTest {
    @Test
    fun `attempts below the cap keep retrying`() {
        assertEquals(
            listOf(false, false, false, false, false),
            (0 until MaxChunkRunAttempts).map { shouldGiveUp(it) },
        )
    }

    @Test
    fun `the cap and anything beyond it gives up`() {
        assertEquals(true, shouldGiveUp(MaxChunkRunAttempts))
        assertEquals(true, shouldGiveUp(MaxChunkRunAttempts + 1))
        assertEquals(true, shouldGiveUp(50))
    }

    @Test
    fun `cap is five attempts`() {
        assertEquals(5, MaxChunkRunAttempts)
    }
}
