package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.VideoGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreviewLayerTransformTest {
    @Test
    fun identityGeometryKeepsTheFittedFrame() {
        assertEquals(
            PreviewLayerTransform(0f, 1f, 1f, 400f, 300f),
            fallbackPreviewTransform(VideoGeometry(), 400f, 300f, 400f, 300f),
        )
    }

    @Test
    fun quarterTurnRotatesCounterClockwiseAndShrinksToFitTheContainer() {
        val transform = fallbackPreviewTransform(
            VideoGeometry(rotationDegrees = 90f), 400f, 300f, 400f, 300f,
        )

        assertEquals(-90f, transform.rotationZ, 0.001f)
        // The rotated 300x400 frame must fit a 400x300 container: 300 / 400.
        assertEquals(0.75f, transform.scaleX, 0.001f)
        assertEquals(0.75f, transform.scaleY, 0.001f)
    }

    @Test
    fun flippingMirrorsFirstSoTheLayerRotatesTheOtherWay() {
        val transform = fallbackPreviewTransform(
            VideoGeometry(rotationDegrees = 10f, flipHorizontal = true), 400f, 300f, 400f, 300f,
        )

        assertEquals(10f, transform.rotationZ, 0.001f)
        assertEquals(-transform.scaleY, transform.scaleX, 0.0001f)
    }

    @Test
    fun straightenedFramesZoomToCoverTheirUnrotatedBox() {
        val transform = fallbackPreviewTransform(
            VideoGeometry(rotationDegrees = 10f), 400f, 300f, 400f, 300f,
        )
        val radians = Math.toRadians(10.0)
        val cover = kotlin.math.cos(radians) + 400.0 / 300.0 * kotlin.math.sin(radians)

        assertEquals(-10f, transform.rotationZ, 0.001f)
        assertEquals(cover.toFloat(), transform.scaleY, 0.001f)
        assertEquals(400f, transform.clipWidth, 0.001f)
        assertEquals(300f, transform.clipHeight, 0.001f)
    }

    @Test
    fun straighteningAfterAQuarterTurnFitsTheTurnedFrame() {
        val transform = fallbackPreviewTransform(
            VideoGeometry(rotationDegrees = 95f), 400f, 300f, 400f, 300f,
        )

        assertEquals(225f, transform.clipWidth, 0.001f)
        assertEquals(300f, transform.clipHeight, 0.001f)
    }

    @Test
    fun unmeasuredLayoutsAreLeftUntransformed() {
        assertEquals(
            PreviewLayerTransform(0f, 1f, 1f, 0f, 0f),
            fallbackPreviewTransform(VideoGeometry(rotationDegrees = 90f), 0f, 0f, 400f, 300f),
        )
    }
}
