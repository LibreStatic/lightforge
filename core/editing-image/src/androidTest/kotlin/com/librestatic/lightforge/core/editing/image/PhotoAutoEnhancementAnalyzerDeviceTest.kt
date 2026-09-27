package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoAutoEnhancementAnalyzerDeviceTest {
    @Test
    fun darkAndBrightImagesReceiveBoundedCorrections() {
        val dark = solidBitmap(Color.rgb(24, 28, 32))
        val bright = solidBitmap(Color.rgb(235, 238, 240))

        val darkSuggestion = PhotoAutoEnhancementAnalyzer.analyze(dark).enhance
        val brightSuggestion = PhotoAutoEnhancementAnalyzer.analyze(bright).enhance

        assertTrue(darkSuggestion.brightness > 0f)
        assertTrue(brightSuggestion.brightness < 0f)
        assertBounded(darkSuggestion)
        assertBounded(brightSuggestion)
    }

    @Test
    fun grayscaleAndTransparentInputsRemainSafe() {
        val grayscale = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val value = (x * 255 / (width - 1))
                setPixel(x, y, Color.rgb(value, value, value))
            }
        }
        val transparent = solidBitmap(Color.TRANSPARENT)

        val grayscaleSuggestions = PhotoAutoEnhancementAnalyzer.analyze(grayscale)
        val transparentSuggestions = PhotoAutoEnhancementAnalyzer.analyze(transparent)

        assertEquals(1f, grayscaleSuggestions.enhance.saturation)
        assertEquals(EditOperation.Tone(), transparentSuggestions.enhance)
        assertEquals(EditOperation.Tone(), transparentSuggestions.dynamic)
    }

    @Test
    fun analysisIsDeterministicAndDynamicIsStronger() {
        val source = Bitmap.createBitmap(192, 128, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, Color.rgb(20 + x * 180 / width, 35 + y * 150 / height, 80 + x * 100 / width))
            }
        }

        val first = PhotoAutoEnhancementAnalyzer.analyze(source)
        val second = PhotoAutoEnhancementAnalyzer.analyze(source)

        assertEquals(first, second)
        assertTrue(first.dynamic.contrast >= first.enhance.contrast)
        assertTrue(first.dynamic.saturation >= first.enhance.saturation)
        assertBounded(first.enhance)
        assertBounded(first.dynamic)
    }

    private fun solidBitmap(color: Int) = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }

    private fun assertBounded(tone: EditOperation.Tone) {
        assertTrue(tone.brightness in -0.15f..0.15f)
        assertTrue(tone.contrast in 0.90f..1.35f)
        assertTrue(tone.saturation in 0.95f..1.30f)
        assertTrue(tone.brightness.isFinite() && tone.contrast.isFinite() && tone.saturation.isFinite())
    }
}
