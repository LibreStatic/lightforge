package com.librestatic.lightforge.feature.photoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCropDraftTest {
    @Test
    fun squarePresetAccountsForLandscapeSourceAspect() {
        val crop = cropForAspect(PhotoCropDraft(), ratio = 1f, sourceAspectRatio = 2f)

        assertEquals(1f, crop.width * 2f / crop.height, 0.001f)
        assertEquals(1f, crop.aspectRatio)
    }

    @Test
    fun aspectLockedResizeKeepsVisualRatioAndBounds() {
        val crop = resizeCrop(
            start = PhotoCropDraft(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f, aspectRatio = 1f),
            handle = CropHandle.BottomRight,
            dx = -0.2f,
            dy = -0.05f,
            imageAspectRatio = 2f,
        )

        assertEquals(1f, crop.width * 2f / crop.height, 0.001f)
        assertTrue(crop.left >= 0f && crop.top >= 0f && crop.right <= 1f && crop.bottom <= 1f)
    }

    @Test
    fun movingCropClampsWithoutChangingItsSize() {
        val start = PhotoCropDraft(left = 0.2f, top = 0.2f, right = 0.7f, bottom = 0.8f)
        val crop = resizeCrop(start, CropHandle.Move, dx = 1f, dy = -1f, imageAspectRatio = 1f)

        assertEquals(start.width, crop.width, 0.001f)
        assertEquals(start.height, crop.height, 0.001f)
        assertEquals(1f, crop.right, 0.001f)
        assertEquals(0f, crop.top, 0.001f)
    }

    @Test
    fun rotatingTheDraftMatchesTheHistoryCropRotation() {
        val draft = PhotoCropDraft(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.9f, aspectRatio = 16f / 9f)
        val rotated = draft.rotatedClockwise()
        val expected = cropEditOperations(draft).first() as com.librestatic.lightforge.core.model.EditOperation.Crop

        assertEquals(0.1f, rotated.left, 0.001f)
        assertEquals(0.1f, rotated.top, 0.001f)
        assertEquals(0.8f, rotated.right, 0.001f)
        assertEquals(0.6f, rotated.bottom, 0.001f)
        assertEquals(9f / 16f, rotated.aspectRatio)
        assertEquals(1_000 - expected.bottomPermille, (rotated.left * 1_000).toInt())
    }

    @Test
    fun flippingTheDraftMirrorsTheRectangleAndStraighten() {
        val flipped = PhotoCropDraft(left = 0.1f, right = 0.6f, straightenDegrees = 4f).flippedHorizontally()

        assertEquals(0.4f, flipped.left, 0.001f)
        assertEquals(0.9f, flipped.right, 0.001f)
        assertEquals(-4f, flipped.straightenDegrees, 0.001f)
    }
}
