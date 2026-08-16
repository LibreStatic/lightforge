package com.ugallery.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

object GalleryGridMetrics {
    val Gap = 4.dp
    val CompactCell = 112.dp
    val MediumCell = 92.dp
    val ExpandedCell = 88.dp
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

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    background = Color(0xFF111318),
    surface = Color(0xFF1A1C20),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF2B3038),
    outline = Color(0xFF8E918F),
    error = Color(0xFFFFB4AB),
)

private val GalleryTypography = androidx.compose.material3.Typography(
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

@Composable
fun UGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = GalleryTypography,
        content = content,
    )
}

