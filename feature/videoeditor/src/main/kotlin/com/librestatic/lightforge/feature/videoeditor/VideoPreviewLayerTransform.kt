package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.VideoGeometry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A Compose `graphicsLayer` transform: scale is applied first, then [rotationZ] (clockwise). */
internal data class PreviewLayerTransform(
    val rotationZ: Float,
    val scaleX: Float,
    val scaleY: Float,
)

/**
 * Approximates the export's rotate/flip/straighten for a preview that plays without Media3 effects
 * (after the effects graph failed on the device), so those edits still visibly respond.
 *
 * The export applies `ScaleAndRotateTransformation`: rotate [VideoGeometry.rotationDegrees]
 * counter-clockwise, then mirror. A layer mirrors first, and mirroring reverses the rotation's
 * direction, so the layer rotates clockwise only when flipped. The rotated frame is scaled to fit
 * the container. Crop is not reproduced here; it is still applied on export.
 */
internal fun fallbackPreviewTransform(
    geometry: VideoGeometry,
    frameWidth: Float,
    frameHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
): PreviewLayerTransform {
    if (frameWidth <= 0f || frameHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
        return PreviewLayerTransform(0f, 1f, 1f)
    }
    val radians = Math.toRadians(geometry.rotationDegrees.toDouble())
    val cosine = abs(cos(radians)).toFloat()
    val sine = abs(sin(radians)).toFloat()
    val rotatedWidth = frameWidth * cosine + frameHeight * sine
    val rotatedHeight = frameWidth * sine + frameHeight * cosine
    val fit = min(containerWidth / rotatedWidth, containerHeight / rotatedHeight)
    // "+ 0f" turns -0f into 0f so an unrotated frame reads as no rotation.
    val rotation = (if (geometry.flipHorizontal) geometry.rotationDegrees else -geometry.rotationDegrees) + 0f
    return PreviewLayerTransform(
        rotationZ = rotation,
        scaleX = if (geometry.flipHorizontal) -fit else fit,
        scaleY = fit,
    )
}
