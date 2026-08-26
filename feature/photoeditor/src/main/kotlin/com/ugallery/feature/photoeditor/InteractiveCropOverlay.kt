package com.ugallery.feature.photoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class PhotoCropDraft(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val aspectRatio: Float? = null,
    val straightenDegrees: Float = 0f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal enum class CropHandle { Move, Left, Top, Right, Bottom, TopLeft, TopRight, BottomLeft, BottomRight }

@Composable
internal fun InteractiveCropOverlay(
    bitmapSize: IntSize,
    draft: PhotoCropDraft,
    onChange: (PhotoCropDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDraft by rememberUpdatedState(draft)
    BoxWithConstraints(modifier) {
        val containerWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val imageAspect = bitmapSize.width.toFloat() / bitmapSize.height.coerceAtLeast(1)
        val containerAspect = containerWidth / containerHeight
        val imageWidth: Float
        val imageHeight: Float
        if (imageAspect >= containerAspect) {
            imageWidth = containerWidth
            imageHeight = imageWidth / imageAspect
        } else {
            imageHeight = containerHeight
            imageWidth = imageHeight * imageAspect
        }
        val imageLeft = (containerWidth - imageWidth) / 2f
        val imageTop = (containerHeight - imageHeight) / 2f
        val imageRect = Rect(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(imageRect) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val handle = hitTest(down.position, imageRect, currentDraft, 40f)
                            ?: return@awaitEachGesture
                        val start = currentDraft
                        drag(down.id) { change ->
                            change.consume()
                            val delta = change.position - down.position
                            onChange(resizeCrop(
                                start,
                                handle,
                                delta.x / imageRect.width,
                                delta.y / imageRect.height,
                                imageRect.width / imageRect.height,
                            ))
                        }
                    }
                },
        ) {
            val crop = Rect(
                imageRect.left + draft.left * imageRect.width,
                imageRect.top + draft.top * imageRect.height,
                imageRect.left + draft.right * imageRect.width,
                imageRect.top + draft.bottom * imageRect.height,
            )
            val shade = Color.Black.copy(alpha = 0.62f)
            drawRect(shade, topLeft = imageRect.topLeft, size = androidx.compose.ui.geometry.Size(imageRect.width, crop.top - imageRect.top))
            drawRect(shade, topLeft = Offset(imageRect.left, crop.bottom), size = androidx.compose.ui.geometry.Size(imageRect.width, imageRect.bottom - crop.bottom))
            drawRect(shade, topLeft = Offset(imageRect.left, crop.top), size = androidx.compose.ui.geometry.Size(crop.left - imageRect.left, crop.height))
            drawRect(shade, topLeft = Offset(crop.right, crop.top), size = androidx.compose.ui.geometry.Size(imageRect.right - crop.right, crop.height))
            drawRect(Color.White, crop.topLeft, crop.size, style = Stroke(width = 3f))
            for (index in 1..2) {
                val x = crop.left + crop.width * index / 3f
                val y = crop.top + crop.height * index / 3f
                drawLine(Color.White.copy(alpha = 0.65f), Offset(x, crop.top), Offset(x, crop.bottom), 1f)
                drawLine(Color.White.copy(alpha = 0.65f), Offset(crop.left, y), Offset(crop.right, y), 1f)
            }
            val corner = 28f
            val path = Path().apply {
                moveTo(crop.left, crop.top + corner); lineTo(crop.left, crop.top); lineTo(crop.left + corner, crop.top)
                moveTo(crop.right - corner, crop.top); lineTo(crop.right, crop.top); lineTo(crop.right, crop.top + corner)
                moveTo(crop.right, crop.bottom - corner); lineTo(crop.right, crop.bottom); lineTo(crop.right - corner, crop.bottom)
                moveTo(crop.left + corner, crop.bottom); lineTo(crop.left, crop.bottom); lineTo(crop.left, crop.bottom - corner)
            }
            drawPath(path, Color.White, style = Stroke(width = 8f))
        }
    }
}

private fun hitTest(position: Offset, image: Rect, draft: PhotoCropDraft, radius: Float): CropHandle? {
    val crop = Rect(
        image.left + draft.left * image.width,
        image.top + draft.top * image.height,
        image.left + draft.right * image.width,
        image.top + draft.bottom * image.height,
    )
    val nearLeft = abs(position.x - crop.left) <= radius
    val nearRight = abs(position.x - crop.right) <= radius
    val nearTop = abs(position.y - crop.top) <= radius
    val nearBottom = abs(position.y - crop.bottom) <= radius
    return when {
        nearLeft && nearTop -> CropHandle.TopLeft
        nearRight && nearTop -> CropHandle.TopRight
        nearLeft && nearBottom -> CropHandle.BottomLeft
        nearRight && nearBottom -> CropHandle.BottomRight
        nearLeft && position.y in crop.top..crop.bottom -> CropHandle.Left
        nearRight && position.y in crop.top..crop.bottom -> CropHandle.Right
        nearTop && position.x in crop.left..crop.right -> CropHandle.Top
        nearBottom && position.x in crop.left..crop.right -> CropHandle.Bottom
        position in crop -> CropHandle.Move
        else -> null
    }
}

internal fun resizeCrop(
    start: PhotoCropDraft,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    imageAspectRatio: Float,
): PhotoCropDraft {
    val minimum = 0.08f
    if (handle == CropHandle.Move) {
        val safeDx = dx.coerceIn(-start.left, 1f - start.right)
        val safeDy = dy.coerceIn(-start.top, 1f - start.bottom)
        return start.copy(
            left = start.left + safeDx,
            right = start.right + safeDx,
            top = start.top + safeDy,
            bottom = start.bottom + safeDy,
        )
    }
    var left = if (handle in setOf(CropHandle.Left, CropHandle.TopLeft, CropHandle.BottomLeft)) start.left + dx else start.left
    var right = if (handle in setOf(CropHandle.Right, CropHandle.TopRight, CropHandle.BottomRight)) start.right + dx else start.right
    var top = if (handle in setOf(CropHandle.Top, CropHandle.TopLeft, CropHandle.TopRight)) start.top + dy else start.top
    var bottom = if (handle in setOf(CropHandle.Bottom, CropHandle.BottomLeft, CropHandle.BottomRight)) start.bottom + dy else start.bottom
    left = left.coerceIn(0f, right - minimum)
    right = right.coerceIn(left + minimum, 1f)
    top = top.coerceIn(0f, bottom - minimum)
    bottom = bottom.coerceIn(top + minimum, 1f)
    start.aspectRatio?.let { ratio ->
        val normalizedRatio = ratio / imageAspectRatio
        val targetHeight = (right - left) / normalizedRatio
        val targetWidth = (bottom - top) * normalizedRatio
        if (abs(targetHeight - (bottom - top)) <= abs(targetWidth - (right - left))) {
            if (handle in setOf(CropHandle.Top, CropHandle.TopLeft, CropHandle.TopRight)) top = (bottom - targetHeight).coerceAtLeast(0f)
            else bottom = (top + targetHeight).coerceAtMost(1f)
        } else {
            if (handle in setOf(CropHandle.Left, CropHandle.TopLeft, CropHandle.BottomLeft)) left = (right - targetWidth).coerceAtLeast(0f)
            else right = (left + targetWidth).coerceAtMost(1f)
        }
    }
    return start.copy(left = min(left, right - minimum), top = min(top, bottom - minimum), right = max(right, left + minimum), bottom = max(bottom, top + minimum))
}
