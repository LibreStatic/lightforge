package com.librestatic.lightforge.feature.motionphotos

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoFrameSizeTest {
    @Test
    fun smallVideoIsNotUpscaled() {
        assertEquals(720 to 960, scaledFrameSize(720, 960, 0, 4096))
    }

    @Test
    fun largeVideoIsBoundedPreservingAspect() {
        assertEquals(2048 to 1024, scaledFrameSize(4096, 2048, 0, 2048))
    }

    @Test
    fun quarterTurnSwapsAxes() {
        assertEquals(960 to 720, scaledFrameSize(720, 960, 90, 4096))
        assertEquals(720 to 960, scaledFrameSize(720, 960, 180, 4096))
    }

    @Test
    fun missingMetadataFallsBackToBound() {
        assertEquals(1600 to 1600, scaledFrameSize(null, 100, 0, 1600))
    }
}
