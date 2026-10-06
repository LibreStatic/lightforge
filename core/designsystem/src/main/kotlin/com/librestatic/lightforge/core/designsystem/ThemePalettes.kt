package com.librestatic.lightforge.core.designsystem

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.librestatic.lightforge.core.preferences.ThemePalette

/**
 * The colour scheme for [palette]. Material You follows the wallpaper on Android 12+ when
 * [dynamicColor] is on and falls back to the baseline schemes otherwise; the fixed palettes
 * define every role so container/on-container pairs stay matched. [pureBlack] turns the
 * background and surfaces black for OLED screens; it only applies to dark schemes.
 */
fun lightforgeColorScheme(
    context: Context,
    palette: ThemePalette,
    darkTheme: Boolean,
    pureBlack: Boolean = false,
    dynamicColor: Boolean = true,
): ColorScheme {
    val dark = darkTheme || palette == ThemePalette.MinimalistBlack
    val base = fixedColorScheme(palette, dark) ?: when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    return if (dark && (pureBlack || palette == ThemePalette.MinimalistBlack)) base.toPureBlack() else base
}

/** The fixed scheme of [palette], or null for Material You, whose colours come from the system. */
internal fun fixedColorScheme(palette: ThemePalette, dark: Boolean): ColorScheme? = when (palette) {
    ThemePalette.MaterialYou -> null
    ThemePalette.Neutral -> if (dark) NeutralDark else NeutralLight
    ThemePalette.Rounded -> if (dark) RoundedDark else RoundedLight
    ThemePalette.Crisp -> if (dark) CrispDark else CrispLight
    ThemePalette.Warm -> if (dark) WarmDark else WarmLight
    ThemePalette.MinimalistBlack -> MinimalistBlack
}

/**
 * Black background and surfaces with a dark grey container ladder, faintly tinted by the
 * primary colour so Material You keeps its hue. Content roles are untouched: the surfaces only get
 * darker, so their contrast with the on-surface colours only improves.
 */
fun ColorScheme.toPureBlack(): ColorScheme {
    fun tone(gray: Long) = lerp(Color(gray), primary, 0.04f)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = tone(0xFF0B0B0B),
        surfaceContainer = tone(0xFF121212),
        surfaceContainerHigh = tone(0xFF1A1A1A),
        surfaceContainerHighest = tone(0xFF242424),
        surfaceBright = tone(0xFF2C2C2C),
    )
}

// Neutral: white and graphite surfaces with a calm blue accent.
private val NeutralLight = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    inversePrimary = Color(0xFFA8C7FA),
    secondary = Color(0xFF00639B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2E7FF), onSecondaryContainer = Color(0xFF004A77),
    tertiary = Color(0xFF146C2E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4EED0), onTertiaryContainer = Color(0xFF0F5223),
    background = Color.White, onBackground = Color(0xFF1F1F1F),
    surface = Color.White, onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E3E1), onSurfaceVariant = Color(0xFF444746),
    surfaceTint = Color(0xFF0B57D0),
    inverseSurface = Color(0xFF303030), inverseOnSurface = Color(0xFFF2F2F2),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF747775), outlineVariant = Color(0xFFC4C7C5),
    surfaceBright = Color.White, surfaceDim = Color(0xFFD3DBE5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF0F4F9), surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFDDE3EA),
)

private val NeutralDark = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    inversePrimary = Color(0xFF0B57D0),
    secondary = Color(0xFF7FCFFF), onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF004A77), onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C), onTertiary = Color(0xFF0A3818),
    tertiaryContainer = Color(0xFF0F5223), onTertiaryContainer = Color(0xFFC4EED0),
    background = Color(0xFF131314), onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314), onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF444746), onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceTint = Color(0xFFA8C7FA),
    inverseSurface = Color(0xFFE3E3E3), inverseOnSurface = Color(0xFF303030),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746),
    surfaceBright = Color(0xFF37393B), surfaceDim = Color(0xFF131314),
    surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20), surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
)

// Rounded: soft grey canvas with white cards and a vivid blue accent.
private val RoundedLight = lightColorScheme(
    primary = Color(0xFF1A5FD8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF), onPrimaryContainer = Color(0xFF001848),
    inversePrimary = Color(0xFFB1C5FF),
    secondary = Color(0xFF4F5B76), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E2FF), onSecondaryContainer = Color(0xFF0A1A36),
    tertiary = Color(0xFF00696B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9CF1F2), onTertiaryContainer = Color(0xFF002020),
    background = Color(0xFFF6F6F6), onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFF6F6F6), onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFE3E3E8), onSurfaceVariant = Color(0xFF4A4A4F),
    surfaceTint = Color(0xFF1A5FD8),
    inverseSurface = Color(0xFF2F2F31), inverseOnSurface = Color(0xFFF2F2F4),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7A7A80), outlineVariant = Color(0xFFC8C8CE),
    surfaceBright = Color.White, surfaceDim = Color(0xFFD9D9DC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF0F0F2), surfaceContainerHigh = Color(0xFFEAEAEC),
    surfaceContainerHighest = Color(0xFFE3E3E6),
)

