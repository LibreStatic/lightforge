package com.ugallery.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDurationBadgeTest {
    @Test fun `unknown and non-positive durations use a neutral placeholder`() {
        assertEquals("--:--", formatVideoDuration(0L))
        assertEquals("--:--", formatVideoDuration(-1L))
    }

    @Test fun `sub-hour durations use minutes and zero-padded seconds without rounding`() {
        assertEquals("0:18", formatVideoDuration(18_999L))
        assertEquals("1:04", formatVideoDuration(64_000L))
        assertEquals("59:59", formatVideoDuration(3_599_999L))
    }

    @Test fun `hour-long durations include hours and zero-padded minutes`() {
        assertEquals("1:00:02", formatVideoDuration(3_602_000L))
        assertEquals("12:34:56", formatVideoDuration(45_296_000L))
    }
}
