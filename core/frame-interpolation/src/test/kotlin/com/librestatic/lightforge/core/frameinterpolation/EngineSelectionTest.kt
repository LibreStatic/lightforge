package com.librestatic.lightforge.core.frameinterpolation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineSelectionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun choose(
        engine: FrameInterpolationEngine,
        hvx: Boolean,
        vulkan: Boolean,
        accelerated: Boolean = true,
    ) = EngineSelection.choose(engine, accelerated, hvx, vulkan)

    @Test
    fun networkExtentRoundsToNearestMultipleOf32WithAMinimum() {
        assertEquals(32, EngineSelection.networkExtent(2))
        assertEquals(32, EngineSelection.networkExtent(40))
        assertEquals(64, EngineSelection.networkExtent(48))
        assertEquals(288, EngineSelection.networkExtent(288))
        assertEquals(288, EngineSelection.networkExtent(282))
        assertEquals(512, EngineSelection.networkExtent(512))
        assertEquals(512, EngineSelection.networkExtent(500))
        assertEquals(384, EngineSelection.networkExtent(380))
    }

    @Test
    fun lowResolutionIsEvenAndCappedAtTheLongEdge() {
        assertEquals(512 to 288, EngineSelection.lowResolution(1920, 1080))
        assertEquals(288 to 512, EngineSelection.lowResolution(1080, 1920))
        assertEquals(320 to 180, EngineSelection.lowResolution(320, 180))
        assertEquals(2 to 2, EngineSelection.lowResolution(1, 1))
    }

    @Test
    fun cpuIsForcedWhenAccelerationIsDisabled() {
        FrameInterpolationEngine.entries.forEach { engine ->
            assertEquals(BackendChoice.UseCpu, choose(engine, hvx = true, vulkan = true, accelerated = false))
        }
    }

    @Test
    fun gpuPreferenceNeverUsesTheDsp() {
        assertEquals(BackendChoice.UseVulkan, choose(FrameInterpolationEngine.Gpu, hvx = true, vulkan = true))
        assertEquals(BackendChoice.UseCpu, choose(FrameInterpolationEngine.Gpu, hvx = true, vulkan = false))
    }

    @Test
    fun dspPreferenceFallsBackToGpuThenCpu() {
        assertEquals(BackendChoice.UseHvx, choose(FrameInterpolationEngine.Dsp, hvx = true, vulkan = true))
        assertEquals(BackendChoice.UseHvx, choose(FrameInterpolationEngine.Dsp, hvx = true, vulkan = false))
        assertEquals(BackendChoice.UseVulkan, choose(FrameInterpolationEngine.Dsp, hvx = false, vulkan = true))
        assertEquals(BackendChoice.UseCpu, choose(FrameInterpolationEngine.Dsp, hvx = false, vulkan = false))
    }

    @Test
    fun automaticPrefersTheDspThenTheGpuThenTheCpu() {
        val auto = FrameInterpolationEngine.Automatic
        assertEquals(BackendChoice.UseHvx, choose(auto, hvx = true, vulkan = true))
        assertEquals(BackendChoice.UseHvx, choose(auto, hvx = true, vulkan = false))
        assertEquals(BackendChoice.UseVulkan, choose(auto, hvx = false, vulkan = true))
        assertEquals(BackendChoice.UseCpu, choose(auto, hvx = false, vulkan = false))
    }

    @Test
    fun cacheKeysDependOnTheDeviceAndTheLibraryVersion() {
        assertNotEquals(EngineCacheKeys.dsp("a/b:1"), EngineCacheKeys.dsp("a/b:2"))
        assertEquals(true, EngineCacheKeys.dsp("a/b:1").endsWith(EngineCacheKeys.LibraryVersion))
    }

    @Test
    fun cachePersistsValuesAcrossInstancesAndSurvivesCorruption() {
        val file = File(folder.root, "hvx/engine-cache.properties")
        val key = EngineCacheKeys.dsp("google/pixel:14/AP1A.240305.019/release-keys")
        assertNull(EngineCache(file).get(key))
        EngineCache(file).put(key, "true")
        EngineCache(file).put(EngineCacheKeys.dsp("x"), "true")
        assertEquals("true", EngineCache(file).get(key))
        assertEquals("true", EngineCache(file).get(EngineCacheKeys.dsp("x")))
        file.writeBytes(byteArrayOf(0x5c, 0x75, 0x00)) // truncated unicode escape
        assertNull(EngineCache(file).get(key))
        EngineCache(file).put(key, "false")
        assertEquals("false", EngineCache(file).get(key))
    }
}
