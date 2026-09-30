package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Shared contrast plates for content rendered over arbitrary media.
 *
 * Media pixels are not a reliable background, so viewer chrome should use
 * these tokens instead of inventing a new translucent black/white value.
 */
object GalleryOverlayTokens {
    val Content: Color = Color.White
    val ControlSurface: Color = Color.Black.copy(alpha = 0.72f)
    val StrongSurface: Color = Color.Black.copy(alpha = 0.80f)
    val DurationSurface: Color = Color.Black.copy(alpha = 0.78f)
    val FeedbackSurface: Color = Color.Black.copy(alpha = 0.70f)
    val SoftVeil: Color = Color.Black.copy(alpha = 0.35f)
    val TimelineSurface: Color = Color.Black.copy(alpha = 0.65f)
    val Border: Color = Color.White.copy(alpha = 0.40f)
    val ScrimBase: Color = Color.Black.copy(alpha = 0.30f)
    val ScrimTop: Color = Color.Black.copy(alpha = 0.65f)
    val ScrimMiddle: Color = Color.Black.copy(alpha = 0.24f)
    val ScrimBottom: Color = Color.Black.copy(alpha = 0.72f)
    val FilmstripHalo: Color = Color.Black.copy(alpha = 0.72f)
}
