package com.ugallery.feature.photoeditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoExperimentalToolsTest {

    @Test
    fun fittedBounds_letterboxesAWideImage() {
        val bounds = fittedImageBounds(IntSize(1000, 1000), IntSize(2000, 1000))
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(250f, bounds.top, 0.01f)
        assertEquals(1000f, bounds.width, 0.01f)
        assertEquals(500f, bounds.height, 0.01f)
    }

    @Test
    fun imagePoint_mapsInsideAndIgnoresLetterbox() {
        val bounds = fittedImageBounds(IntSize(1000, 1000), IntSize(2000, 1000))
        assertEquals(PhotoPoint(0.5f, 0.5f), imagePointAt(Offset(500f, 500f), bounds))
        assertNull(imagePointAt(Offset(500f, 100f), bounds))
    }

    @Test
    fun eraseRegions_areBrushSquaresInBitmapPixels() {
        val regions = eraseRegionsFor(listOf(PhotoPoint(0.5f, 0.5f)), 2000, 1000)
        val radius = 30 // 3% of the 1000 px short side
        assertEquals(1, regions.size)
        assertEquals(1000 - radius, regions[0].x)
        assertEquals(500 - radius, regions[0].y)
        assertEquals(radius * 2, regions[0].width)
        assertEquals(radius * 2, regions[0].height)
    }

    @Test
    fun brushPoints_skipDabsCloserThanHalfARadius() {
        val first = listOf(PhotoPoint(0.5f, 0.5f))
        assertEquals(1, appendBrushPoint(first, PhotoPoint(0.505f, 0.5f), 1f).size)
        assertEquals(2, appendBrushPoint(first, PhotoPoint(0.6f, 0.5f), 1f).size)
    }
}
