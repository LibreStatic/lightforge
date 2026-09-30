package com.librestatic.lightforge.feature.collage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollageRenderTest {

    @Test
    fun render_grid2_producesCorrectSize() {
        val bmp1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val config = CollageConfig(
            template = CollageTemplate.GRID_2,
            outputWidth = 800,
            outputHeight = 600,
        )
        val result = CollageTemplates.render(listOf(bmp1, bmp2), config)
        assertEquals(800, result.width)
        assertEquals(600, result.height)
    }

    @Test
    fun render_grid4_withFewerBitmaps_doesNotCrash() {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val config = CollageConfig(
            template = CollageTemplate.GRID_4,
            outputWidth = 400,
            outputHeight = 400,
        )
        val result = CollageTemplates.render(listOf(bmp), config)
        assertEquals(400, result.width)
        assertEquals(400, result.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun render_tooManyBitmaps_throws() {
        val bmps = (1..5).map { Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888) }
        val config = CollageConfig(CollageTemplate.GRID_2, 400, 400)
        CollageTemplates.render(bmps, config)
    }

    @Test
    fun render_grid2_bothHalbsFilled() {
        val bmp1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bmp2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val config = CollageConfig(
            template = CollageTemplate.GRID_2,
            outputWidth = 200,
            outputHeight = 200,
            spacing = 0f,
        )
        val result = CollageTemplates.render(listOf(bmp1, bmp2), config)
        // Left half should be reddish, right half should be bluish
        val leftPixel = result.getPixel(50, 100)
        val rightPixel = result.getPixel(150, 100)
        assertTrue("Left should be red-ish", Color.red(leftPixel) > 200)
        assertTrue("Right should be blue-ish", Color.blue(rightPixel) > 200)
    }
}
