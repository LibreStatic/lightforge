package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAnnotationLayerTest {
    private fun layer() = VideoAnnotationLayer(
        shape = VideoAnnotationShape.Rectangle,
        points = listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.5f, 0.5f)),
        startMillis = 0,
        endMillis = 10_000,
        trackingMode = VideoAnnotationTrackingMode.Keyframes,
        keyframes = listOf(
            VideoAnnotationKeyframe(1_000, VideoAnnotationTransform(translationX = 0.1f)),
            VideoAnnotationKeyframe(3_000, VideoAnnotationTransform(translationX = 0.3f)),
        ),
    )

    @Test
    fun holdsTheFirstKeyframeBeforeIt() {
        assertEquals(0.1f, layer().transformAt(0).translationX, 0.0001f)
    }

    @Test
    fun interpolatesBetweenKeyframes() {
        assertEquals(0.2f, layer().transformAt(2_000).translationX, 0.0001f)
    }

    @Test
    fun holdsTheLastKeyframeAfterIt() {
        assertEquals(0.3f, layer().transformAt(8_000).translationX, 0.0001f)
    }
}
