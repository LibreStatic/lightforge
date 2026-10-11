package com.librestatic.lightforge.feature.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.librestatic.lightforge.core.model.NormalizedPoint
import kotlin.math.max
import kotlin.math.min

private val LoupeSize = 104.dp

private fun cornerOffset(corner: NormalizedPoint, image: Rect) =
    Offset(image.left + corner.x * image.width, image.top + corner.y * image.height)

/**
 * Four draggable corners over the unwarped photo, with a magnifier while one is held. The dragged
 * quad is kept convex ([movePerspectiveCorner]); the warp itself is rendered by the host.
 */
@Composable
internal fun PhotoPerspectiveOverlay(
    bitmap: Bitmap,
    corners: List<NormalizedPoint>,
    onChange: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCorners by rememberUpdatedState(corners)
    val currentOnChange by rememberUpdatedState(onChange)
    var activeCorner by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val grabRadius = with(density) { 56.dp.toPx() }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val description = stringResource(R.string.photo_editor_perspective_corners)
    val colors = MaterialTheme.colorScheme
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap.width, bitmap.height) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val bounds = fittedImageBounds(size, IntSize(bitmap.width, bitmap.height))
                        if (bounds.isEmpty) return@awaitEachGesture
                        val index = currentCorners.indices
                            .minBy { (cornerOffset(currentCorners[it], bounds) - down.position).getDistance() }
                        val origin = cornerOffset(currentCorners[index], bounds)
                        if ((origin - down.position).getDistance() > grabRadius) return@awaitEachGesture
                        activeCorner = index
                        drag(down.id) { change ->
                            change.consume()
                            // Keep the grab offset so the corner does not jump under the finger.
                            val target = origin + (change.position - down.position)
                            val moved = movePerspectiveCorner(
                                currentCorners,
                                index,
                                NormalizedPoint(
                                    ((target.x - bounds.left) / bounds.width).coerceIn(0f, 1f),
                                    ((target.y - bounds.top) / bounds.height).coerceIn(0f, 1f),
                                ),
                            )
                            currentOnChange(moved)
                        }
                        activeCorner = -1
                    }
                },
        ) {
            val bounds = fittedImageBounds(IntSize(size.width.toInt(), size.height.toInt()), IntSize(bitmap.width, bitmap.height))
            if (bounds.isEmpty) return@Canvas
            val points = corners.map { cornerOffset(it, bounds) }
            val quad = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1..3) lineTo(points[i].x, points[i].y)
                close()
            }
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(bounds)
                addPath(quad)
            }
            drawPath(outside, colors.scrim.copy(alpha = 0.55f))
            drawPath(quad, colors.onPrimary, style = Stroke(width = 7.dp.toPx()))
            drawPath(quad, colors.primary, style = Stroke(width = 3.dp.toPx()))
            points.forEachIndexed { index, point ->
                val radius = (if (index == activeCorner) 14.dp else 11.dp).toPx()
                drawCircle(colors.onPrimary, radius + 2.dp.toPx(), point)
                drawCircle(colors.primary, radius, point)
            }
        }
        if (activeCorner >= 0) {
            // The magnifier sits in the top corner away from the finger.
            val onRight = corners[activeCorner].x < 0.5f
            Loupe(
                image = image,
                bitmapSize = IntSize(bitmap.width, bitmap.height),
                center = corners[activeCorner],
                modifier = Modifier.align(if (onRight) Alignment.TopEnd else Alignment.TopStart).padding(8.dp),
            )
        }
    }
}

@Composable
private fun Loupe(
    image: androidx.compose.ui.graphics.ImageBitmap,
    bitmapSize: IntSize,
    center: NormalizedPoint,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.size(LoupeSize)) {
        val side = size.width
        val circle = Path().apply { addOval(Rect(0f, 0f, side, side)) }
        drawCircle(colors.surface, side / 2f, Offset(side / 2f, side / 2f))
        // About 14 % of the shorter side around the corner, magnified to the loupe.
        val region = max(24, (min(bitmapSize.width, bitmapSize.height) * 0.14f).toInt())
        val srcWidth = min(region, bitmapSize.width)
        val srcHeight = min(region, bitmapSize.height)
        val srcLeft = (center.x * bitmapSize.width - srcWidth / 2f).toInt().coerceIn(0, bitmapSize.width - srcWidth)
        val srcTop = (center.y * bitmapSize.height - srcHeight / 2f).toInt().coerceIn(0, bitmapSize.height - srcHeight)
        clipPath(circle) {
            drawImage(
                image,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcWidth, srcHeight),
                dstSize = IntSize(side.toInt(), side.toInt()),
            )
        }
        // Crosshair on the corner itself, which sits where it falls inside the (clamped) region.
        val cx = (center.x * bitmapSize.width - srcLeft) / srcWidth * side
        val cy = (center.y * bitmapSize.height - srcTop) / srcHeight * side
        val arm = 8.dp.toPx()
        drawLine(colors.onPrimary, Offset(cx - arm, cy), Offset(cx + arm, cy), 4.dp.toPx())
        drawLine(colors.onPrimary, Offset(cx, cy - arm), Offset(cx, cy + arm), 4.dp.toPx())
        drawLine(colors.primary, Offset(cx - arm, cy), Offset(cx + arm, cy), 2.dp.toPx())
        drawLine(colors.primary, Offset(cx, cy - arm), Offset(cx, cy + arm), 2.dp.toPx())
        drawCircle(colors.primary, side / 2f - 1.5.dp.toPx(), Offset(side / 2f, side / 2f), style = Stroke(3.dp.toPx()))
    }
}

/** The perspective quad drawn over the unwarped photo while another crop tool is active. */
@Composable
internal fun PerspectiveQuadOutline(
    bitmapSize: IntSize,
    corners: List<NormalizedPoint>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val bounds = fittedImageBounds(IntSize(size.width.toInt(), size.height.toInt()), bitmapSize)
        if (bounds.isEmpty) return@Canvas
        val points = corners.map { cornerOffset(it, bounds) }
        val quad = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1..3) lineTo(points[i].x, points[i].y)
            close()
        }
        drawPath(quad, colors.onPrimary, style = Stroke(width = 5.dp.toPx()))
        drawPath(quad, colors.primary, style = Stroke(width = 2.dp.toPx()))
    }
}
