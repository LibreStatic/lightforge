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

    @Test
    fun marks_roundTripThroughRotateFlipAndCrop() {
        val geometry = listOf(
            com.ugallery.core.model.EditOperation.Rotate(90),
            com.ugallery.core.model.EditOperation.Flip(horizontal = true),
            com.ugallery.core.model.EditOperation.Crop(100, 200, 900, 800),
        )
        val source = PhotoPoint(0.3f, 0.6f)
        val edited = projectToEdited(source, geometry)!!
        val back = projectToSource(edited, geometry)!!
        assertEquals(source.x, back.x, 0.0001f)
        assertEquals(source.y, back.y, 0.0001f)
    }

    @Test
    fun rotate90_movesTopLeftToTopRight() {
        val edited = projectToEdited(PhotoPoint(0f, 0f), listOf(com.ugallery.core.model.EditOperation.Rotate(90)))!!
        assertEquals(1f, edited.x, 0.0001f)
        assertEquals(0f, edited.y, 0.0001f)
    }

    @Test
    fun cropHidesMarksOutsideTheFrame() {
        assertNull(projectToEdited(PhotoPoint(0.05f, 0.5f), listOf(com.ugallery.core.model.EditOperation.Crop(100, 0, 1000, 1000))))
    }

    @Test
    fun straightenIsNotProjectable() {
        val geometry = photoGeometryOperations(listOf(com.ugallery.core.model.EditOperation.Straighten(5f)))
        assertEquals(false, photoGeometryProjectable(geometry))
        assertEquals(true, photoGeometryProjectable(photoGeometryOperations(listOf(com.ugallery.core.model.EditOperation.Straighten(0f)))))
    }
}
