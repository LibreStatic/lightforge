package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

// Design-system foundations: the rules every screen draws from. Spacing lives in GallerySpacing
// (LightforgeTheme.kt); this file adds the shape, type, color and scrim grammar on top of it.

/**
 * Semantic corner radii. The scale is Material's own (4/8/12/16/28 dp, as in
 * [MaterialTheme.shapes]); the names say what each radius is for, so two screens never pick
 * different radii for the same kind of element.
 */
object GalleryShapes {
    /** Grid thumbnails and their selection frame. Small enough that dense grids stay calm. */
    val Thumbnail: Shape = RoundedCornerShape(8.dp)

    /** Badges drawn over a thumbnail (duration, file type, stack count). */
    val Badge: Shape = RoundedCornerShape(8.dp)

    /** Cards that are a destination or a piece of content (highlights, albums, tool cards). */
    val Card: Shape = RoundedCornerShape(16.dp)

    /** Inline status plates and banners inside a page. */
    val Plate: Shape = RoundedCornerShape(12.dp)

    /** Floating bars (selection bar, floating navigation) and chips that act as one control. */
    val Pill: Shape = CircleShape

    /** Dialogs and sheets. */
    val Sheet: Shape = RoundedCornerShape(28.dp)
}

/** Standard heights so rows, chips and bars line up across screens. */
object GalleryHeights {
    /** Minimum touch target for anything tappable. */
    val TouchTarget: Dp = 48.dp

    /** A one-line settings or list row. */
    val Row: Dp = 56.dp

    /** A row with a supporting line. */
    val TwoLineRow: Dp = 72.dp

    /** Material top app bar (small). */
    val TopBar: Dp = 64.dp

    /** Filter and assist chips. */
    val Chip: Dp = 32.dp

    /** Floating selection bar and floating navigation. */
    val FloatingBar: Dp = 64.dp
}

/**
 * Type floor: nothing in the app is smaller than [Metadata], and controls (buttons, chips, menu
 * items) read at [Control] or larger. Material's baseline labelSmall is 11 sp, which is below the
 * floor, so [galleryTypography] raises it.
 */
object GalleryTypeFloor {
    /** Smallest size for any text, used for metadata and badges. */
    val Metadata: TextUnit = 12.sp

    /** Smallest size for text inside a control. */
    val Control: TextUnit = 14.sp
}

/** Returns [size] raised to [floor] when it is smaller; unspecified sizes stay unspecified. */
fun flooredTextSize(size: TextUnit, floor: TextUnit): TextUnit =
    if (!size.isSpecified || !floor.isSpecified || size.value >= floor.value) size else floor

private fun TextStyle.withFloor(floor: TextUnit): TextStyle {
    val raised = flooredTextSize(fontSize, floor)
    if (raised == fontSize) return this
    // Keep the original leading ratio so raised styles do not clip their descenders.
    val leading = if (lineHeight.isSpecified && fontSize.isSpecified && fontSize.value > 0f) {
        (lineHeight.value * raised.value / fontSize.value).sp
    } else lineHeight
    return copy(fontSize = raised, lineHeight = leading)
}

/**
 * The app typography: Material's scale with the [GalleryTypeFloor] applied. labelSmall moves from
 * 11 sp to 12 sp; every other baseline style is already at or above the floor.
 */
fun galleryTypography(base: Typography = Typography()): Typography = base.copy(
    labelSmall = base.labelSmall.withFloor(GalleryTypeFloor.Metadata),
    labelMedium = base.labelMedium.withFloor(GalleryTypeFloor.Metadata),
    bodySmall = base.bodySmall.withFloor(GalleryTypeFloor.Metadata),
    labelLarge = base.labelLarge.withFloor(GalleryTypeFloor.Control),
)

/** The app-wide [galleryTypography], built once. [LightforgeTheme] installs it. */
val GalleryTypographyScale: Typography by lazy { galleryTypography() }

/** A container color and the only content color allowed on it. */
@Immutable
data class GalleryColorPair(val container: Color, val content: Color)

/**
 * The color grammar. Every surface in the app is one of these roles, and content on it uses the
 * matching `on*` color (or inherits it through `LocalContentColor`). Never mix roles: no
 * `primary` text on `secondaryContainer`, no hard-coded black or white over the theme.
 *
 * | Role         | Container                | Content                  | Use for                                   |
 * |--------------|--------------------------|--------------------------|-------------------------------------------|
 * | page         | background               | onBackground             | the screen behind everything              |
 * | chrome       | surfaceContainer         | onSurface                | app bars once scrolled, rails, panels     |
 * | raised       | surfaceContainerHigh     | onSurface                | floating bars, menus, cards over a page   |
 * | highest      | surfaceContainerHighest  | onSurface                | inputs, the most prominent card           |
 * | active       | secondaryContainer       | onSecondaryContainer     | selected chip/nav item, active state      |
 * | accent       | primaryContainer         | onPrimaryContainer       | the one emphasised card or FAB            |
 * | highlight    | tertiaryContainer        | onTertiaryContainer      | promotional or "new" callouts             |
 * | emphasis     | primary                  | onPrimary                | filled primary buttons, selection check   |
 * | destructive  | errorContainer           | onErrorContainer         | delete confirmations, failure banners     |
 * | inverse      | inverseSurface           | inverseOnSurface         | snackbars and tooltips                    |
 *
 * Metadata (dates, counts, captions) uses `onSurfaceVariant` on any surface role above.
 * Disabled content is the role's content color at 38 % alpha; disabled containers at 12 %.
 * In dark theme the surface levels are explicit: page < chrome < raised < highest, never an
 * inverted light scheme. Text over photos never uses the scheme: see [GalleryScrims].
 */
