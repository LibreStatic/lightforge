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

class PhotoCropOperationsTest {
    private val tilted = withVerticalKeystone(IdentityCorners, 0.5f)

    @Test
    fun identityPerspectiveEmitsOnlyCropAndStraighten() {
        val ops = cropEditOperations(PhotoCropDraft(straightenDegrees = 2f))

        assertEquals(2, ops.size)
        assertTrue(ops[0] is com.librestatic.lightforge.core.model.EditOperation.Crop)
        assertTrue(ops[1] is com.librestatic.lightforge.core.model.EditOperation.Straighten)
    }

    @Test
    fun perspectiveComesFirstThenCropThenStraighten() {
        val ops = cropEditOperations(PhotoCropDraft(left = 0.1f, perspective = tilted, straightenDegrees = -3f))

        assertEquals(3, ops.size)
        assertEquals(com.librestatic.lightforge.core.model.EditOperation.Perspective(tilted), ops[0])
        assertTrue(ops[1] is com.librestatic.lightforge.core.model.EditOperation.Crop)
        assertTrue(ops[2] is com.librestatic.lightforge.core.model.EditOperation.Straighten)
    }

    @Test
    fun anInvalidQuadIsDroppedInsteadOfCrashing() {
        val bowtie = listOf(
            com.librestatic.lightforge.core.model.NormalizedPoint(0f, 0f),
            com.librestatic.lightforge.core.model.NormalizedPoint(1f, 1f),
            com.librestatic.lightforge.core.model.NormalizedPoint(1f, 0f),
            com.librestatic.lightforge.core.model.NormalizedPoint(0f, 1f),
        )

        assertEquals(2, cropEditOperations(PhotoCropDraft(perspective = bowtie)).size)
    }

    @Test
    fun changingThePerspectiveResetsTheCropRectangle() {
        val draft = PhotoCropDraft(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f, aspectRatio = 1f)

        val warped = draft.withPerspective(tilted)

        assertEquals(PhotoCropDraft(perspective = tilted), warped)
        assertEquals(draft, draft.withPerspective(IdentityCorners))
    }

    @Test
    fun draftRotationAndFlipsCarryThePerspective() {
        val draft = PhotoCropDraft(perspective = tilted)

        assertEquals(tilted.rotatedClockwise(), draft.rotatedClockwise().perspective)
        assertEquals(tilted.flipped(true), draft.flippedHorizontally().perspective)
        assertEquals(tilted.flipped(false), draft.flippedVertically().perspective)
        assertEquals(tilted.size, draft.flippedVertically().flippedVertically().perspective.size)
    }

    @Test
    fun verticalFlipMirrorsTheRectangleAndStraighten() {
        val flipped = PhotoCropDraft(top = 0.1f, bottom = 0.6f, straightenDegrees = 4f).flippedVertically()

        assertEquals(0.4f, flipped.top, 0.001f)
        assertEquals(0.9f, flipped.bottom, 0.001f)
        assertEquals(-4f, flipped.straightenDegrees, 0.001f)
    }

    @Test
    fun aspectPresetsCoverTheRequestedRatiosAndOriginalFollowsTheSource() {
        val presets = cropAspectPresets(sourceAspectRatio = 1.25f)

        assertEquals(11, presets.size)
        assertEquals(null, presets.first().ratio)
        assertEquals(1.25f, presets.first { it.token == "original" }.ratio)
        assertTrue(presets.map { it.token }.containsAll(listOf("square", "4-3", "3-4", "3-2", "2-3", "5-4", "4-5", "16-9", "9-16")))
        assertEquals(presets.size, presets.map { it.token }.toSet().size)
    }
}
