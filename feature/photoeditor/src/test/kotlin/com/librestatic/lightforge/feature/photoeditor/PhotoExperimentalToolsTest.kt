package com.librestatic.lightforge.feature.photoeditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(2, appendBrushPoint(first, PhotoPoint(0.6f, 0.5f), 1f, connect = false).size)
    }

    @Test
    fun brushPoints_fillTheGapOfAFastDrag() {
        val first = listOf(PhotoPoint(0.5f, 0.5f))
        val stroke = appendBrushPoint(first, PhotoPoint(0.6f, 0.5f), 1f)
        assertEquals(PhotoPoint(0.6f, 0.5f), stroke.last())
        stroke.zipWithNext().forEach { (a, b) ->
            assertTrue(b.x - a.x <= PHOTO_ERASE_BRUSH_RADIUS / 2f + 1e-4f)
        }
    }

    @Test
    fun marks_roundTripThroughRotateFlipAndCrop() {
        val geometry = listOf(
            com.librestatic.lightforge.core.model.EditOperation.Rotate(90),
            com.librestatic.lightforge.core.model.EditOperation.Flip(horizontal = true),
            com.librestatic.lightforge.core.model.EditOperation.Crop(100, 200, 900, 800),
        )
        val source = PhotoPoint(0.3f, 0.6f)
        val edited = projectToEdited(source, geometry)!!
        val back = projectToSource(edited, geometry)!!
        assertEquals(source.x, back.x, 0.0001f)
        assertEquals(source.y, back.y, 0.0001f)
    }

    @Test
    fun rotate90_movesTopLeftToTopRight() {
        val edited = projectToEdited(PhotoPoint(0f, 0f), listOf(com.librestatic.lightforge.core.model.EditOperation.Rotate(90)))!!
        assertEquals(1f, edited.x, 0.0001f)
        assertEquals(0f, edited.y, 0.0001f)
    }

    @Test
    fun cropHidesMarksOutsideTheFrame() {
        assertNull(projectToEdited(PhotoPoint(0.05f, 0.5f), listOf(com.librestatic.lightforge.core.model.EditOperation.Crop(100, 0, 1000, 1000))))
    }

    @Test
    fun straightenIsNotProjectable() {
        val geometry = photoGeometryOperations(listOf(com.librestatic.lightforge.core.model.EditOperation.Straighten(5f)))
        assertEquals(false, photoGeometryProjectable(geometry))
        assertEquals(true, photoGeometryProjectable(photoGeometryOperations(listOf(com.librestatic.lightforge.core.model.EditOperation.Straighten(0f)))))
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: PhotoPoint) {
        assertEquals(expectedX, actual.x, 0.0001f)
        assertEquals(expectedY, actual.y, 0.0001f)
    }

    @Test
    fun pendingHorizontalStroke_turnsVerticalAfterRotate() {
        // Centre -> right of centre, drawn before Rotate; after a quarter turn it points down.
        val stroke = listOf(PhotoPoint(0.5f, 0.5f), PhotoPoint(0.7f, 0.5f), PhotoPoint(0.9f, 0.5f))
        val rotated = reprojectEditedPoints(stroke, emptyList(), listOf(com.librestatic.lightforge.core.model.EditOperation.Rotate(90)))
        assertEquals(3, rotated.size)
        assertPoint(0.5f, 0.5f, rotated[0])
        assertPoint(0.5f, 0.7f, rotated[1])
        assertPoint(0.5f, 0.9f, rotated[2])
    }

    @Test
    fun pendingStroke_followsFlipAndCropAndUndo() {
        val point = listOf(PhotoPoint(0.8f, 0.3f))
        val flip = listOf(com.librestatic.lightforge.core.model.EditOperation.Flip(horizontal = true))
        assertPoint(0.2f, 0.3f, reprojectEditedPoints(point, emptyList(), flip).single())
        val vertical = listOf(com.librestatic.lightforge.core.model.EditOperation.Flip(horizontal = false))
        assertPoint(0.8f, 0.7f, reprojectEditedPoints(point, emptyList(), vertical).single())
        val crop = listOf(com.librestatic.lightforge.core.model.EditOperation.Crop(500, 0, 1000, 1000))
        assertPoint(0.6f, 0.3f, reprojectEditedPoints(point, emptyList(), crop).single())
        // Undo back to no geometry puts the dab where it was drawn.
        val rotated = listOf(com.librestatic.lightforge.core.model.EditOperation.Rotate(90))
        assertPoint(0.8f, 0.3f, reprojectEditedPoints(reprojectEditedPoints(point, emptyList(), rotated), rotated, emptyList()).single())
    }

    @Test
    fun pendingStroke_dropsCroppedOutDabsAndClearsUnderStraighten() {
        val stroke = listOf(PhotoPoint(0.1f, 0.5f), PhotoPoint(0.9f, 0.5f))
        val crop = listOf(com.librestatic.lightforge.core.model.EditOperation.Crop(500, 0, 1000, 1000))
        assertEquals(1, reprojectEditedPoints(stroke, emptyList(), crop).size)
        val straighten = photoGeometryOperations(listOf(com.librestatic.lightforge.core.model.EditOperation.Straighten(5f)))
        assertEquals(emptyList<PhotoPoint>(), reprojectEditedPoints(stroke, emptyList(), straighten))
    }

    @Test
    fun rotateThenCrop_projectsThroughBothInOrder() {
        val geometry = listOf(
            com.librestatic.lightforge.core.model.EditOperation.Rotate(90),
            com.librestatic.lightforge.core.model.EditOperation.Crop(0, 500, 1000, 1000),
        )
        // Source (0.75, 0.5) -> rotate (0.5, 0.75) -> crop bottom half (0.5, 0.5).
        assertPoint(0.5f, 0.5f, projectToEdited(PhotoPoint(0.75f, 0.5f), geometry)!!)
        assertPoint(0.75f, 0.5f, projectToSource(PhotoPoint(0.5f, 0.5f), geometry)!!)
    }
}