object GalleryColorRoles {
    fun page(scheme: ColorScheme) = GalleryColorPair(scheme.background, scheme.onBackground)
    fun chrome(scheme: ColorScheme) = GalleryColorPair(scheme.surfaceContainer, scheme.onSurface)
    fun raised(scheme: ColorScheme) = GalleryColorPair(scheme.surfaceContainerHigh, scheme.onSurface)
    fun highest(scheme: ColorScheme) = GalleryColorPair(scheme.surfaceContainerHighest, scheme.onSurface)
    fun active(scheme: ColorScheme) = GalleryColorPair(scheme.secondaryContainer, scheme.onSecondaryContainer)
    fun accent(scheme: ColorScheme) = GalleryColorPair(scheme.primaryContainer, scheme.onPrimaryContainer)
    fun highlight(scheme: ColorScheme) = GalleryColorPair(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    fun emphasis(scheme: ColorScheme) = GalleryColorPair(scheme.primary, scheme.onPrimary)
    fun destructive(scheme: ColorScheme) = GalleryColorPair(scheme.errorContainer, scheme.onErrorContainer)
    fun inverse(scheme: ColorScheme) = GalleryColorPair(scheme.inverseSurface, scheme.inverseOnSurface)

    /** Metadata on a surface role: the surface's container with onSurfaceVariant text. */
    fun metadata(scheme: ColorScheme, surface: GalleryColorPair = chrome(scheme)) =
        GalleryColorPair(surface.container, scheme.onSurfaceVariant)

    /** Every role, for contrast checks. */
    fun all(scheme: ColorScheme): Map<String, GalleryColorPair> = mapOf(
        "page" to page(scheme),
        "chrome" to chrome(scheme),
        "raised" to raised(scheme),
        "highest" to highest(scheme),
        "active" to active(scheme),
        "accent" to accent(scheme),
        "highlight" to highlight(scheme),
        "emphasis" to emphasis(scheme),
        "destructive" to destructive(scheme),
        "inverse" to inverse(scheme),
        "metadata" to metadata(scheme),
        "metadata-raised" to metadata(scheme, raised(scheme)),
    )

    /** Shorthand for the current theme. */
    val current: GalleryColorRolesInTheme
        @Composable @ReadOnlyComposable get() = GalleryColorRolesInTheme(MaterialTheme.colorScheme)
}

/** [GalleryColorRoles] bound to one scheme, for use inside composables. */
@Immutable
class GalleryColorRolesInTheme(private val scheme: ColorScheme) {
    val page get() = GalleryColorRoles.page(scheme)
    val chrome get() = GalleryColorRoles.chrome(scheme)
    val raised get() = GalleryColorRoles.raised(scheme)
    val highest get() = GalleryColorRoles.highest(scheme)
    val active get() = GalleryColorRoles.active(scheme)
    val accent get() = GalleryColorRoles.accent(scheme)
    val highlight get() = GalleryColorRoles.highlight(scheme)
    val emphasis get() = GalleryColorRoles.emphasis(scheme)
    val destructive get() = GalleryColorRoles.destructive(scheme)
    val inverse get() = GalleryColorRoles.inverse(scheme)
}

/** WCAG contrast ratio between two opaque colors (1..21). Translucent [foreground] is composited. */
fun galleryContrastRatio(foreground: Color, background: Color): Float {
    val bg = if (background.alpha < 1f) background.compositeOver(Color.White) else background
    val fg = if (foreground.alpha < 1f) foreground.compositeOver(bg) else foreground
    val a = fg.luminance() + 0.05f
    val b = bg.luminance() + 0.05f
    return if (a > b) a / b else b / a
}

/**
 * Scrims for text and controls drawn over photos. Media pixels are not a theme surface, so the
 * pair is fixed: [GalleryOverlayTokens.Content] over a dark scrim. The bottom and top gradients
 * end at an alpha that keeps white text at 4.5:1 even over a pure white photo (unit-tested).
 */
object GalleryScrims {
    /** Content color for anything drawn on a scrim. */
    val Content: Color get() = GalleryOverlayTokens.Content

    /** Solid scrim behind a label over media, e.g. a highlight card title. */
    val Label: Color get() = GalleryOverlayTokens.ScrimBottom

    /** Gradient that darkens the bottom of a media card for a title. */
    fun bottom(): Brush = Brush.verticalGradient(
        0f to Color.Transparent,
        0.45f to GalleryOverlayTokens.ScrimMiddle,
        1f to GalleryOverlayTokens.ScrimBottom,
    )

    /** Gradient that darkens the top of a media surface for controls. */
    fun top(): Brush = Brush.verticalGradient(
        0f to GalleryOverlayTokens.ScrimTop,
        1f to Color.Transparent,
    )

    /** Behind a modal over the app (dialogs, sheets): the scheme's own scrim at 32 %. */
    val Modal: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
}
