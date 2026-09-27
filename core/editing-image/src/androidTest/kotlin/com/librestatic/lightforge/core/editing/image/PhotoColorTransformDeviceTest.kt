package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun drawWithMatrix(source: Bitmap, values: FloatArray): Bitmap =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(source, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(values)
            })
        }
}
