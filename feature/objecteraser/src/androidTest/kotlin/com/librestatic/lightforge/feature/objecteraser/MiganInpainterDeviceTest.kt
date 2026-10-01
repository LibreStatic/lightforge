package com.librestatic.lightforge.feature.objecteraser

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.librestatic.lightforge.core.ml.PinnedModelDownload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.log10

/**
 * Runs the pinned MI-GAN model on the GPU and the CPU and checks they agree. The model is not bundled: push
 * migan-512-fp16.tflite into this test package's external files directory first, or the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class MiganInpainterDeviceTest {
    @Test
    fun gpuMatchesCpu_andErasesABitmapEndToEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = requireNotNull(context.getExternalFilesDir(null))
        val model = File(files, "migan-512-fp16.tflite")
        assumeTrue("Push the model to ${model.path}", model.isFile)
        PinnedModelDownload.verify(model, InpaintModelCatalog.Model, CancellationSignal())

        val size = InpaintPlan.ModelSize
        val pixels = IntArray(size * size) { i -> stripes(i % size, i / size) }
        val dab = BrushDab(256f, 256f, 60f)
        val keep = InpaintPlan.keepMask(InpaintPass(PixelRect(0, 0, size, size), dab.bounds, listOf(dab)))
        val cache = File(context.cacheDir, "migan-device-test").apply { deleteRecursively() }

        val report = JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val cpu = MiganInpainter.open(context, model, "device-test", cache, preferGpu = false).use { inpainter ->
            assertEquals(Accelerator.CPU, inpainter.accelerator)
            timed(report, "cpu") { inpainter.inpaint(pixels, keep) }
        }
        val gpu = MiganInpainter.open(context, model, "device-test", cache).use { inpainter ->
            report.put("gpuAccelerator", inpainter.accelerator.name)
            timed(report, "gpu") { inpainter.inpaint(pixels, keep) }
        }
        val psnr = psnr(cpu, gpu)
        report.put("gpuVsCpuPsnr", psnr)
        // The hole is filled from the stripes around it, not left flat.
        val hole = keep.indices.filter { !keep[it] }
        assertTrue(hole.map { cpu[it] }.toSet().size > 16)
        assertTrue("GPU output diverges from CPU: $psnr dB", psnr >= 35.0)

        // End to end on a photo-sized bitmap, through the planner and the banded blend.
        val photo = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = stripes(x / 4, y / 4)
                setPixels(row, 0, width, 0, y, width, 1)
            }
        }
        val regions = listOf(ObjectEraser.EraseRegion(1400, 900, 200, 200), ObjectEraser.EraseRegion(1550, 900, 200, 200))
        MiganInpainter.open(context, model, "device-test", cache).use { inpainter ->
            val started = SystemClock.elapsedRealtime()
            val result = ObjectEraser().inpaintRegions(photo, regions, inpainter)
            report.put("photoEraseMillis", SystemClock.elapsedRealtime() - started)
            assertEquals(ObjectEraser.EraseMethod.ML_INPAINTING, result.method)
            assertEquals(photo.getPixel(10, 10), result.bitmap.getPixel(10, 10))
            assertTrue(result.bitmap.getPixel(1500, 1000) != photo.getPixel(1500, 1000) ||
                result.bitmap.getPixel(1650, 1000) != photo.getPixel(1650, 1000))
            result.bitmap.recycle()
        }
        photo.recycle()
        cache.deleteRecursively()
        File(files, "migan-device-test.json").writeText(report.toString(2))
    }

    private fun timed(report: JSONObject, key: String, run: () -> IntArray): IntArray {
        run() // Warm-up: the first GPU run uploads weights.
        val started = SystemClock.elapsedRealtimeNanos()
        val result = run()
        report.put("${key}Millis", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
        return result
    }

    private fun stripes(x: Int, y: Int): Int =
        if ((x + y) / 24 % 2 == 0) Color.rgb(200, 80 + y % 40, 40) else Color.rgb(30, 90, 160 + x % 60)

    private fun psnr(a: IntArray, b: IntArray): Double {
        var squared = 0.0
        for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) {
            val d = ((a[i] shr shift) and 255) - ((b[i] shr shift) and 255)
            squared += d * d
        }
        val mse = squared / (a.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }
}
