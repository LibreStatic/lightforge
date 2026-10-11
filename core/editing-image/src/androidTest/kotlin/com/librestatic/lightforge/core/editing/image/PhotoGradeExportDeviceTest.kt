package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.editing.image.grade.PhotoGraders
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.NormalizedPoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class PhotoGradeExportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @Before
    fun clean() = files.clear()

    @After
    fun delete() = files.forEach { it.delete() }

    private fun recipe(vararg operations: EditOperation): EditRecipe =
        operations.fold(EditRecipe.forSource(MediaKey("external_primary", 1L), 0)) { r, op -> r.append(op) }

    private fun file(name: String) = File(context.cacheDir, "$name-${System.nanoTime()}.png").also { files += it }

    /** Source whose red channel is x and green channel is y, so geometry is readable from colours. */
    private fun writeSource(width: Int, height: Int): Uri {
        val random = Random(11)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val r = (x * 255 / (width - 1)).coerceIn(0, 255)
            val g = (y * 255 / (height - 1)).coerceIn(0, 255)
            val b = (128 + random.nextInt(-20, 21) + (x / 64 % 2) * 30).coerceIn(0, 255)
            pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = file("grade-source")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun renderer(maxExportPixels: Long) =
        PhotoImageRenderer(context.contentResolver, maxExportPixels = maxExportPixels, grader = PhotoGraders.default(context))

    @Test
    fun tiledExportEqualsSingleBitmapExport() = runBlocking {
        val source = writeSource(1_400, 900)
        val recipe = recipe(
            EditOperation.Adjust(mapOf(AdjustKey.Vignette to 0.8f, AdjustKey.Highlights to -0.6f, AdjustKey.Shadows to 0.4f)),
            EditOperation.Look("builtin:warm", 0.9f),
        )
        val single = file("single")
        val tiled = file("tiled")
        val a = renderer(24_000_000L).export(source, recipe, single) as PhotoExportOutcome.Completed
        val b = renderer(1_000_000L).export(source, recipe, tiled) as PhotoExportOutcome.Completed
        assertTrue(PhotoExportWarning.FullResolutionPng in b.warnings)
        assertTrue(PhotoExportWarning.FullResolutionPng !in a.warnings)
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val diff = maxDiff(single, tiled)
        assertTrue("tiled export differs from single export by $diff levels", diff <= 2)
    }

    @Test
    fun perspectiveExportKeepsTheFrameSizeAndMapsTheCorners() = runBlocking {
        val source = writeSource(1_400, 900)
        val quad = listOf(
            NormalizedPoint(0.20f, 0.10f), NormalizedPoint(0.90f, 0.20f),
            NormalizedPoint(0.80f, 0.90f), NormalizedPoint(0.10f, 0.80f),
        )
        val recipe = recipe(EditOperation.Perspective(quad))
        for (limit in longArrayOf(24_000_000L, 1_000_000L)) {
            val out = file("perspective")
            val done = renderer(limit).export(source, recipe, out) as PhotoExportOutcome.Completed
            assertEquals(1_400, done.width)
            assertEquals(900, done.height)
            val bitmap = BitmapFactory.decodeFile(out.absolutePath)
            assertEquals(1_400, bitmap.width)
            assertEquals(900, bitmap.height)
            // Output corner i shows the source at quad corner i: red encodes x, green encodes y.
            val probes = listOf(
                (3 to 3) to quad[0], (bitmap.width - 4 to 3) to quad[1],
                (bitmap.width - 4 to bitmap.height - 4) to quad[2], (3 to bitmap.height - 4) to quad[3],
            )
            probes.forEach { (xy, corner) ->
                val pixel = bitmap.getPixel(xy.first, xy.second)
                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                assertTrue("red $red vs ${corner.x * 255}", abs(red - corner.x * 255) <= 8)
                assertTrue("green $green vs ${corner.y * 255}", abs(green - corner.y * 255) <= 8)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun missingLookIsReportedAndSkipped() = runBlocking {
        val source = writeSource(300, 200)
        val renderer = PhotoImageRenderer(
            context.contentResolver, grader = PhotoGraders.default(context), lookResolver = { null },
        )
        val out = file("missing-look")
        val done = renderer.export(source, recipe(EditOperation.Look("builtin:warm", 1f)), out) as PhotoExportOutcome.Completed
        assertEquals("builtin:warm", done.missingLookId)
        assertTrue(done.width == 300 && done.height == 200)
    }

    private fun maxDiff(a: File, b: File): Int {
        val x = BitmapFactory.decodeFile(a.absolutePath)
        val y = BitmapFactory.decodeFile(b.absolutePath)
        assertEquals(x.width, y.width)
        assertEquals(x.height, y.height)
        val px = IntArray(x.width * x.height).also { x.getPixels(it, 0, x.width, 0, 0, x.width, x.height) }
        val py = IntArray(y.width * y.height).also { y.getPixels(it, 0, y.width, 0, 0, y.width, y.height) }
        var worst = 0
        for (i in px.indices) for (shift in intArrayOf(16, 8, 0)) {
            worst = maxOf(worst, abs(((px[i] shr shift) and 0xff) - ((py[i] shr shift) and 0xff)))
        }
        x.recycle()
        y.recycle()
        return worst
    }
}
