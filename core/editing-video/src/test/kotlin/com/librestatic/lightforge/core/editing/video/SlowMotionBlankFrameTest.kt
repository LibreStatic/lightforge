package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowMotionBlankFrameTest {
    @Test
    fun nearBlackFrameBetweenBrightNeighboursIsBlank() {
        assertTrue(isBlank(frameLuma = 0.4f, neighbourLuma = 120f))
    }

    @Test
    fun darkSceneIsNotFlaggedBecauseItsNeighboursAreDarkToo() {
        assertFalse(isBlank(frameLuma = 1f, neighbourLuma = 2f))
    }

    @Test
    fun normalIntermediateFrameIsNotBlank() {
        assertFalse(isBlank(frameLuma = 90f, neighbourLuma = 120f))
    }
}
