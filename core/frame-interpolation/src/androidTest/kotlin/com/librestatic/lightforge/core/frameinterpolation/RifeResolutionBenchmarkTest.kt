package com.librestatic.lightforge.core.frameinterpolation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic: warm RIFE latency per preview size, read from logcat (`LIGHTFORGE_RIFE_BENCH`).
 * Picks `HoldSlowMotionSession`'s preview long edge; it must stay under ~40 ms per frame.
 */
@RunWith(AndroidJUnit4::class)
class RifeResolutionBenchmarkTest {
    @Test
    fun warmLatencyPerResolution() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RifeFrameInterpolator(context).use { interpolator ->
            listOf(256 to 144, 320 to 180, 352 to 198, 480 to 270).forEach { (w, h) ->
                val random = Random(w)
                val a = Bitmap.createBitmap(IntArray(w * h) { random.nextInt() or (0xFF shl 24) }, w, h, Bitmap.Config.ARGB_8888)
                val b = Bitmap.createBitmap(IntArray(w * h) { random.nextInt() or (0xFF shl 24) }, w, h, Bitmap.Config.ARGB_8888)
                repeat(2) { interpolator.interpolate(a, b, 0.5f).recycle() }
                val started = System.nanoTime()
                repeat(10) { interpolator.interpolate(a, b, 0.5f).recycle() }
                println("LIGHTFORGE_RIFE_BENCH backend=${interpolator.capability.backend} ${w}x$h msPerFrame=${(System.nanoTime() - started) / 10_000_000}")
                a.recycle(); b.recycle()
            }
        }
    }

    /** Guided path cost and JPEG cost for the 320 px flow / 1280 px output configuration. */
    @Test
    fun guidedAndEncodeCost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RifeFrameInterpolator(context).use { interpolator ->
            if (!interpolator.capability.supportsGuided) return
            val highA = texturedBitmap(1280, 720, 0f)
            val highB = texturedBitmap(1280, 720, 12f)
            val lowA = Bitmap.createScaledBitmap(highA, 320, 180, true)
            val lowB = Bitmap.createScaledBitmap(highB, 320, 180, true)
            val smallA = Bitmap.createScaledBitmap(highA, 320, 180, true)
            val smallB = Bitmap.createScaledBitmap(highB, 320, 180, true)
            repeat(3) {
                interpolator.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle()
                interpolator.interpolateGuided(lowA, lowB, smallA, smallB, 0.5f).recycle()
            }
            fun timeMs(block: () -> Unit): Double {
                val started = System.nanoTime()
                repeat(20) { block() }
                return (System.nanoTime() - started) / 20_000_000.0
            }
            val guided1280 = timeMs { interpolator.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle() }
            val guided320 = timeMs { interpolator.interpolateGuided(lowA, lowB, smallA, smallB, 0.5f).recycle() }
            val plain320 = timeMs { interpolator.interpolate(lowA, lowB, 0.5f).recycle() }
            println(
                "LIGHTFORGE_RIFE_BENCH guided 320->1280 msPerFrame=$guided1280 guided320->320=$guided320 " +
                    "plain320=$plain320 composeOnlyApprox=${guided1280 - guided320}",
            )

            // Sensor-like noise makes the JPEG size a conservative (large) estimate.
            val random = Random(7)
            val noisy = highA.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(1280 * 720).also { noisy.getPixels(it, 0, 1280, 0, 0, 1280, 720) }
            for (i in pixels.indices) {
                val n = random.nextInt(-6, 7)
                val p = pixels[i]
                pixels[i] = (0xFF shl 24) or (((p shr 16 and 255) + n).coerceIn(0, 255) shl 16) or
                    (((p shr 8 and 255) + n).coerceIn(0, 255) shl 8) or ((p and 255) + n).coerceIn(0, 255)
            }
            noisy.setPixels(pixels, 0, 1280, 0, 0, 1280, 720)
            listOf(85, 88, 92).forEach { quality ->
                listOf("clean" to highA, "noisy" to noisy).forEach { (label, bitmap) ->
                    val bytes = java.io.ByteArrayOutputStream()
                    repeat(3) { bytes.reset(); bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes) }
                    val ms = timeMs { bytes.reset(); bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes) }
                    println("LIGHTFORGE_RIFE_BENCH jpeg1280x720 $label q=$quality encodeMs=$ms bytes=${bytes.size()}")
                }
            }
            val file = java.io.File(context.cacheDir, "bench.jpg")
            val fileMs = timeMs {
                java.io.FileOutputStream(file).use { highA.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }
            println("LIGHTFORGE_RIFE_BENCH jpeg1280x720 toFile q=88 encodeMs=$fileMs")
            file.delete()
            listOf(highA, highB, lowA, lowB, smallA, smallB, noisy).forEach(Bitmap::recycle)
        }
    }
}
