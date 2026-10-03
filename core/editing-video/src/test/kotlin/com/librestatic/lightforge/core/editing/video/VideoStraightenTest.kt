package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VideoStraightenTest {
    @Test
    fun rotationSplitsIntoQuarterTurnsAndAStraightenAngle() {
        assertEquals(90f, VideoStraighten.quarterTurnDegrees(97.5f), 0f)
        assertEquals(7.5f, VideoStraighten.fineDegrees(97.5f), 0.0001f)
        assertEquals(-90f, VideoStraighten.quarterTurnDegrees(-80f), 0f)
        assertEquals(10f, VideoStraighten.fineDegrees(-80f), 0.0001f)
        assertEquals(0f, VideoStraighten.fineDegrees(180f), 0f)
    }

    @Test
    fun coverScaleHidesTheCornersOfATiltedFrame() {
        val radians = Math.toRadians(10.0)
        val expected = cos(radians) + 16.0 / 9.0 * sin(radians)

        assertEquals(expected.toFloat(), VideoStraighten.coverScale(10f, 1920f, 1080f), 0.0001f)
        assertEquals(expected.toFloat(), VideoStraighten.coverScale(-10f, 1080f, 1920f), 0.0001f)
        assertEquals(1f, VideoStraighten.coverScale(0f, 1920f, 1080f), 0f)
    }

    @Test
    fun straightenKeepsTheEditedSize() {
        val source = VideoSourceInfo(width = 1920, height = 1080, frameRate = 30f, durationMs = 1_000, hasAudio = false)
        val (width, height) = VideoOutputPlan.editedSize(source, VideoGeometry(rotationDegrees = 7f))

        assertEquals(1920.0, width, 0.001)
        assertEquals(1080.0, height, 0.001)
    }
}
