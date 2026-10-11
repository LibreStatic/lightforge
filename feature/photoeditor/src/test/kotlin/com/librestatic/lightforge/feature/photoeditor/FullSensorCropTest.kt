package com.librestatic.lightforge.feature.photoeditor

import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSensorCropTest {
    private val frame = EditOperation.Crop(50, 50, 950, 950)

    @Test
    fun untouchedConvertedDraftIsNotAChange() {
        val baseline = EditOperation.Crop(105, 205, 805, 705)
        val draft = baseline.toDraft().toFullSensor(frame)
        val outcome = cropOutcome(draft, frame, baseline)
        assertFalse(outcome.needsFullSensor)
        assertEquals(baseline, outcome.crop)
        val noCrop = cropOutcome(PhotoCropDraft().toFullSensor(frame), frame, baseline = null)
        assertEquals(EditOperation.Crop(0, 0, 1_000, 1_000), noCrop.crop)
        assertFalse(noCrop.needsFullSensor)
    }

    @Test
    fun draftInsideFrameBecomesAFramedCrop() {
        val draft = PhotoCropDraft(left = 0.5f, top = 0.5f, right = 0.95f, bottom = 0.95f, fullSensor = true)
        val outcome = cropOutcome(draft, frame, baseline = null)
        assertFalse(outcome.needsFullSensor)
        assertEquals(EditOperation.Crop(500, 500, 1_000, 1_000), outcome.crop)
    }

    @Test
    fun draftLeavingTheFrameNeedsTheFullSensor() {
        val outcome = cropOutcome(
            PhotoCropDraft(left = 0.02f, right = 0.9f, straightenDegrees = 3f, fullSensor = true), frame, null,
        )
        assertTrue(outcome.needsFullSensor)
        assertEquals(EditOperation.Crop(20, 0, 900, 1_000), outcome.crop)
        assertEquals(3f, outcome.straightenDegrees)
    }

    @Test
    fun framedAndFullSensorCoordinatesConvertBack() {
        val draft = PhotoCropDraft(left = 0.2f, top = 0.1f, right = 0.8f, bottom = 0.7f)
        val back = draft.toFullSensor(frame).toFramed(frame)
        assertEquals(draft.left, back.left, 1e-4f)
        assertEquals(draft.bottom, back.bottom, 1e-4f)
        assertFalse(back.fullSensor)
        assertEquals(draft.resetToFrame(frame).left, 0.05f, 1e-6f)
    }

    @Test
    fun nonFullSensorDraftKeepsTheExistingCropRules() {
        val draft = PhotoCropDraft(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.9f)
        val outcome = cropOutcome(draft, frame, baseline = null)
        assertEquals(cropEditOperations(draft), outcome.operations)
        assertFalse(outcome.needsFullSensor)
    }

    @Test
    fun untouchedDraftOnATightFrameIsNotAChange() {
        // z46: 653 x 873 framed inside 3000 x 4000, so one absolute permille is ~5 relative ones.
        val tight = EditOperation.Crop(391, 391, 609, 609)
        val baseline = EditOperation.Crop(123, 457, 877, 911)
        val outcome = cropOutcome(baseline.toDraft().toFullSensor(tight), tight, baseline)
        assertFalse(outcome.needsFullSensor)
        assertEquals(baseline, outcome.crop)
    }

    @Test
    fun resetFullSensorDraftRemovesTheCameraFraming() {
        val outcome = cropOutcome(PhotoCropDraft(fullSensor = true), frame, baseline = null)
        assertTrue(outcome.needsFullSensor)
        assertTrue(outcome.crop.isFullFrame)
    }
}
