package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.editing.image.grade.PhotoGraders
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoColorTransformDeviceTest {
    @Test
    fun combinedMatrixMatchesSequentialRendererOrder() {
        val operations = listOf(
            EditOperation.Filter("vivid"),
            EditOperation.Tone(brightness = 0.12f, contrast = 1.15f, saturation = 0.8f),
        )
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.rgb(30, 90, 180))
            setPixel(1, 0, Color.rgb(210, 120, 40))
        }
        var sequential = source
        operations.forEach { operation ->
            val next = drawWithMatrix(sequential, PhotoColorTransform.matrixFor(operation).array)
            if (sequential !== source) sequential.recycle()
            sequential = next
        }
        val combined = drawWithMatrix(source, PhotoColorTransform.combinedValues(operations))

        repeat(2) { x ->
            val expected = sequential.getPixel(x, 0)
            val actual = combined.getPixel(x, 0)
            assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 1)
            assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 1)
            assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 1)
        }
    }

    /** Legacy tone/filter recipes now go through the baked legacy LUT; they must stay close to the old matrices. */
    @Test
    fun legacyRecipesRenderWithinAFewLevelsOfTheColorMatrixPipeline() = runBlocking {
        val operations = listOf(
            EditOperation.Filter("vivid"),
            EditOperation.Tone(brightness = 0.12f, contrast = 1.15f, saturation = 0.8f),
        )
        val width = 64
        val height = 48
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            source.setPixel(x, y, Color.rgb(x * 4, y * 5, (x * y) % 256))
        }
        var expected = source
        operations.forEach { operation ->
            expected = drawWithMatrix(expected, PhotoColorTransform.matrixFor(operation).array)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recipe = operations.fold(EditRecipe.forSource(MediaKey("external_primary", 1L), 0)) { r, op -> r.append(op) }
        val renderer = PhotoImageRenderer(context.contentResolver, grader = PhotoGraders.default(context))
        val actual = renderer.renderDecoded(source.copy(Bitmap.Config.ARGB_8888, false), recipe)
        var worst = 0
        for (y in 0 until height) for (x in 0 until width) {
            val e = expected.getPixel(x, y)
            val a = actual.getPixel(x, y)
            worst = maxOf(
                worst,
                kotlin.math.abs(Color.red(e) - Color.red(a)),
                kotlin.math.abs(Color.green(e) - Color.green(a)),
                kotlin.math.abs(Color.blue(e) - Color.blue(a)),
            )
        }
        assertTrue("legacy recipe differs from the ColorMatrix pipeline by $worst levels", worst <= 4)
    }

    private fun drawWithMatrix(source: Bitmap, values: FloatArray): Bitmap =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(source, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(values)
            })
        }
}
