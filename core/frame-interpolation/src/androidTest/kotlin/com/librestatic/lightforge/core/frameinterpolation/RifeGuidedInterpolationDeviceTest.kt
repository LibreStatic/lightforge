package com.librestatic.lightforge.core.frameinterpolation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Results are printed to logcat with the `LIGHTFORGE_GUIDED` tag. */
@RunWith(AndroidJUnit4::class)
class RifeGuidedInterpolationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun guidedMatchesPlainInterpolationAtEqualSize() {
        RifeFrameInterpolator(context).use { interpolator ->
            assumeTrue(interpolator.capability.backend == FrameInterpolationBackend.Vulkan)
            val a = texturedBitmap(320, 180, 0f)
            val b = texturedBitmap(320, 180, 4f)
            listOf(0.25f, 0.5f, 0.75f).forEach { t ->
                val plain = interpolator.interpolate(a, b, t)
                val guided = interpolator.interpolateGuided(a, b, a, b, t)
                assertEquals(320, guided.width)
                val mad = meanAbsDiff(plain, guided)
                println("LIGHTFORGE_GUIDED equalSize t=$t meanAbsDiff=$mad")
                assertTrue("guided differs from plain at t=$t: $mad", mad < 2.0)
                plain.recycle(); guided.recycle()
            }
            // The concurrent factor helper must return the same frames, in order.
            val plainFrames = interpolator.interpolateFactor(a, b, 4)
            val guidedFrames = interpolator.interpolateFactorGuided(a, b, a, b, 4)
            assertEquals(3, guidedFrames.size)
            plainFrames.zip(guidedFrames).forEachIndexed { index, (plain, guided) ->
                val mad = meanAbsDiff(plain, guided)
                println("LIGHTFORGE_GUIDED factor4 frame=${index + 1} meanAbsDiff=$mad")
                assertTrue("frame ${index + 1} differs: $mad", mad < 2.0)
                plain.recycle(); guided.recycle()
            }
            a.recycle(); b.recycle()
        }
    }

    @Test
    fun guidedIsSharperThanUpscaledLowResolution() {
        RifeFrameInterpolator(context).use { interpolator ->
            assumeTrue(interpolator.capability.backend == FrameInterpolationBackend.Vulkan)
            val highA = texturedBitmap(1280, 720, 0f)
            val highB = texturedBitmap(1280, 720, 12f)
            val truth = texturedBitmap(1280, 720, 6f)
            val lowA = Bitmap.createScaledBitmap(highA, 320, 180, true)
            val lowB = Bitmap.createScaledBitmap(highB, 320, 180, true)
            val guided = interpolator.interpolateGuided(lowA, lowB, highA, highB, 0.5f)
            val plainLow = interpolator.interpolate(lowA, lowB, 0.5f)
            val plain = Bitmap.createScaledBitmap(plainLow, 1280, 720, true)
            val margin = 32
            val guidedPsnr = psnr(guided, truth, margin)
            val plainPsnr = psnr(plain, truth, margin)
            val blendPsnr = run {
                val half = IntArray(1280 * 720)
                val pa = IntArray(half.size).also { highA.getPixels(it, 0, 1280, 0, 0, 1280, 720) }
                val pb = IntArray(half.size).also { highB.getPixels(it, 0, 1280, 0, 0, 1280, 720) }
                for (i in half.indices) {
                    half[i] = (0xFF shl 24) or (((((pa[i] shr 16) and 255) + ((pb[i] shr 16) and 255)) / 2) shl 16) or
                        (((((pa[i] shr 8) and 255) + ((pb[i] shr 8) and 255)) / 2) shl 8) or
                        (((pa[i] and 255) + (pb[i] and 255)) / 2)
                }
                psnr(Bitmap.createBitmap(half, 1280, 720, Bitmap.Config.ARGB_8888), truth, margin)
            }
            println(
                "LIGHTFORGE_GUIDED quality guidedPsnr=$guidedPsnr plainUpscaledPsnr=$plainPsnr " +
                    "crossfadePsnr=$blendPsnr guidedMad=${meanAbsDiff(guided, truth, margin)} " +
                    "plainMad=${meanAbsDiff(plain, truth, margin)}",
            )
            assertTrue("guided $guidedPsnr dB vs plain $plainPsnr dB", guidedPsnr > plainPsnr + 1.0)
            listOf(highA, highB, truth, lowA, lowB, guided, plainLow, plain).forEach(Bitmap::recycle)
        }
    }
}
