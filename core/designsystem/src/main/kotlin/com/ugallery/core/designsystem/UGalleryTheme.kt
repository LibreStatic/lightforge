package com.ugallery.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object GalleryColors {
    val Background = Color(0xFFF8FAFD)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceTonal = Color(0xFFE8F0FE)
    val Foreground = Color(0xFF202124)
    val ForegroundSecondary = Color(0xFF3C4043)
    val Muted = Color(0xFF5F6368)
    val Border = Color(0xFFDADCE0)
    val BorderSoft = Color(0xFFEDF0F2)
    val Accent = Color(0xFF1A73E8)
    val Success = Color(0xFF188038)
    val Warning = Color(0xFFF9AB00)
    val Danger = Color(0xFFD93025)
}

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

object GalleryRadii {
    val Small = 4.dp
    val Medium = 12.dp
    val Large = 24.dp
    val Pill = 9999.dp
}

object GalleryMotion {
    const val FastMillis = 150
    const val BaseMillis = 250
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
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

private val LightScheme = lightColorScheme(
    primary = GalleryColors.Accent,
    onPrimary = GalleryColors.Surface,
    background = GalleryColors.Background,
    onBackground = GalleryColors.Foreground,
    surface = GalleryColors.Surface,
    onSurface = GalleryColors.Foreground,
    surfaceVariant = GalleryColors.SurfaceTonal,
    onSurfaceVariant = GalleryColors.ForegroundSecondary,
    outline = GalleryColors.Border,
    error = GalleryColors.Danger,
)

/**
 * Dark palette mirroring [GalleryColors] semantics so both schemes derive
 * from named tokens instead of inline literals.
 */
object GalleryColorsDark {
    val Background = Color(0xFF111318)
    val Surface = Color(0xFF1A1C20)
    val SurfaceTonal = Color(0xFF2B3038)
    val Foreground = Color(0xFFE3E3E3)
    val Border = Color(0xFF8E918F)
    val Accent = Color(0xFFA8C7FA)
    val Danger = Color(0xFFFFB4AB)
}

private val DarkScheme = darkColorScheme(
    primary = GalleryColorsDark.Accent,
    background = GalleryColorsDark.Background,
    surface = GalleryColorsDark.Surface,
    onSurface = GalleryColorsDark.Foreground,
    surfaceVariant = GalleryColorsDark.SurfaceTonal,
    outline = GalleryColorsDark.Border,
    error = GalleryColorsDark.Danger,
)

private val GalleryTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 64.sp,
        lineHeight = 72.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 56.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)


/** Monospace style for metadata, durations, and timeline values. */
val GalleryMonoTypography: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

private val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(GalleryRadii.Small),
    small = RoundedCornerShape(GalleryRadii.Medium),
    medium = RoundedCornerShape(GalleryRadii.Medium),
    large = RoundedCornerShape(GalleryRadii.Large),
    extraLarge = RoundedCornerShape(GalleryRadii.Large),
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
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colors,
        typography = GalleryTypography,
        shapes = GalleryShapes,
        content = content,
    )
}
