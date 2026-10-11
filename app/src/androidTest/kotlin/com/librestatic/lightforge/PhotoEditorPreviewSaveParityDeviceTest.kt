package com.librestatic.lightforge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.editing.image.PhotoExportOutcome
import com.librestatic.lightforge.core.editing.image.PhotoImageRenderer
import com.librestatic.lightforge.core.editing.image.grade.PhotoGraders
import com.librestatic.lightforge.core.editing.image.grade.PhotoLutBaker
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditHistory
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.NormalizedPoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Save copy must match the live preview. Drives the same calls as the photo editor view model:
 * a geometry-only 1600 px frame, the live colour operations graded by the shared GPU grader, and
 * the export of the committed recipe (Adjust, Look and Perspective).
 */
@RunWith(AndroidJUnit4::class)
class PhotoEditorPreviewSaveParityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun delete() = files.forEach { it.delete() }

    private fun file(name: String, extension: String) =
        File(context.cacheDir, "$name-${System.nanoTime()}.$extension").also { files += it }

    private fun writeSource(width: Int, height: Int): Uri {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val r = x * 255 / (width - 1)
            val g = y * 255 / (height - 1)
            val b = 90 + (x + y) % 80
            pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val source = file("parity-source", "png")
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(source)
    }

    /** Mean RGB of a square patch around the centre, as the stage would show it. */
    private fun centre(bitmap: Bitmap): IntArray {
        val half = 8
        val sum = LongArray(3)
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        for (y in cy - half until cy + half) for (x in cx - half until cx + half) {
            val p = bitmap.getPixel(x, y)
            sum[0] += (p shr 16) and 0xff
            sum[1] += (p shr 8) and 0xff
            sum[2] += p and 0xff
        }
        val n = (2 * half) * (2 * half)
        return IntArray(3) { (sum[it] / n).toInt() }
    }

    @Test
    fun savedCopyCentreMatchesTheGradedPreview() = runBlocking {
        val renderer = PhotoImageRenderer(
            context.contentResolver,
            grader = PhotoGraders.default(context),
            lookResolver = PhotoLutBaker::bake,
        )
        val source = writeSource(2_400, 1_600)
        val adjust = EditOperation.Adjust(
            mapOf(AdjustKey.Brightness to 0.2f, AdjustKey.Contrast to 0.3f, AdjustKey.Warmth to 0.25f),
        )
        val look = EditOperation.Look("builtin:film", 0.8f)
        val perspective = EditOperation.Perspective(
            listOf(
                NormalizedPoint(0.06f, 0.04f), NormalizedPoint(0.94f, 0.10f),
                NormalizedPoint(0.90f, 0.96f), NormalizedPoint(0.10f, 0.90f),
            ),
        )
        val history = listOf(adjust, look, perspective).fold(
            EditHistory.initial(EditRecipe.forSource(MediaKey("external_primary", 1L), 0)),
        ) { history, operation -> history.apply(operation) }
        val recipe = history.present

        // Preview: geometry only, then the live grade, exactly as the editor publishes it.
        val geometryRecipe = recipe.copy(operations = recipe.operations.filterNot(::isPhotoColorOperation))
        val frame = renderer.renderPreview(source, geometryRecipe, 1_600)
        val params = renderer.gradeParams(
            liveGradeOperations(recipe, photoAdjustFor(recipe), photoColorMixFor(recipe), photoLookFor(recipe)),
        )
        assertTrue("the grade must not be the identity", !params.isIdentity)
        val preview = renderer.grade(frame, params)
        assertTrue(preview !== frame)

        val saved = file("parity-saved", "jpg")
        val outcome = renderer.export(source, recipe, saved, preserveMetadata = false)
        assertTrue(outcome is PhotoExportOutcome.Completed)
        val savedBitmap = BitmapFactory.decodeFile(saved.path)
        assertNotNull(savedBitmap)

        val expected = centre(preview)
        val actual = centre(savedBitmap)
        for (channel in 0..2) {
            assertTrue(
                "channel $channel: preview ${expected[channel]} vs saved ${actual[channel]}",
                abs(expected[channel] - actual[channel]) <= 6,
            )
        }
        // Both frames show the same photo shape, whatever their resolution.
        assertEquals(
            preview.width.toDouble() / preview.height,
            savedBitmap.width.toDouble() / savedBitmap.height,
            0.02,
        )
    }
}
