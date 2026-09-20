package com.ugallery.app

import com.ugallery.core.editing.video.*
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class ExternalVideoEditorRestoreSnapshotTest {
    private val id = "655ff1b7-a6e4-4e99-8759-2f492d4f903d"
    private val job = "ad8c20dd-f592-4ef0-aa35-e9058201072a"
    private fun source() = ExternalVideoSourceSnapshot("content://fixture.documents/document/$id", "video/mp4",
        "Reviewed video.mp4", 1920, 1080, 10_000, 145922,
        "ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861")
    private fun layer(id: String = "annotation-1", end: Long = 8_000) = VideoAnnotationLayer(id = id,
        shape = VideoAnnotationShape.Rectangle, points = listOf(NormalizedPoint(.1f, .2f), NormalizedPoint(.7f, .8f)),
        startMillis = 1_000, endMillis = end)
    private fun recipe() = VideoEditRecipe(startMillis = 500, endMillis = 9_000, speed = 1.5f,
        geometry = VideoGeometry(left = .1f, rotationDegrees = 90f), originalAudioVolume = .4f,
        colorGrade = VideoColorGrade(exposureEv = 1.25f, lut = LutReference(customId = 42)),
        slowMotionSegments = listOf(SlowMotionSegment("slow-1", 2_000, 4_000)), annotations = listOf(layer()))
    private fun snapshot() = ExternalVideoEditorRestoreSnapshot(id, source(), 10_000,
        VideoEditorRestoreSnapshot.encodeRecipeExact(recipe()),
        VideoEditorRestoreSnapshot.encodeRecipeExact(VideoEditRecipe()), 3_000,
        selectedAnnotationId = "annotation-1", selectedSlowMotionSegmentId = "slow-1",
        slowMotionMarkInMillis = 5_000, annotationTrackingCorrectionMillis = 3_500,
        undoAnnotations = listOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(emptyList())),
        redoAnnotations = listOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(listOf(layer("redo")))))
    private fun invalid(value: ExternalVideoEditorRestoreSnapshot) {
        assertNull(ExternalVideoEditorRestoreSnapshot.validatedOrNull(value))
        try { ExternalVideoEditorRestoreSnapshot.validatedCopy(value); fail("Invalid external draft accepted") }
        catch (_: Exception) { }
    }
    private fun bytes(value: Any): ByteArray = ByteArrayOutputStream().also { output ->
        ObjectOutputStream(output).use { it.writeObject(value) }
    }.toByteArray()

    @Test fun loadingSnapshotGuardsSameUriAfterPendingWasConsumed() {
        val saved=snapshot()
        assertTrue(externalVideoDraftIsRestoring(saved,saved.id,true,saved.source.uriString))
        assertTrue(externalVideoDraftIsRestoring(saved,saved.id,true))
    }
    @Test fun loadingGuardNeverAdoptsAnotherSessionOrDifferentSource() {
        val saved=snapshot()
        assertFalse(externalVideoDraftIsRestoring(saved,job,true,saved.source.uriString))
        assertFalse(externalVideoDraftIsRestoring(saved,saved.id,true,"content://fixture.documents/document/other"))
        assertFalse(externalVideoDraftIsRestoring(saved,saved.id,false,saved.source.uriString))
        assertFalse(externalVideoDraftIsRestoring(null,null,true))
    }

    @Test fun completeReviewStateIsDetachedAndPreservesDifferentBaseline() {
        val original = snapshot()
        val value = ExternalVideoEditorRestoreSnapshot.validatedCopy(original)
        assertEquals(original, value); assertNotSame(original, value); assertNotSame(original.source, value.source)
        val common: VideoEditorDraftRestore = value
        assertEquals(recipe(), VideoEditorRestoreSnapshot.decodeRecipeExact(common.recipe))
        assertEquals(VideoEditRecipe(), VideoEditorRestoreSnapshot.decodeRecipeExact(common.baselineRecipe))
        assertEquals(listOf(layer("redo")), VideoEditorRestoreSnapshot.decodeAnnotationsExact(common.redoAnnotations.single()))
        assertTrue(bytes(value).size <= ExternalVideoEditorRestoreSnapshot.MaxSerializedBytes)
    }

    @Test fun javaSerializationRoundtripRevalidatesWithoutAndroidUri() {
        val value = snapshot().copy(pendingExportRecipe = snapshot().recipe, exportJobId = job)
        val read = ObjectInputStream(bytes(value).inputStream()).use { it.readObject() }
        assertEquals(value, ExternalVideoEditorRestoreSnapshot.validatedOrNull(read))
        assertNull(ExternalVideoEditorRestoreSnapshot.validatedOrNull(null))
        assertNull(ExternalVideoEditorRestoreSnapshot.validatedOrNull("not a draft"))
    }

    @Test fun rejectsInvalidProviderUrisAndSourceMetadataWithoutNormalization() {
        val value = snapshot()
        listOf("file:///video.mp4", "https://fixture/video", "content:opaque", "content:///missing-authority",
            "content://fixture.documents/video#fragment", "content://fixture.documents:88/video", "content://fixture.documents/a b").forEach {
            invalid(value.copy(source = value.source.copy(uriString = it)))
        }
        listOf(value.source.copy(mimeType = "image/jpeg"), value.source.copy(mimeType = "VIDEO/MP4"),
            value.source.copy(displayName = " "), value.source.copy(displayName = "name\u0000.mp4"),
            value.source.copy(width = 0), value.source.copy(height = -1), value.source.copy(sizeBytes = 0),
            value.source.copy(durationMillis = 0), value.source.copy(sha256 = "A".repeat(64)),
            value.source.copy(sha256 = "a".repeat(63))).forEach { invalid(value.copy(source = it)) }
    }

    @Test fun rejectsIdentityDurationAndExportInconsistency() {
        val value = snapshot()
        listOf(value.copy(id = "1-1-1-1-1"), value.copy(id = id.uppercase()), value.copy(durationMillis = 9_999),
            value.copy(durationMillis = 0), value.copy(exportJobId = "not-uuid"),
            value.copy(pendingExportRecipe = value.recipe)).forEach(::invalid)
        assertEquals(job, ExternalVideoEditorRestoreSnapshot.validatedCopy(value.copy(exportJobId = job)).exportJobId)
    }

    @Test fun rejectsOutOfTrimPositionSelectorsAndTransientMarks() {
        val value = snapshot()
        listOf(value.copy(positionMillis = 499), value.copy(positionMillis = 9_000),
            value.copy(selectedAnnotationId = "missing"), value.copy(selectedSlowMotionSegmentId = "missing"),
            value.copy(slowMotionMarkInMillis = 9_000), value.copy(annotationTrackingCorrectionMillis = -1)).forEach(::invalid)
    }

    @Test fun enforcesCompleteStrictRecipeCodecForBaselineCurrentAndPending() {
        val value = snapshot()
        val p = Properties().apply { load(StringReader(value.recipe)); setProperty("version", "4") }
        val legacy = StringWriter().also { p.store(it, null) }.toString()
        val tooLong = VideoEditorRestoreSnapshot.encodeRecipeExact(VideoEditRecipe(endMillis = 10_001))
        listOf(value.copy(recipe = legacy), value.copy(baselineRecipe = legacy),
            value.copy(pendingExportRecipe = legacy, exportJobId = job), value.copy(baselineRecipe = tooLong),
            value.copy(pendingExportRecipe = tooLong, exportJobId = job), value.copy(recipe = value.recipe + "\nspeed=1.5\n")).forEach(::invalid)
    }

    @Test fun historiesRejectMalformedTrailingBytesAndOutOfSourceDuration() {
        val value = snapshot()
        val good = value.redoAnnotations.single()
        val trailing = java.util.Base64.getEncoder().encodeToString(java.util.Base64.getDecoder().decode(good) + byteArrayOf(0))
        listOf(value.copy(undoAnnotations = listOf("bad")), value.copy(redoAnnotations = listOf(trailing)),
            value.copy(redoAnnotations = listOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(listOf(layer(end = 10_001))))),
            value.copy(undoAnnotations = List(101) { value.undoAnnotations.single() })).forEach(::invalid)
    }

    @Test fun historyIsNotSharedOrMutableAfterValidation() {
        val history = snapshot().undoAnnotations.toMutableList()
        val value = ExternalVideoEditorRestoreSnapshot.validatedCopy(snapshot().copy(undoAnnotations = history))
        history.clear(); assertEquals(1, value.undoAnnotations.size)
        try { (value.undoAnnotations as MutableList<String>).clear(); fail("Mutable history") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun boundsCombinedInputAndActualSerializedOverhead() {
        val value = snapshot()
        invalid(value.copy(recipe = "x".repeat(65_537)))
        invalid(value.copy(source = value.source.copy(displayName = "é".repeat(32_769))))
        // Keep total raw UTF-8 text below64KiB; Java class descriptors still overflow the real budget.
        val commonText = listOfNotNull(value.id, value.recipe, value.baselineRecipe, value.source.uriString,
            value.source.mimeType, value.source.sha256, value.selectedAnnotationId, value.selectedSlowMotionSegmentId) +
            value.undoAnnotations + value.redoAnnotations
        val used = commonText.sumOf { it.toByteArray(Charsets.UTF_8).size }
        val oversized = value.copy(source = value.source.copy(displayName = "n".repeat(65_536 - used - 1)))
        assertTrue(bytes(oversized).size > 65_536)
        invalid(oversized)
    }
}
