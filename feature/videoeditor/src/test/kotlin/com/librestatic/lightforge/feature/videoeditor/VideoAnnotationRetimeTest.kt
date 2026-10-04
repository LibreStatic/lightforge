package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.NormalizedPoint
import com.librestatic.lightforge.core.editing.video.VideoAnnotationKeyframe
import com.librestatic.lightforge.core.editing.video.VideoAnnotationLayer
import com.librestatic.lightforge.core.editing.video.VideoAnnotationShape
import com.librestatic.lightforge.core.editing.video.VideoAnnotationTrackingMode
import com.librestatic.lightforge.core.editing.video.VideoAnnotationTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAnnotationRetimeTest {
    private fun layer(mode: VideoAnnotationTrackingMode, vararg keyframes: VideoAnnotationKeyframe) =
        VideoAnnotationLayer(
            shape = VideoAnnotationShape.Rectangle,
            points = listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.3f, 0.3f)),
            startMillis = 0,
            endMillis = 10_000,
            trackingMode = mode,
            keyframes = keyframes.toList(),
        )

    private fun at(x: Float) = VideoAnnotationTransform(translationX = x)

    @Test
    fun fixedLayerKeepsItsPositionWhenStartMoves() {
        val fixed = layer(VideoAnnotationTrackingMode.Fixed, VideoAnnotationKeyframe(0, at(0.4f)))
        val retimed = fixed.retimed(5_000, 9_000)
        assertEquals(0.4f, retimed.transformAt(6_000).translationX, 0f)
        assertEquals(5_000L, retimed.keyframes.single().timeMillis)
    }

    @Test
    fun keyframesCutByNewBoundsAreReplacedByInterpolatedEdges() {
        val moving = layer(
            VideoAnnotationTrackingMode.Keyframes,
            VideoAnnotationKeyframe(0, at(0f)),
            VideoAnnotationKeyframe(10_000, at(1f)),
        )
        val retimed = moving.retimed(2_000, 8_000)
        assertEquals(listOf(2_000L, 8_000L), retimed.keyframes.map { it.timeMillis })
        assertEquals(0.2f, retimed.keyframes.first().transform.translationX, 0.001f)
        assertEquals(0.8f, retimed.keyframes.last().transform.translationX, 0.001f)
    }

    @Test
    fun keyframesBeforeNewStartHoldTheirLastValue() {
        val moving = layer(
            VideoAnnotationTrackingMode.Keyframes,
            VideoAnnotationKeyframe(0, at(0f)),
            VideoAnnotationKeyframe(1_000, at(0.5f)),
        )
        val retimed = moving.retimed(4_000, 9_000)
        assertEquals(0.5f, retimed.keyframes.single().transform.translationX, 0f)
    }
}
