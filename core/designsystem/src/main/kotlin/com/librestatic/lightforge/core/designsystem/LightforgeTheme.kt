package com.librestatic.lightforge.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

/**
 * Maximum line length for text, forms and settings rows, also inside wide panes: lines stay
 * readable (640–760 dp) instead of stretching across a tablet or desktop window.
 */
val ReadableContentMaxWidth: Dp = GalleryContentWidths.Reading

/**
 * Pane sizing for list-detail and supporting-pane layouts, always in dp and never as a fraction
 * of the window, so a master list keeps the same width on a foldable, a tablet and a desktop.
 */
object GalleryPaneMetrics {
    val MasterMinWidth = 300.dp
    val MasterMaxWidth = 360.dp

    /** Narrowest detail pane that still shows its titles and rows without wrapping mid-word. */
    val DetailMinWidth = 360.dp

    /** A detail pane this wide lets the master grow to [MasterMaxWidth]. */
    val DetailComfortableWidth = 560.dp

    val SupportingPaneMinWidth = 360.dp
    val SupportingPaneMaxWidth = 420.dp

    /** Content width from which a third, supporting pane may join list + detail. */
    val ThirdPaneMinContentWidth = 1_100.dp
}

/**
 * Master pane width for a list-detail layout in [contentWidth] (the window minus the navigation
 * rail), or null when the two panes do not fit with their minimums and the screen should show
 * one pane at a time. The master grows from 300 to 360 dp only out of the detail's spare room.
 */
fun galleryMasterPaneWidth(contentWidth: Dp): Dp? {
    if (contentWidth < GalleryPaneMetrics.MasterMinWidth + GalleryPaneMetrics.DetailMinWidth) return null
    return (contentWidth - GalleryPaneMetrics.DetailComfortableWidth)
        .coerceIn(GalleryPaneMetrics.MasterMinWidth, GalleryPaneMetrics.MasterMaxWidth)
}

/** Whether a supporting (third) pane fits beside list + detail in [contentWidth]. */
fun galleryShowsSupportingPane(contentWidth: Dp): Boolean =
    contentWidth >= GalleryPaneMetrics.ThirdPaneMinContentWidth

object GalleryGridMetrics {
    val Gap = 4.dp
    val CompactCell = 112.dp
    val MediumCell = 92.dp
    val ExpandedCell = 88.dp

    /** Target tile size for width-driven media grids (~3 columns compact, 5–7 expanded). */
    val TargetCell = 128.dp

    /** Default column count for a media grid of [width], never below three columns. */
    fun adaptiveColumns(width: Dp): Int =
        ((width + Gap) / (TargetCell + Gap)).toInt().coerceAtLeast(3)
}

enum class GalleryWindowClass { Compact, Medium, Expanded }

/** Album side panel sizing and default visibility per window class. */
object GallerySidePanelMetrics {
    fun width(windowClass: GalleryWindowClass): Dp = when (windowClass) {
        GalleryWindowClass.Compact -> 96.dp
        GalleryWindowClass.Medium -> 136.dp
        GalleryWindowClass.Expanded -> 168.dp
    }

    /** A stored user choice wins; otherwise the panel starts open only where there is room. */
    fun initiallyOpen(windowClass: GalleryWindowClass, stored: Boolean?): Boolean =
        stored ?: (windowClass != GalleryWindowClass.Compact)
}

/**
 * Root navigation chrome. [Floating] is the pill (Photos · Collections · Create) plus a separate
 * Search button over the bottom of the content; [Rail] is the side navigation rail.
 */
enum class GalleryNavigationType { Floating, Rail }

/** Window thresholds for [galleryNavigationType], in dp of the whole window. */
object GalleryNavigationThresholds {
    /** Below either of these the rail is dropped (a 393 dp tall landscape phone cannot fit it). */
    val RailMinWidth = 600.dp
    val RailMinHeight = 480.dp

    /** Coming from the floating navigation, the window has to clear these to switch to the rail. */
    val RailEnterWidth = 616.dp
    val RailEnterHeight = 496.dp
}

/**
 * Picks the navigation for a [width] × [height] window, never from orientation alone.
 *
 * - Floating when the width is below 600 dp or the height below 480 dp; rail otherwise.
 * - Hysteresis for freely resized windows: from [previous] = Floating the rail needs 616 × 496 dp,
 *   and an existing rail stays until the window drops below 600 × 480 dp.
 * - Tabletop (a separating horizontal hinge) keeps the floating navigation, which sits at the
 *   bottom of the lower half; a book posture (separating vertical hinge) keeps the rail in the
 *   starting half whenever the height allows one.
 */
