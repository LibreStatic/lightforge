package com.librestatic.lightforge.core.frameinterpolation

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a Qualcomm cDSP; skipped elsewhere. Results go to logcat with the `LIGHTFORGE_HVX` tag. */
@RunWith(AndroidJUnit4::class)
class HvxInterpolationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assumeDsp() = assumeTrue(runBlocking { FrameInterpolationEngines.isDspAvailable(context) })

    @Test
    fun dspOutputMatchesTheGpuOutput() {
        assumeDsp()
        val highA = texturedBitmap(1920, 1080, 0f)
        val highB = texturedBitmap(1920, 1080, 12f)
        val lowA = Bitmap.createScaledBitmap(highA, 512, 288, true)
        val lowB = Bitmap.createScaledBitmap(highB, 512, 288, true)
        RifeFrameInterpolator(context, engine = FrameInterpolationEngine.Dsp).use { dsp ->
            assertEquals(FrameInterpolationBackend.Hvx, dsp.capability.backend)
            assertTrue(dsp.capability.supportsGuided)
            RifeFrameInterpolator(context, engine = FrameInterpolationEngine.Gpu).use { gpu ->
                assumeTrue(gpu.capability.backend == FrameInterpolationBackend.Vulkan)
                listOf(0.25f, 0.5f, 0.75f).forEach { t ->
                    val dspFrame = dsp.interpolateGuided(lowA, lowB, highA, highB, t)
                    val gpuFrame = gpu.interpolateGuided(lowA, lowB, highA, highB, t)
                    val mad = meanAbsDiff(dspFrame, gpuFrame, margin = 32)
                    println("LIGHTFORGE_HVX t=$t dspVsGpuMeanAbsDiff=$mad blank=${isBlank(dspFrame)}")
                    assertTrue("DSP frame is blank at t=$t", !isBlank(dspFrame))
                    assertTrue("DSP differs from GPU at t=$t: $mad", mad < 6.0)
                    dspFrame.recycle(); gpuFrame.recycle()
                }
                println("LIGHTFORGE_HVX timing dsp=${timeMillis(dsp, lowA, lowB, highA, highB)} ms " +
                    "gpu=${timeMillis(gpu, lowA, lowB, highA, highB)} ms per guided frame")
            }
        }
        listOf(highA, highB, lowA, lowB).forEach(Bitmap::recycle)
    }

    @Test
    fun plainInterpolationAndOtherNetworkSizesWorkOnTheDsp() {
        assumeDsp()
        RifeFrameInterpolator(context, engine = FrameInterpolationEngine.Dsp).use { dsp ->
            val a = texturedBitmap(1280, 720, 0f)
            val b = texturedBitmap(1280, 720, 8f)
            val plain = dsp.interpolate(a, b, 0.5f)
            assertEquals(1280, plain.width)
            assertTrue(!isBlank(plain))
            // 320x180 low-res frames resample to a 320x192 network, a different size than the 512x288 default.
            val lowA = Bitmap.createScaledBitmap(a, 320, 180, true)
            val lowB = Bitmap.createScaledBitmap(b, 320, 180, true)
            val guided = dsp.interpolateGuided(lowA, lowB, a, b, 0.5f)
            assertTrue(!isBlank(guided))
            println("LIGHTFORGE_HVX plainVsGuidedMeanAbsDiff=${meanAbsDiff(plain, guided, margin = 32)}")
            listOf(a, b, plain, lowA, lowB, guided).forEach(Bitmap::recycle)
        }
    }

    @Test
    fun parallelTimestepsAreSerialisedOnTheDsp() {
        assumeDsp()
        RifeFrameInterpolator(context, engine = FrameInterpolationEngine.Dsp).use { dsp ->
            val a = texturedBitmap(640, 360, 0f)
            val b = texturedBitmap(640, 360, 6f)
            val frames = dsp.interpolateFactorGuided(a, b, a, b, 8)
            assertEquals(7, frames.size)
            frames.forEach { assertTrue(!isBlank(it)); it.recycle() }
            a.recycle(); b.recycle()
        }
    }

    private fun timeMillis(interpolator: RifeFrameInterpolator, lowA: Bitmap, lowB: Bitmap, highA: Bitmap, highB: Bitmap): Double {
        interpolator.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle()
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(5) { interpolator.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle() }
        return (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / 5
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val first = pixels[0]
        return pixels.all { it == first }
    }
}
