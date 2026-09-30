package com.librestatic.lightforge.core.designsystem

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the contextual controls floating over the bottom of the current surface (for
 * example the selection toolbar). Scrolling content adds it as bottom content padding so its
 * last row can always be scrolled clear of the overlay.
 */
val LocalGalleryBottomOverlayPadding = compositionLocalOf { 0.dp }

/** Bottom content padding that keeps [minimum] and still clears any floating bottom controls. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun galleryBottomContentPadding(minimum: Dp = 0.dp): Dp =
    maxOf(minimum, LocalGalleryBottomOverlayPadding.current)
