package com.librestatic.lightforge.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class HoldSlowMotionInterpolationFactorTest {
    @Test
    fun factorKeepsOutputNearThirtyFramesPerSecondAtQuarterSpeed() {
        assertEquals(8, interpolationFactor(67L)) // 15 fps CCTV
        assertEquals(8, interpolationFactor(1_000L)) // dropped frames never exceed the model limit
        assertEquals(4, interpolationFactor(33L)) // 30 fps
        assertEquals(4, interpolationFactor(40L)) // 25 fps
        assertEquals(2, interpolationFactor(17L)) // 60 fps
        assertEquals(1, interpolationFactor(8L)) // 120 fps needs no synthesis
    }
}