private val RoundedDark = darkColorScheme(
    primary = Color(0xFF7FB0FF), onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF0B4AB0), onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF1A5FD8),
    secondary = Color(0xFFB8C6E6), onSecondary = Color(0xFF22304A),
    secondaryContainer = Color(0xFF384661), onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF80D4D6), onTertiary = Color(0xFF003738),
    tertiaryContainer = Color(0xFF004F51), onTertiaryContainer = Color(0xFF9CF1F2),
    background = Color(0xFF101010), onBackground = Color(0xFFEBEBEB),
    surface = Color(0xFF101010), onSurface = Color(0xFFEBEBEB),
    surfaceVariant = Color(0xFF45454A), onSurfaceVariant = Color(0xFFC2C2C8),
    surfaceTint = Color(0xFF7FB0FF),
    inverseSurface = Color(0xFFE6E6E8), inverseOnSurface = Color(0xFF2F2F31),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E8E94), outlineVariant = Color(0xFF45454A),
    surfaceBright = Color(0xFF36363A), surfaceDim = Color(0xFF101010),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF252527),
    surfaceContainerHighest = Color(0xFF2E2E31),
)

// Crisp: plain white or black, cool greys and a system-style blue.
private val CrispLight = lightColorScheme(
    primary = Color(0xFF0062CC), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF), onPrimaryContainer = Color(0xFF001D3D),
    inversePrimary = Color(0xFF5AA9FF),
    secondary = Color(0xFF48484A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF9A4A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC5), onTertiaryContainer = Color(0xFF301400),
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF3C3C43),
    surfaceTint = Color(0xFF0062CC),
    inverseSurface = Color(0xFF1C1C1E), inverseOnSurface = Color(0xFFF2F2F7),
    error = Color(0xFFC4141E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFC6C6C8),
    surfaceBright = Color.White, surfaceDim = Color(0xFFD8D8DD),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F9FB),
    surfaceContainer = Color(0xFFF2F2F7), surfaceContainerHigh = Color(0xFFE9E9EE),
    surfaceContainerHighest = Color(0xFFE0E0E5),
)

private val CrispDark = darkColorScheme(
    primary = Color(0xFF5AA9FF), onPrimary = Color(0xFF002F63),
    primaryContainer = Color(0xFF00478F), onPrimaryContainer = Color(0xFFD6E7FF),
    inversePrimary = Color(0xFF0062CC),
    secondary = Color(0xFFC7C7CC), onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF3A3A3C), onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFFFFB68A), onTertiary = Color(0xFF502400),
    tertiaryContainer = Color(0xFF723600), onTertiaryContainer = Color(0xFFFFDCC5),
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF3A3A3C), onSurfaceVariant = Color(0xFFC7C7CC),
    surfaceTint = Color(0xFF5AA9FF),
    inverseSurface = Color(0xFFF2F2F7), inverseOnSurface = Color(0xFF1C1C1E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E8E93), outlineVariant = Color(0xFF48484A),
    surfaceBright = Color(0xFF3A3A3C), surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
)

// Warm: cream and cocoa surfaces with an orange accent.
private val WarmLight = lightColorScheme(
    primary = Color(0xFFA04100), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA), onPrimaryContainer = Color(0xFF351000),
    inversePrimary = Color(0xFFFFB68F),
    secondary = Color(0xFF765848), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCA), onSecondaryContainer = Color(0xFF2B160A),
    tertiary = Color(0xFF646032), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBE4AA), onTertiaryContainer = Color(0xFF1E1C00),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231A15),
    surface = Color(0xFFFFF8F6), onSurface = Color(0xFF231A15),
    surfaceVariant = Color(0xFFF4DED4), onSurfaceVariant = Color(0xFF53443C),
    surfaceTint = Color(0xFFA04100),
    inverseSurface = Color(0xFF392E2A), inverseOnSurface = Color(0xFFFFEDE6),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF85736B), outlineVariant = Color(0xFFD8C2B9),
    surfaceBright = Color(0xFFFFF8F6), surfaceDim = Color(0xFFE8D7CF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE3), surfaceContainerHigh = Color(0xFFF6E5DD),
    surfaceContainerHighest = Color(0xFFF1DFD7),
)

private val WarmDark = darkColorScheme(
    primary = Color(0xFFFFB68F), onPrimary = Color(0xFF552000),
    primaryContainer = Color(0xFF793000), onPrimaryContainer = Color(0xFFFFDBCA),
    inversePrimary = Color(0xFFA04100),
    secondary = Color(0xFFE6BEAB), onSecondary = Color(0xFF432B1E),
    secondaryContainer = Color(0xFF5C4033), onSecondaryContainer = Color(0xFFFFDBCA),
    tertiary = Color(0xFFCFC890), onTertiary = Color(0xFF343107),
    tertiaryContainer = Color(0xFF4B481C), onTertiaryContainer = Color(0xFFEBE4AA),
    background = Color(0xFF1A120E), onBackground = Color(0xFFF1DFD7),
    surface = Color(0xFF1A120E), onSurface = Color(0xFFF1DFD7),
    surfaceVariant = Color(0xFF53443C), onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceTint = Color(0xFFFFB68F),
    inverseSurface = Color(0xFFF1DFD7), inverseOnSurface = Color(0xFF392E2A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08D84), outlineVariant = Color(0xFF53443C),
    surfaceBright = Color(0xFF42372F), surfaceDim = Color(0xFF1A120E),
    surfaceContainerLowest = Color(0xFF140C09), surfaceContainerLow = Color(0xFF231A15),
    surfaceContainer = Color(0xFF271E19), surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D332D),
)

// Minimalist Black: monochrome on pure black, always dark.
private val MinimalistBlack = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFFD4D4D4), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFFBDBDBD), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF303030), onTertiaryContainer = Color(0xFFF2F2F2),
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.White,
    inverseSurface = Color(0xFFF2F2F2), inverseOnSurface = Color.Black,
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFF333333),
    surfaceBright = Color(0xFF2C2C2C), surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
)