fun galleryNavigationType(
    width: Dp,
    height: Dp,
    foldInfo: GalleryFoldInfo? = null,
    previous: GalleryNavigationType? = null,
): GalleryNavigationType {
    val fitsRail = width >= GalleryNavigationThresholds.RailMinWidth &&
        height >= GalleryNavigationThresholds.RailMinHeight
    val entersRail = width >= GalleryNavigationThresholds.RailEnterWidth &&
        height >= GalleryNavigationThresholds.RailEnterHeight
    val rail = when {
        foldInfo?.isTabletop == true -> false
        foldInfo?.enablesSideBySide == true -> height >= GalleryNavigationThresholds.RailMinHeight
        previous == GalleryNavigationType.Floating -> entersRail
        else -> fitsRail
    }
    return if (rail) GalleryNavigationType.Rail else GalleryNavigationType.Floating
}

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

    /** Half-open with a horizontal hinge: content above, controls on the lower half. */
    val isTabletop: Boolean
        get() = isSeparating && orientation == GalleryFoldOrientation.Horizontal
}

data class GalleryAdaptiveLayoutInfo(
    val windowClass: GalleryWindowClass,
    val navigationType: GalleryNavigationType,
    val gutter: Dp,
    val supportsTwoPane: Boolean,
    val foldInfo: GalleryFoldInfo? = null,
    /** Window height the policy was computed for; [Dp.Infinity] when only the width was known. */
    val windowHeight: Dp = Dp.Infinity,
    /** Width left for content beside the navigation (the whole window with floating navigation). */
    val contentWidth: Dp = Dp.Infinity,
)

/** Width of the shell's navigation rail. */
val GalleryNavigationRailWidth: Dp = 120.dp

/**
 * The shell's current adaptive policy, provided around every library surface so screens can read
 * the navigation type, gutter and content width instead of measuring the window again.
 */
val LocalGalleryAdaptiveLayoutInfo = staticCompositionLocalOf<GalleryAdaptiveLayoutInfo?> { null }

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

/**
 * Adaptive policy for a [width] × [height] window. [previousNavigationType] is the navigation the
 * window showed last, for the rail hysteresis of [galleryNavigationType]. The gutter is the one
 * content inset of the shell: screens indent their content by it instead of their own margins.
 */
fun galleryAdaptiveLayoutInfo(
    width: Dp,
    foldInfo: GalleryFoldInfo? = null,
    height: Dp = Dp.Infinity,
    previousNavigationType: GalleryNavigationType? = null,
): GalleryAdaptiveLayoutInfo {
    val windowClass = galleryWindowClass(width)
    val navigationType = galleryNavigationType(width, height, foldInfo, previousNavigationType)
    return GalleryAdaptiveLayoutInfo(
        windowClass = windowClass,
        navigationType = navigationType,
        gutter = when (windowClass) {
            GalleryWindowClass.Compact -> GallerySpacing.Lg
            GalleryWindowClass.Medium -> GallerySpacing.Xxl
            GalleryWindowClass.Expanded -> GallerySpacing.Section
        },
        supportsTwoPane = foldInfo?.enablesSideBySide == true || windowClass == GalleryWindowClass.Expanded,
        foldInfo = foldInfo,
        windowHeight = height,
        contentWidth = if (navigationType == GalleryNavigationType.Rail) {
            (width - GalleryNavigationRailWidth).coerceAtLeast(0.dp)
        } else width,
    )
}

/** Monospace style for metadata, durations, and timeline values. */
val GalleryMonoTypography: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * Container pair for positive confirmations (a finished setup, a completed task). Material has no
 * success role, so this is a fixed green tonal pair: tone 90 on tone 10 in light, tone 30 on
 * tone 90 in dark, both well above 7:1. Always draw [onContainer] on [container], never mix it
 * with scheme roles.
 */
@Immutable
data class GallerySuccessColors(val container: Color, val onContainer: Color)

private val SuccessLight = GallerySuccessColors(container = Color(0xFFB8F397), onContainer = Color(0xFF042100))
private val SuccessDark = GallerySuccessColors(container = Color(0xFF1F5108), onContainer = Color(0xFFB8F397))

val LocalGallerySuccessColors = staticCompositionLocalOf { SuccessLight }

@Composable
fun LightforgeTheme(
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
    CompositionLocalProvider(LocalGallerySuccessColors provides if (darkTheme) SuccessDark else SuccessLight) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
