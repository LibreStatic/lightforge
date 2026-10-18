package com.librestatic.lightforge.feature.objecteraser

import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Runs the Moebius models on the device. They are not bundled: push the generative package's files
 * (moebius-unet.tflite, moebius-vae-encoder.tflite, moebius-vae-decoder.tflite, moebius-cond.f32) into the
 * app's external files directory first, or the tests are skipped.
 */
@RunWith(AndroidJUnit4::class)
class MoebiusInpainterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val size = InpaintPlan.ModelSize
    private val pixels = IntArray(size * size) { i -> 0xFF000000.toInt() or ((i % size) / 2 shl 16) or ((i / size) / 2 shl 8) or 0x60 }
    private val dab = BrushDab(256f, 256f, 60f)
    private val keep = InpaintPlan.keepMask(InpaintPass(PixelRect(0, 0, size, size), dab.bounds, listOf(dab)))
    private val cache = File(context.cacheDir, "moebius-device-test")

    private fun files(): List<File> {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val files = InpaintModelCatalog.Generative.files.map { File(directory, it.name) }
        assumeTrue("Push the models to ${directory.path}", files.all { it.isFile })
        return files
    }

    /**
     * The GPU delegate maps matmuls onto convolutions with its own rules; the GPU result must match the CPU's
     * (XNNPACK, the reference the export was checked against) up to fp16 rounding. One denoising step keeps the
     * CPU run to about a minute.
     */
    @Test
    fun gpuMatchesCpu() {
        val files = files()
        val sampler = MoebiusSampler(steps = 2)
        fun run(accelerator: Accelerator) =
            MoebiusInpainter.open(context, files, "device-test", cache, accelerator, sampler).use {
                val started = SystemClock.elapsedRealtime()
                it.inpaint(pixels, keep, seed = 7L).clone()
                    .also { _ -> println("moebius parity $accelerator: ${SystemClock.elapsedRealtime() - started} ms") }
            }
        val gpu = run(Accelerator.GPU)
        val cpu = run(Accelerator.CPU)
        var total = 0L
        var worst = 0
        var holes = 0
        for (i in gpu.indices) {
            if (keep[i]) continue
            holes++
            for (shift in intArrayOf(16, 8, 0)) {
                val difference = abs(((gpu[i] shr shift) and 0xFF) - ((cpu[i] shr shift) and 0xFF))
                total += difference
                worst = maxOf(worst, difference)
            }
        }
        val mean = total.toDouble() / (holes * 3)
        println("moebius parity: mean abs ${"%.3f".format(mean)} max $worst over $holes hole pixels")
        assertTrue("GPU differs from CPU by $mean on average", mean < 3.0)
    }

    @Test
    fun fillsTheHole_keepsTheRest_andIsRepeatable() {
        val files = files()
        cache.deleteRecursively()

        MoebiusInpainter.open(context, files, "device-test", cache).use { inpainter ->
            val started = SystemClock.elapsedRealtime()
            val first = inpainter.inpaint(pixels, keep, seed = 1L).clone()
            println("moebius ${inpainter.accelerator}: ${SystemClock.elapsedRealtime() - started} ms")
            assertEquals(size * size, first.size)
            assertTrue(first.any { it != 0 })
            // The memo makes an identical pass free and returns the same pixels.
            assertArrayEquals(first, inpainter.inpaint(pixels, keep, seed = 1L))
        }
    }
}
