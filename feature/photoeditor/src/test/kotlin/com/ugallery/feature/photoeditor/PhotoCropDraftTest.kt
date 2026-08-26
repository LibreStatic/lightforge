package com.ugallery.feature.photoeditor

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
}
