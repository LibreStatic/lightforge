package com.librestatic.lightforge.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCameraFrameTest {
    private val source = MediaKey("external_primary", 42)
    private fun metadata(flip: Int = 0, crop: RawCameraCrop? = RawCameraCrop(200, 150, 3_600, 2_700)) = RawMetadata(
        width = crop?.width ?: 4_000, height = crop?.height ?: 3_000, bitsPerSample = 14,
        blackLevel = 0, whiteLevel = 16_383, fullWidth = 4_000, fullHeight = 3_000,
        cameraCrop = crop, flip = flip,
    )
    private fun history() = EditHistory.initial(EditRecipe.forSource(source, 1))

    @Test
    fun frameIsNullWithoutMeaningfulCrop() {
        assertNull(metadata(crop = null).cameraFrame(emptyList()))
        assertNull(metadata(crop = RawCameraCrop(0, 0, 3_980, 2_990)).cameraFrame(emptyList()))
    }

    @Test
    fun frameFollowsOrientationAndGeometry() {
        assertEquals(EditOperation.Crop(50, 50, 950, 950), metadata().cameraFrame(emptyList()))
        val offset = metadata(crop = RawCameraCrop(400, 300, 3_000, 1_500))
        assertEquals(EditOperation.Crop(100, 100, 850, 600), offset.cameraFrame(emptyList()))
        assertEquals(EditOperation.Crop(400, 100, 900, 850), metadata(6, offset.cameraCrop).cameraFrame(emptyList()))
        assertEquals(EditOperation.Crop(100, 150, 600, 900), metadata(5, offset.cameraCrop).cameraFrame(emptyList()))
        assertEquals(EditOperation.Crop(150, 400, 900, 900), metadata(3, offset.cameraCrop).cameraFrame(emptyList()))
        assertEquals(
            EditOperation.Crop(150, 400, 900, 900),
            offset.cameraFrame(listOf(EditOperation.Rotate(90), EditOperation.Tone(), EditOperation.Rotate(90))),
        )
        assertEquals(
            EditOperation.Crop(150, 100, 900, 600),
            offset.cameraFrame(listOf(EditOperation.Flip(horizontal = true))),
        )
    }

    @Test
    fun withinAndRelativeToRoundTrip() {
        val frame = EditOperation.Crop(50, 50, 950, 950)
        assertEquals(frame, EditOperation.Crop(0, 0, 1_000, 1_000).within(frame))
        assertNull(frame.relativeTo(frame))
        assertEquals(EditOperation.Crop(500, 500, 950, 950), EditOperation.Crop(500, 500, 1_000, 1_000).within(frame))
        // Absolute -> relative -> absolute is exact for every value because the frame is coarser.
        for (value in 51..949) {
            val crop = EditOperation.Crop(value, value, 950, 950)
            assertEquals(crop, crop.relativeTo(frame)?.within(frame))
        }
        // Relative -> absolute -> relative drifts at most once and is stable afterwards.
        for (value in 0..998) {
            val crop = EditOperation.Crop(value, value, 1_000, 1_000)
            val once = crop.within(frame).relativeTo(frame) ?: continue
            assertTrue(kotlin.math.abs(once.leftPermille - value) <= 1)
            assertEquals(once, once.within(frame).relativeTo(frame))
        }
        assertNull(EditOperation.Crop(0, 0, 40, 40).relativeTo(frame))
    }

    @Test
    fun togglingFramingRemapsCropAndKeepsOneUndoStep() {
        val raw = metadata()
        val on = RawDevelopmentSettings()
        val off = on.copy(cameraFraming = false)
        val cropped = history().apply(EditOperation.Crop(500, 500, 1_000, 1_000))
        val unframed = cropped.applyRawDevelopment(off, raw)
        assertEquals(
            listOf(EditOperation.Crop(500, 500, 950, 950), EditOperation.RawDevelop(off)),
            unframed.present.operations,
        )
        assertEquals(cropped.present, unframed.undo().present)
        val reframed = unframed.applyRawDevelopment(on, raw)
        assertEquals(
            listOf(EditOperation.Crop(500, 500, 1_000, 1_000), EditOperation.RawDevelop(on)),
            reframed.present.operations,
        )
        // No crop stays no crop; the full-frame crop is dropped when it equals the camera frame.
        assertEquals(listOf<EditOperation>(EditOperation.RawDevelop(off)), history().applyRawDevelopment(off, raw).present.operations)
        val atFrame = history().applyRawDevelopment(off, raw)
            .apply(EditOperation.Crop(50, 50, 950, 950)).applyRawDevelopment(on, raw)
        assertEquals(listOf<EditOperation>(EditOperation.RawDevelop(on)), atFrame.present.operations)
        // Without metadata (or an unchanged switch) nothing is remapped.
        assertEquals(
            listOf(EditOperation.Crop(500, 500, 1_000, 1_000), EditOperation.RawDevelop(off)),
            cropped.applyRawDevelopment(off).present.operations,
        )
    }

    @Test
    fun fullSensorCropIsOneUndoStep() {
        val on = RawDevelopmentSettings()
        val base = history().apply(EditOperation.Crop(50, 50, 950, 950)).apply(EditOperation.Straighten(3f))
        val updated = base.applyFullSensorCrop(on, EditOperation.Crop(0, 0, 800, 800), EditOperation.Straighten(0f))
        assertEquals(
            listOf(EditOperation.Crop(0, 0, 800, 800), EditOperation.RawDevelop(on.copy(cameraFraming = false))),
            updated.present.operations,
        )
        assertEquals(base.present, updated.undo().present)
        val removed = base.applyFullSensorCrop(on, null, EditOperation.Straighten(2f))
        assertEquals(
            listOf(EditOperation.Straighten(2f), EditOperation.RawDevelop(on.copy(cameraFraming = false))),
            removed.present.operations,
        )
    }
}
