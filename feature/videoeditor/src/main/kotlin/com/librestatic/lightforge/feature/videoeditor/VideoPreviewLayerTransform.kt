package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.VideoGeometry
import com.librestatic.lightforge.core.editing.video.VideoStraighten
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A Compose `graphicsLayer` transform: scale is applied first, then [rotationZ] (clockwise). The
 * transformed frame is shown through a [clipWidth]×[clipHeight] window centred on it, which hides
 * what the straighten zoom pushes past the frame's edges.
 */
internal data class PreviewLayerTransform(
    val rotationZ: Float,
    val scaleX: Float,
    val scaleY: Float,
    val clipWidth: Float = 0f,
    val clipHeight: Float = 0f,
)

/**
 * Approximates the export's rotate/flip/straighten for a preview that plays without Media3 effects
 * (after the effects graph failed on the device), so those edits still visibly respond.
 *
 * The export rotates the quarter turns of [VideoGeometry.rotationDegrees] counter-clockwise, then
 * mirrors, then tilts by the remaining straighten angle zoomed to cover the frame
 * ([VideoStraighten]). A layer mirrors first, and mirroring reverses the rotation's direction, so
 * the layer rotates clockwise only when flipped. The quarter-turned frame is scaled to fit the
 * container. Crop is not reproduced here; it is still applied on export.
 */
internal fun fallbackPreviewTransform(
    geometry: VideoGeometry,
    frameWidth: Float,
    frameHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
): PreviewLayerTransform {
    if (frameWidth <= 0f || frameHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
        return PreviewLayerTransform(0f, 1f, 1f, frameWidth, frameHeight)
    }
    val sideways = (VideoStraighten.quarterTurnDegrees(geometry.rotationDegrees) / 90f).roundToInt() % 2 != 0
    val turnedWidth = if (sideways) frameHeight else frameWidth
    val turnedHeight = if (sideways) frameWidth else frameHeight
    val fit = min(containerWidth / turnedWidth, containerHeight / turnedHeight)
    val zoom = VideoStraighten.coverScale(VideoStraighten.fineDegrees(geometry.rotationDegrees), frameWidth, frameHeight)
    // "+ 0f" turns -0f into 0f so an unrotated frame reads as no rotation.
    val rotation = (if (geometry.flipHorizontal) geometry.rotationDegrees else -geometry.rotationDegrees) + 0f
    return PreviewLayerTransform(
        rotationZ = rotation,
        scaleX = if (geometry.flipHorizontal) -fit * zoom else fit * zoom,
        scaleY = fit * zoom,
        clipWidth = turnedWidth * fit,
        clipHeight = turnedHeight * fit,
    )
}
