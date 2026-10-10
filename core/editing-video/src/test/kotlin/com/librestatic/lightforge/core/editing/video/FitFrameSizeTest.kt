package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Test

class FitFrameSizeTest {
    @Test
    fun keepsTheSourceSizeWhenItFits() {
        assertEquals(1080 to 2520, fitFrameSize(1080, 2520, 2520))
        assertEquals(3840 to 2160, fitFrameSize(3840, 2160, 3840))
    }

    @Test
    fun neverUpscales() {
        assertEquals(1280 to 720, fitFrameSize(1280, 720, 3840))
    }

    @Test
    fun scalesTheLongEdgeAndKeepsBothSidesEven() {
        assertEquals(1080 to 1920, fitFrameSize(2160, 3840, 1920))
        assertEquals(822 to 1920, fitFrameSize(1080, 2520, 1920))
        assertEquals(1280 to 548, fitFrameSize(2520, 1080, 1280))
    }
}
