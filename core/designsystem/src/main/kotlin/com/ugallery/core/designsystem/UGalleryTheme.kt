package com.ugallery.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object GallerySpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Section = 32.dp
    val Hero = 48.dp
}

object GalleryContentWidths {
    val Reading = 720.dp
    val Browsing = 1_200.dp
}

object GalleryGridMetrics {
    val Gap = 4.dp
    val CompactCell = 112.dp
    val MediumCell = 92.dp
    val ExpandedCell = 88.dp
}

enum class GalleryWindowClass { Compact, Medium, Expanded }

enum class GalleryNavigationType { BottomBar, Rail }

enum class GalleryFoldOrientation { Vertical, Horizontal }

data class GalleryFoldInfo(
    val orientation: GalleryFoldOrientation,
    val isSeparating: Boolean,
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
) {
    val hingeWidth: Dp get() = (right - left).coerceAtLeast(0.dp)
    val hingeHeight: Dp get() = (bottom - top).coerceAtLeast(0.dp)
    val enablesSideBySide: Boolean
        get() = isSeparating && orientation == GalleryFoldOrientation.Vertical
}

data class GalleryAdaptiveLayoutInfo(
    val windowClass: GalleryWindowClass,
    val navigationType: GalleryNavigationType,
    val gutter: Dp,
    val supportsTwoPane: Boolean,
    val foldInfo: GalleryFoldInfo? = null,
)

fun galleryWindowClass(width: Dp): GalleryWindowClass = when {
    width < 600.dp -> GalleryWindowClass.Compact
    width < 840.dp -> GalleryWindowClass.Medium
    else -> GalleryWindowClass.Expanded
}

fun galleryGridCellSize(width: Dp): Dp = when (galleryWindowClass(width)) {
    GalleryWindowClass.Compact -> GalleryGridMetrics.CompactCell
    GalleryWindowClass.Medium -> GalleryGridMetrics.MediumCell
    GalleryWindowClass.Expanded -> GalleryGridMetrics.ExpandedCell
}

fun galleryAdaptiveLayoutInfo(
    width: Dp,
    foldInfo: GalleryFoldInfo? = null,
): GalleryAdaptiveLayoutInfo {
    val windowClass = galleryWindowClass(width)
    return GalleryAdaptiveLayoutInfo(
        windowClass = windowClass,
        navigationType = if (windowClass == GalleryWindowClass.Compact) {
            GalleryNavigationType.BottomBar
        } else {
            GalleryNavigationType.Rail
        },
        gutter = when (windowClass) {
            GalleryWindowClass.Compact -> GallerySpacing.Lg
            GalleryWindowClass.Medium -> GallerySpacing.Xxl
            GalleryWindowClass.Expanded -> GallerySpacing.Section
        },
        supportsTwoPane = foldInfo?.enablesSideBySide == true || windowClass == GalleryWindowClass.Expanded,
        foldInfo = foldInfo,
    )
}

/** Monospace style for metadata, durations, and timeline values. */
val GalleryMonoTypography: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

@Composable
fun UGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
