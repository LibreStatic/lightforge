package com.ugallery.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewPolicyTest {
    @Test
    fun declaredPerformanceClassUsesContinuousProfile() {
        val profile = RawPreviewPolicy.select(RawPreviewHardware(31, 4, 4L shl 30, false))
        assertTrue(profile.continuous)
        assertEquals(1_024, profile.maxDimension)
    }

    @Test
    fun balancedFallbackAcceptsEightCoresAndSixGib() {
        val profile = RawPreviewPolicy.select(RawPreviewHardware(0, 8, 6L shl 30, false))
        assertTrue(profile.continuous)
    }

    @Test
    fun ordinaryAndLowRamDevicesUseProgressiveProfiles() {
        assertEquals(640, RawPreviewPolicy.select(RawPreviewHardware(0, 6, 4L shl 30, false)).maxDimension)
        assertEquals(512, RawPreviewPolicy.select(RawPreviewHardware(35, 8, 12L shl 30, true)).maxDimension)
    }
}
