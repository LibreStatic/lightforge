package com.librestatic.lightforge

import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationEngine
import com.librestatic.lightforge.core.preferences.FrameInterpolationEngine as PreferredEngine
import org.junit.Test
import org.junit.Assert.assertEquals

class FrameInterpolationEngineMappingTest {
    @Test
    fun everyPreferenceMapsToTheInterpolationEngineOfTheSameName() {
        assertEquals(
            PreferredEngine.entries.map { it.name },
            PreferredEngine.entries.map { it.toInterpolationEngine().name },
        )
        assertEquals(FrameInterpolationEngine.Dsp, PreferredEngine.Dsp.toInterpolationEngine())
        assertEquals(FrameInterpolationEngine.Gpu, PreferredEngine.Gpu.toInterpolationEngine())
        assertEquals(FrameInterpolationEngine.Automatic, PreferredEngine.Automatic.toInterpolationEngine())
    }

    @Test
    fun bothEnumsDeclareTheSameValues() {
        assertEquals(
            PreferredEngine.entries.map { it.name }.toSet(),
            FrameInterpolationEngine.entries.map { it.name }.toSet(),
        )
    }
}
