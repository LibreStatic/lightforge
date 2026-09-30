package com.librestatic.lightforge.feature.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.editing.video.VideoGeometry

/** Which part of the crop rectangle a touch grabbed. */
internal enum class CropHandle { TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left, Move }

/** Smallest crop side, as a fraction of the source, so the rectangle can never collapse. */
internal const val MinCropSpan = 0.05f

/**
 * Pure crop-drag math in normalized source coordinates (0..1), separated from the gesture code so
 * it can be unit tested. All results are clamped to the frame and to [MinCropSpan].
 */
internal object VideoCropDrag {
    /** Picks the handle near ([x], [y]), preferring corners, then edges, then the interior. */
    fun hitTest(geometry: VideoGeometry, x: Float, y: Float, slop: Float): CropHandle? {
        val nearLeft = kotlin.math.abs(x - geometry.left) <= slop
        val nearRight = kotlin.math.abs(x - geometry.right) <= slop
        val nearTop = kotlin.math.abs(y - geometry.top) <= slop
        val nearBottom = kotlin.math.abs(y - geometry.bottom) <= slop
        val withinX = x in (geometry.left - slop)..(geometry.right + slop)
        val withinY = y in (geometry.top - slop)..(geometry.bottom + slop)
        return when {
            nearLeft && nearTop -> CropHandle.TopLeft
            nearRight && nearTop -> CropHandle.TopRight
            nearLeft && nearBottom -> CropHandle.BottomLeft
            nearRight && nearBottom -> CropHandle.BottomRight
            nearTop && withinX -> CropHandle.Top
            nearBottom && withinX -> CropHandle.Bottom
            nearLeft && withinY -> CropHandle.Left
            nearRight && withinY -> CropHandle.Right
            x in geometry.left..geometry.right && y in geometry.top..geometry.bottom -> CropHandle.Move
            else -> null
        }
    }

    fun drag(start: VideoGeometry, handle: CropHandle, dx: Float, dy: Float): VideoGeometry {
        if (handle == CropHandle.Move) {
            val width = start.right - start.left
            val height = start.bottom - start.top
            val left = (start.left + dx).coerceIn(0f, 1f - width)
            val top = (start.top + dy).coerceIn(0f, 1f - height)
            return start.copy(left = left, right = left + width, top = top, bottom = top + height)
        }
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        if (handle in LeftHandles) left = (start.left + dx).coerceIn(0f, right - MinCropSpan)
        if (handle in RightHandles) right = (start.right + dx).coerceIn(left + MinCropSpan, 1f)
        if (handle in TopHandles) top = (start.top + dy).coerceIn(0f, bottom - MinCropSpan)
        if (handle in BottomHandles) bottom = (start.bottom + dy).coerceIn(top + MinCropSpan, 1f)
        return start.copy(left = left, top = top, right = right, bottom = bottom)
    }

    private val LeftHandles = setOf(CropHandle.TopLeft, CropHandle.Left, CropHandle.BottomLeft)
    private val RightHandles = setOf(CropHandle.TopRight, CropHandle.Right, CropHandle.BottomRight)
    private val TopHandles = setOf(CropHandle.TopLeft, CropHandle.Top, CropHandle.TopRight)
    private val BottomHandles = setOf(CropHandle.BottomLeft, CropHandle.Bottom, CropHandle.BottomRight)
}

/**
 * Draws the crop rectangle over the (unrotated, uncropped) preview and lets the user drag its
 * corners, edges or body. Sits exactly over the video surface, so its size is the source frame.
 */
@Composable
internal fun VideoCropOverlay(
    geometry: VideoGeometry,
    onGeometryChange: (VideoGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(geometry)
    val onChange by rememberUpdatedState(onGeometryChange)
    val handleColor = Color.White
    val scrim = Color.Black.copy(alpha = 0.55f)
    Canvas(
        modifier
            .fillMaxSize()
            // The frame's left and right edges touch the screen edges; keep them out of the back gesture.
            .systemGestureExclusion()
            .testTag("video-editor-crop-overlay")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    val slop = 28.dp.toPx() / minOf(width, height)
                    val handle = VideoCropDrag.hitTest(
                        latest, down.position.x / width, down.position.y / height, slop,
                    ) ?: return@awaitEachGesture
                    val start = latest
                    down.consume()
                    drag(down.id) { change ->
                        val dx = (change.position.x - down.position.x) / width
                        val dy = (change.position.y - down.position.y) / height
                        onChange(VideoCropDrag.drag(start, handle, dx, dy))
                        change.consume()
                    }
                }
            },
    ) {
        val left = geometry.left * size.width
        val top = geometry.top * size.height
        val right = geometry.right * size.width
        val bottom = geometry.bottom * size.height
        // Dim everything outside the crop.
        drawRect(scrim, Offset.Zero, Size(size.width, top))
        drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(scrim, Offset(0f, top), Size(left, bottom - top))
        drawRect(scrim, Offset(right, top), Size(size.width - right, bottom - top))
        // Rule-of-thirds grid and frame.
        val grid = handleColor.copy(alpha = 0.4f)
        for (i in 1..2) {
            val x = left + (right - left) * i / 3f
            val y = top + (bottom - top) * i / 3f
            drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        drawRect(handleColor, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
        // Corner brackets, thick enough to find with a thumb.
        val arm = 22.dp.toPx()
        val thick = 4.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(handleColor, Offset(x, y), Offset(x + dx * arm, y), thick)
            drawLine(handleColor, Offset(x, y), Offset(x, y + dy * arm), thick)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
        // Edge midpoints.
        val mid = CornerRadius(3.dp.toPx())
        val edge = Size(18.dp.toPx(), 5.dp.toPx())
        val tall = Size(5.dp.toPx(), 18.dp.toPx())
        drawRoundRect(handleColor, Offset((left + right) / 2 - edge.width / 2, top - edge.height / 2), edge, mid)
        drawRoundRect(handleColor, Offset((left + right) / 2 - edge.width / 2, bottom - edge.height / 2), edge, mid)
        drawRoundRect(handleColor, Offset(left - tall.width / 2, (top + bottom) / 2 - tall.height / 2), tall, mid)
        drawRoundRect(handleColor, Offset(right - tall.width / 2, (top + bottom) / 2 - tall.height / 2), tall, mid)
    }
}
