package com.librestatic.lightforge.feature.viewer.shared

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.min

internal const val SHARED_ZOOM_MAX_SCALE = 6f

/** Size of content with the given proportions fitted (FIT_CENTER) inside [container]. */
internal fun fittedSize(container: Size, contentWidth: Float, contentHeight: Float): Size {
    if (container.width <= 0f || container.height <= 0f || contentWidth <= 0f || contentHeight <= 0f) {
        return container
    }
    val fit = min(container.width / contentWidth, container.height / contentHeight)
    return Size(contentWidth * fit, contentHeight * fit)
}

/**
 * The pan of this zoom in pixels for a pane of size [container] showing content fitted to
 * [fitted], held so the zoomed content never leaves a gap it did not already have.
 */
internal fun SharedZoom.translation(container: Size, fitted: Size): Offset {
    val maxX = ((fitted.width * scale - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fitted.height * scale - container.height) / 2f).coerceAtLeast(0f)
    return Offset(
        (panX * fitted.width).coerceIn(-maxX, maxX),
        (panY * fitted.height).coerceIn(-maxY, maxY),
    )
}

/**
 * This zoom after a pinch/pan step. [centroid] is the gesture centre relative to the pane centre,
 * and the point under it stays put while scaling. Returns the identity once back at fit.
 */
internal fun SharedZoom.afterGesture(
    centroid: Offset,
    pan: Offset,
    factor: Float,
    container: Size,
    fitted: Size,
    maxScale: Float = SHARED_ZOOM_MAX_SCALE,
): SharedZoom {
    if (fitted.width <= 0f || fitted.height <= 0f) return this
    val newScale = (scale * factor).coerceIn(1f, maxScale)
    if (newScale < 1.01f) return SharedZoom()
    val applied = newScale / scale
    val current = translation(container, fitted)
    val maxX = ((fitted.width * newScale - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fitted.height * newScale - container.height) / 2f).coerceAtLeast(0f)
    val moved = Offset(
        (current.x * applied + (1f - applied) * centroid.x + pan.x).coerceIn(-maxX, maxX),
        (current.y * applied + (1f - applied) * centroid.y + pan.y).coerceIn(-maxY, maxY),
    )
    return SharedZoom(newScale, moved.x / fitted.width, moved.y / fitted.height)
}
