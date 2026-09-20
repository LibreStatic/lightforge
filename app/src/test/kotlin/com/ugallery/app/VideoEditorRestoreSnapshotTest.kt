package com.ugallery.app

import com.ugallery.core.editing.video.*
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.selection.MediaQuery
import java.io.*
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class VideoEditorRestoreSnapshotTest {
    private val id = "655ff1b7-a6e4-4e99-8759-2f492d4f903d"
    private val job = "ad8c20dd-f592-4ef0-aa35-e9058201072a"
    private fun layer(id: String = "layer-1", end: Long = 8_000) = VideoAnnotationLayer(
        id = id, shape = VideoAnnotationShape.Rectangle,
        points = listOf(NormalizedPoint(.1f, .2f), NormalizedPoint(.6f, .8f)),
        startMillis = 1_000, endMillis = end,
        trackingMode = VideoAnnotationTrackingMode.Keyframes,
        keyframes = listOf(VideoAnnotationKeyframe(1_000, VideoAnnotationTransform()),
            VideoAnnotationKeyframe(end, VideoAnnotationTransform(translationX = .2f))),
    )
    private fun recipe() = VideoEditRecipe(startMillis = 500, endMillis = 9_000, speed = 1.5f,
        geometry = VideoGeometry(left = .1f, rotationDegrees = 90f),
        colorGrade = VideoColorGrade(exposureEv = 1.25f, lut = LutReference(customId = 42)),
        originalAudioVolume = .4f, musicVolume = .2f,
        slowMotionSegments = listOf(SlowMotionSegment("slow-1", 2_000, 4_000)),
        annotations = listOf(layer()),
    )
    private fun snapshot() = VideoEditorRestoreSnapshot(
        id, ViewerRestoreSnapshot(MediaKey("external_primary", 52), MediaKind.Video, 7, 3, false, MediaQuery()),
        10_000, VideoEditorRestoreSnapshot.encodeRecipeExact(recipe()),
        VideoEditorRestoreSnapshot.encodeRecipeExact(VideoEditRecipe()), 3_500,
        selectedAnnotationId = "layer-1", selectedSlowMotionSegmentId = "slow-1",
        slowMotionMarkInMillis = 5_000, annotationTrackingCorrectionMillis = 3_000,
        undoAnnotations = listOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(emptyList())),
        redoAnnotations = listOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(listOf(layer("redo")))),
    )
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected rejected recovery state") } catch (_: IllegalArgumentException) { }
    }
    private fun changed(encoded: String, key: String, value: String?): String {
        val p = Properties().apply { load(StringReader(encoded)) }
        if (value == null) p.remove(key) else p.setProperty(key, value)
        return StringWriter().also { p.store(it, null) }.toString()
    }

    @Test fun restoresCompleteRecipeDistinctBaselineHistoryAndSelectors() {
        val value = snapshot()
        val restored = VideoEditorRestoreSnapshot.validatedCopy(value)
        assertEquals(recipe(), VideoEditorRestoreSnapshot.decodeRecipeExact(restored.recipe))
        assertEquals(VideoEditRecipe(), VideoEditorRestoreSnapshot.decodeRecipeExact(restored.baselineRecipe))
        assertEquals(value, restored)
        assertNotSame(value.source, restored.source)
        assertEquals(emptyList<VideoAnnotationLayer>(), VideoEditorRestoreSnapshot.decodeAnnotationsExact(restored.undoAnnotations.single()))
        assertEquals(listOf(layer("redo")), VideoEditorRestoreSnapshot.decodeAnnotationsExact(restored.redoAnnotations.single()))
    }

    @Test fun javaSerializationKeepsExactReviewState() {
        val value = snapshot()
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(value) } }.toByteArray()
        val read = ObjectInputStream(bytes.inputStream()).use { it.readObject() } as VideoEditorRestoreSnapshot
        assertEquals(value, VideoEditorRestoreSnapshot.validatedCopy(read))
    }

    @Test fun ignoresTimestampCommentsButNotUnknownMissingOrDuplicateProperties() {
        val encoded = snapshot().recipe
        assertEquals(recipe(), VideoEditorRestoreSnapshot.decodeRecipeExact("# another timestamp\n" + encoded))
        listOf(changed(encoded, "version", "4"), changed(encoded, "speed", null),
            changed(encoded, "extra", "ignored"), encoded + "\nspeed=1.5\n").forEach {
            invalid { VideoEditorRestoreSnapshot.decodeRecipeExact(it) }
        }
    }

    @Test fun rejectsSilentCodecFallbackForGeometrySegmentsBandsAndAnnotations() {
        val encoded = snapshot().recipe
        listOf("geometry" to "bad", "slowSegments" to "bad", "bands" to "bad", "annotations" to "bad",
            "quality" to "NotAQuality", "speed" to "NaN").forEach { (key, value) ->
            invalid { VideoEditorRestoreSnapshot.decodeRecipeExact(changed(encoded, key, value)) }
        }
    }

    @Test fun rejectsNoncanonicalLiveRecipesInsteadOfDroppingDuplicateAnnotationIds() {
        invalid { VideoEditorRestoreSnapshot.encodeRecipeExact(recipe().copy(annotations = listOf(layer(), layer()))) }
    }

    @Test fun rejectsMalformedHistoryIncludingTrailingBytes() {
        val good = VideoEditorRestoreSnapshot.encodeAnnotationsExact(listOf(layer()))
        invalid { VideoEditorRestoreSnapshot.decodeAnnotationsExact("bad") }
        val extra = java.util.Base64.getEncoder().encodeToString(java.util.Base64.getDecoder().decode(good) + byteArrayOf(0))
        invalid { VideoEditorRestoreSnapshot.decodeAnnotationsExact(extra) }
    }

    @Test fun sourceMustBeUntrashedVideoWithValidGenerationsAndUuid() {
        val value = snapshot()
        listOf(value.copy(id = "other"), value.copy(source = value.source.copy(kind = MediaKind.Image)),
            value.copy(source = value.source.copy(isTrashed = true)),
            value.copy(source = value.source.copy(generationAdded = -1)),
            value.copy(source = value.source.copy(generationModified = -1))).forEach {
            invalid { VideoEditorRestoreSnapshot.validatedCopy(it) }
        }
    }

    @Test fun rejectsPositionSelectorsAndMarksOutsideRestoredTrim() {
        val value = snapshot()
        listOf(value.copy(positionMillis = 499), value.copy(positionMillis = 9_000),
            value.copy(selectedAnnotationId = "absent"), value.copy(selectedSlowMotionSegmentId = "absent"),
            value.copy(slowMotionMarkInMillis = 9_000), value.copy(annotationTrackingCorrectionMillis = -1)).forEach {
            invalid { VideoEditorRestoreSnapshot.validatedCopy(it) }
        }
    }

    @Test fun validatesBaselinePendingAndHistoryAgainstSourceDuration() {
        val value = snapshot()
        val tooLong = VideoEditorRestoreSnapshot.encodeRecipeExact(VideoEditRecipe(endMillis = 10_001))
        listOf(value.copy(durationMillis = 0), value.copy(baselineRecipe = tooLong),
            value.copy(pendingExportRecipe = tooLong, exportJobId = job),
            value.copy(redoAnnotations = listOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(listOf(layer(end = 10_001)))))).forEach {
            invalid { VideoEditorRestoreSnapshot.validatedCopy(it) }
        }
    }

    @Test fun retainsCompletedJobWithoutPendingRecipeAndChecksActiveRecipe() {
        val value = snapshot()
        assertEquals(job, VideoEditorRestoreSnapshot.validatedCopy(value.copy(exportJobId = job)).exportJobId)
        assertEquals(value.recipe, VideoEditorRestoreSnapshot.validatedCopy(value.copy(pendingExportRecipe = value.recipe, exportJobId = job)).pendingExportRecipe)
        invalid { VideoEditorRestoreSnapshot.validatedCopy(value.copy(pendingExportRecipe = value.recipe)) }
    }

    @Test fun rejectsOversizedCombinedInputBeforeDecodingAndOverlongHistory() {
        val value = snapshot()
        invalid { VideoEditorRestoreSnapshot.validatedCopy(value.copy(recipe = "x".repeat(65_537))) }
        invalid { VideoEditorRestoreSnapshot.validatedCopy(value.copy(undoAnnotations = List(101) { value.undoAnnotations.single() })) }
        invalid { VideoEditorRestoreSnapshot.validatedCopy(value.copy(redoAnnotations = List(100) { "x".repeat(660) })) }
        invalid { VideoEditorRestoreSnapshot.decodeRecipeExact("é".repeat(32_769)) }
    }

    @Test fun detachesHistoryAndNestedDecodedAnnotationCollections() {
        val input = mutableListOf(VideoEditorRestoreSnapshot.encodeAnnotationsExact(listOf(layer())))
        val value = VideoEditorRestoreSnapshot.validatedCopy(snapshot().copy(undoAnnotations = input))
        input.clear()
        assertEquals(1, value.undoAnnotations.size)
        try { (value.undoAnnotations as MutableList<String>).clear(); fail("Mutable history") }
        catch (_: UnsupportedOperationException) { }
        val decoded = VideoEditorRestoreSnapshot.decodeAnnotationsExact(value.undoAnnotations.single())
        try { (decoded.single().points as MutableList<NormalizedPoint>).clear(); fail("Mutable points") }
        catch (_: UnsupportedOperationException) { }
    }
}
