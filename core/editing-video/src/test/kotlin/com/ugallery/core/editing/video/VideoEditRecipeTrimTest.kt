package com.ugallery.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditRecipeTrimTest {
    @Test
    fun trimClipsTimedEditsAndDropsEditsOutsideTheRange() {
        val retainedSegment = SlowMotionSegment(id = "retained", startMillis = 1_000, endMillis = 5_000)
        val droppedSegment = SlowMotionSegment(id = "dropped", startMillis = 7_000, endMillis = 8_000)
        val retainedLayer = layer(id = "retained", startMillis = 500, endMillis = 6_000)
        val droppedLayer = layer(id = "dropped", startMillis = 8_000, endMillis = 9_000)

        val trimmed = VideoEditRecipe(
            slowMotionSegments = listOf(retainedSegment, droppedSegment),
            annotations = listOf(retainedLayer, droppedLayer),
        ).withTrimRange(startMillis = 2_000, endMillis = 6_500)

        assertEquals(2_000, trimmed.startMillis)
        assertEquals(6_500L, trimmed.endMillis)
        assertEquals(listOf("retained"), trimmed.slowMotionSegments.map(SlowMotionSegment::id))
        assertEquals(2_000, trimmed.slowMotionSegments.single().startMillis)
        assertEquals(5_000, trimmed.slowMotionSegments.single().endMillis)
        assertEquals(listOf("retained"), trimmed.annotations.map(VideoAnnotationLayer::id))
        assertEquals(2_000, trimmed.annotations.single().startMillis)
        assertEquals(6_000, trimmed.annotations.single().endMillis)
    }

    @Test
    fun trimPreservesInterpolatedTransformsAtNewAnnotationBoundaries() {
        val startTransform = VideoAnnotationTransform(translationX = 0f, scaleX = 1f, scaleY = 1f)
        val endTransform = VideoAnnotationTransform(translationX = 1f, scaleX = 3f, scaleY = 3f)
        val layer = layer(
            id = "tracked",
            startMillis = 0,
            endMillis = 10_000,
            trackingMode = VideoAnnotationTrackingMode.Keyframes,
            keyframes = listOf(
                VideoAnnotationKeyframe(0, startTransform, confidence = 0.8f),
                VideoAnnotationKeyframe(10_000, endTransform, confidence = 0.4f),
            ),
        )

        val trimmedLayer = VideoEditRecipe(annotations = listOf(layer))
            .withTrimRange(startMillis = 2_500, endMillis = 7_500)
            .annotations
            .single()

        assertEquals(listOf(2_500L, 7_500L), trimmedLayer.keyframes.map(VideoAnnotationKeyframe::timeMillis))
        assertEquals(0.25f, trimmedLayer.keyframes.first().transform.translationX, 0.0001f)
        assertEquals(1.5f, trimmedLayer.keyframes.first().transform.scaleX, 0.0001f)
        assertEquals(0.75f, trimmedLayer.keyframes.last().transform.translationX, 0.0001f)
        assertEquals(2.5f, trimmedLayer.keyframes.last().transform.scaleX, 0.0001f)
        assertEquals(0.7f, trimmedLayer.keyframes.first().confidence, 0.0001f)
        assertEquals(0.5f, trimmedLayer.keyframes.last().confidence, 0.0001f)
    }

    @Test
    fun trimKeepsFixedAnnotationTransformWhenItsOriginalKeyframeIsRemoved() {
        val transform = VideoAnnotationTransform(translationY = 0.4f, rotationDegrees = 20f)
        val layer = layer(
            id = "fixed",
            startMillis = 1_000,
            endMillis = 9_000,
            trackingMode = VideoAnnotationTrackingMode.Fixed,
            keyframes = listOf(VideoAnnotationKeyframe(1_000, transform)),
        )

        val trimmedLayer = VideoEditRecipe(annotations = listOf(layer))
            .withTrimRange(startMillis = 4_000, endMillis = 8_000)
            .annotations
            .single()

        assertEquals(listOf(4_000L), trimmedLayer.keyframes.map(VideoAnnotationKeyframe::timeMillis))
        assertEquals(transform, trimmedLayer.transformAt(7_000))
        assertTrue(trimmedLayer.isVisibleAt(7_000))
    }

    private fun layer(
        id: String,
        startMillis: Long,
        endMillis: Long,
        trackingMode: VideoAnnotationTrackingMode = VideoAnnotationTrackingMode.Fixed,
        keyframes: List<VideoAnnotationKeyframe> = emptyList(),
    ) = VideoAnnotationLayer(
        id = id,
        shape = VideoAnnotationShape.Line,
        points = listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.9f, 0.9f)),
        startMillis = startMillis,
        endMillis = endMillis,
        trackingMode = trackingMode,
        keyframes = keyframes,
    )
}
