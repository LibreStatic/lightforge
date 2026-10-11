package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.editing.image.grade.CpuPhotoGrader
import com.librestatic.lightforge.core.editing.image.grade.GpuPhotoGrader
import com.librestatic.lightforge.core.editing.image.grade.PhotoGradeParams
import com.librestatic.lightforge.core.editing.image.grade.PhotoGrader
import com.librestatic.lightforge.core.editing.image.grade.PhotoLocalBase
import com.librestatic.lightforge.core.editing.image.grade.PhotoLutBaker
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.ColorMixBand
import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class GpuPhotoGraderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A GPU grader whose fallback fails, so a passing test proves the GPU path ran. */
    private fun strictGpu(): PhotoGrader = GpuPhotoGrader(context, object : PhotoGrader {
        override fun grade(
            input: Bitmap, params: PhotoGradeParams, base: PhotoLocalBase?,
            tileOriginX: Int, tileOriginY: Int, frameWidth: Int, frameHeight: Int,
        ): Bitmap = error("GPU grader fell back to the CPU")
    })

    @Test
    fun gpuMatchesCpuReferenceOnRandomParameterSets() {
        val gpu = strictGpu()
        val cpu = CpuPhotoGrader()
        val random = Random(45)
        val tile = testImage(257, 193, random)
        val frameImage = testImage(700, 500, Random(7))
        val base = PhotoLocalBase.from(frameImage)
        var worst = 0
        repeat(60) { index ->
            val operations = randomOperations(random, forceAll = index < 6)
            val params = PhotoGradeParams.from(operations, PhotoLutBaker::bake)
            val inFrame = index % 2 == 0
            val originX = if (inFrame) 311 else 0
            val originY = if (inFrame) 217 else 0
            val frameW = if (inFrame) 700 else tile.width
            val frameH = if (inFrame) 500 else tile.height
            val expected = cpu.grade(tile, params, base, originX, originY, frameW, frameH)
            val actual = gpu.grade(tile, params, base, originX, originY, frameW, frameH)
            val diff = maxPremultipliedDiff(expected, actual)
            worst = maxOf(worst, diff)
            assertTrue("set $index differs by $diff levels: $operations", diff <= 2)
        }
        Log.i(Tag, "GPU vs CPU worst channel diff over 60 sets: $worst")
    }

    @Test
    fun eachControlAloneMatchesTheCpuReference() {
        val gpu = strictGpu()
        val cpu = CpuPhotoGrader()
        val tile = testImage(257, 193, Random(5))
        val base = PhotoLocalBase.from(testImage(700, 500, Random(7)))
        val failures = mutableListOf<String>()
        fun check(name: String, operations: List<EditOperation>) {
            val params = PhotoGradeParams.from(operations, PhotoLutBaker::bake)
            val diff = maxPremultipliedDiff(
                cpu.grade(tile, params, base, 311, 217, 700, 500),
                gpu.grade(tile, params, base, 311, 217, 700, 500),
            )
            Log.i(Tag, "control $name: $diff")
            if (diff > 2) failures += "$name=$diff"
        }
        for (key in AdjustKey.entries) for (v in listOf(-0.7f, 0.6f)) {
            check("${key.token}@$v", listOf(EditOperation.Adjust(mapOf(key to v))))
        }
        check("colorMix", listOf(EditOperation.ColorMix(List(PhotoGradeParams.BandCount) { ColorMixBand(0.5f, -0.4f, 0.3f) })))
        check("look", listOf(EditOperation.Look("builtin:warm", 0.7f)))
        check("legacy", listOf(EditOperation.Filter("vivid"), EditOperation.Tone(0.1f, 1.2f, 0.8f)))
        assertTrue("controls differ from the CPU reference: $failures", failures.isEmpty())
    }

    @Test
    fun largeInputIsGradedInSubTilesLikeOneFrame() {
        val gpu = strictGpu()
        val cpu = CpuPhotoGrader()
        val image = testImage(2_300, 700, Random(3), opaque = true)
        val params = PhotoGradeParams.from(
            listOf(
                EditOperation.Adjust(mapOf(AdjustKey.Vignette to 0.7f, AdjustKey.Highlights to 0.5f, AdjustKey.Contrast to 0.3f)),
                EditOperation.Look("builtin:warm", 0.8f),
            ),
            PhotoLutBaker::bake,
        )
        val base = PhotoLocalBase.from(image)
        val diff = maxPremultipliedDiff(
            cpu.grade(image, params, base),
            gpu.grade(image, params, base),
        )
        assertTrue("sub-tiled grade differs by $diff levels", diff <= 2)
    }

    @Test
    fun gradeTimingsAreLogged() {
        val gpu = strictGpu()
        val params = PhotoGradeParams.from(
            listOf(
                EditOperation.Adjust(
                    mapOf(
                        AdjustKey.Brightness to 0.3f, AdjustKey.Contrast to 0.4f, AdjustKey.Highlights to -0.5f,
                        AdjustKey.Shadows to 0.5f, AdjustKey.Saturation to 0.3f, AdjustKey.SkinTone to 0.4f,
                        AdjustKey.Vignette to 0.5f,
                    ),
                ),
                EditOperation.Look("builtin:vivid", 1f),
            ),
            PhotoLutBaker::bake,
        )
        for ((w, h) in listOf(1024 to 768, 1600 to 1200, 2048 to 2048)) {
            val image = testImage(w, h, Random(w), opaque = true)
            val base = PhotoLocalBase.from(image)
            gpu.grade(image, params, base) // warm-up: compile, upload
            val runs = 5
            val start = System.nanoTime()
            repeat(runs) { gpu.grade(image, params, base).recycle() }
            val millis = (System.nanoTime() - start) / 1e6 / runs
            Log.i(Tag, "GPU grade ${w}x$h: ${"%.1f".format(millis)} ms")
        }
    }

    @Test
    fun identityParamsKeepThePixels() {
        val image = testImage(64, 48, Random(1), opaque = true)
        val out = strictGpu().grade(image, PhotoGradeParams(), null)
        assertEquals(0, maxPremultipliedDiff(image, out))
    }

    private fun randomOperations(random: Random, forceAll: Boolean): List<EditOperation> {
        fun value(limit: Float = 1f) = ((random.nextFloat() * 2f - 1f) * limit).let { (it * 100).roundToInt() / 100f }
        // Saturation and colour mix act on hue, which is ill-conditioned near neutral: tiny float
        // differences between the CPU and the GPU would be amplified, so keep them moderate.
        val adjust = AdjustKey.entries.associateWith {
            if (!forceAll && random.nextFloat() >= 0.5f) 0f
            else if (it == AdjustKey.Saturation) value(0.5f) else value()
        }
        return buildList {
            add(EditOperation.Adjust(adjust))
            if (forceAll || random.nextFloat() < 0.4f) {
                add(EditOperation.ColorMix(List(PhotoGradeParams.BandCount) {
                    ColorMixBand(value(0.4f), value(0.4f), value(0.4f))
                }))
            }
            if (forceAll || random.nextFloat() < 0.5f) {
                val ids = listOf("builtin:vivid", "builtin:natural", "builtin:warm", "builtin:cool", "builtin:mono")
                add(EditOperation.Look(ids[random.nextInt(ids.size)], random.nextFloat()))
            }
            if (forceAll || random.nextFloat() < 0.3f) {
                add(EditOperation.Filter(listOf("vivid", "mono", "natural")[random.nextInt(3)]))
                add(EditOperation.Tone(random.nextFloat() * 0.4f - 0.2f, 0.8f + random.nextFloat() * 0.4f, 0.7f + random.nextFloat() * 0.6f))
            }
        }
    }

    /** Smooth colour ramps plus noise; a few alpha < 255 pixels unless [opaque]. */
    private fun testImage(width: Int, height: Int, random: Random, opaque: Boolean = false): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val r = (x * 255 / (width - 1) + random.nextInt(-12, 13)).coerceIn(0, 255)
            val g = (y * 255 / (height - 1) + random.nextInt(-12, 13)).coerceIn(0, 255)
            val b = (255 - x * 255 / (width - 1) + random.nextInt(-12, 13)).coerceIn(0, 255)
            val alpha = if (!opaque && (x * 7 + y * 3) % 41 == 0) 200 else 255
            pixels[y * width + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** Compares in premultiplied space, where the graders agree; alpha must match exactly. */
    private fun maxPremultipliedDiff(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var worst = 0
        for (i in pa.indices) {
            val alphaA = pa[i] ushr 24
            assertEquals("alpha at $i", alphaA, pb[i] ushr 24)
            for (shift in intArrayOf(16, 8, 0)) {
                val ca = ((pa[i] shr shift) and 0xff) * alphaA / 255f
                val cb = ((pb[i] shr shift) and 0xff) * alphaA / 255f
                worst = maxOf(worst, abs(ca - cb).roundToInt())
            }
        }
        return worst
    }

    private companion object { const val Tag = "GpuPhotoGraderTest" }
}
