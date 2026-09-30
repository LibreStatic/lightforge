package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.VideoGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCropDragTest {
    private val crop = VideoGeometry(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)

    @Test
    fun cornersWinOverEdgesAndTheInteriorMoves() {
        assertEquals(CropHandle.TopLeft, VideoCropDrag.hitTest(crop, 0.21f, 0.19f, 0.05f))
        assertEquals(CropHandle.BottomRight, VideoCropDrag.hitTest(crop, 0.79f, 0.81f, 0.05f))
        assertEquals(CropHandle.Top, VideoCropDrag.hitTest(crop, 0.5f, 0.21f, 0.05f))
        assertEquals(CropHandle.Left, VideoCropDrag.hitTest(crop, 0.19f, 0.5f, 0.05f))
        assertEquals(CropHandle.Move, VideoCropDrag.hitTest(crop, 0.5f, 0.5f, 0.05f))
        assertNull(VideoCropDrag.hitTest(crop, 0.05f, 0.5f, 0.05f))
    }

    @Test
    fun draggingACornerMovesOnlyItsTwoEdges() {
        val result = VideoCropDrag.drag(crop, CropHandle.TopLeft, dx = 0.1f, dy = -0.1f)
        assertEquals(0.3f, result.left, 0.0001f)
        assertEquals(0.1f, result.top, 0.0001f)
        assertEquals(0.8f, result.right, 0.0001f)
        assertEquals(0.8f, result.bottom, 0.0001f)
    }

    @Test
    fun edgesNeverCrossOrLeaveTheFrame() {
        val tooFar = VideoCropDrag.drag(crop, CropHandle.Left, dx = 5f, dy = 0f)
        assertEquals(0.8f - MinCropSpan, tooFar.left, 0.0001f)
        val outside = VideoCropDrag.drag(crop, CropHandle.Right, dx = 5f, dy = 0f)
        assertEquals(1f, outside.right, 0.0001f)
        val negative = VideoCropDrag.drag(crop, CropHandle.Top, dx = 0f, dy = -5f)
        assertEquals(0f, negative.top, 0.0001f)
    }

    @Test
    fun movingKeepsTheSizeAndStopsAtTheFrameEdge() {
        val moved = VideoCropDrag.drag(crop, CropHandle.Move, dx = 0.5f, dy = -0.5f)
        assertEquals(0.6f, moved.right - moved.left, 0.0001f)
        assertEquals(1f, moved.right, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)
        assertEquals(0.6f, moved.bottom - moved.top, 0.0001f)
    }

    @Test
    fun rotationAndFlipAreLeftUntouched() {
        val rotated = crop.copy(rotationDegrees = 90f, flipHorizontal = true)
        val result = VideoCropDrag.drag(rotated, CropHandle.Bottom, 0f, -0.1f)
        assertEquals(90f, result.rotationDegrees)
        assertEquals(true, result.flipHorizontal)
    }
}
