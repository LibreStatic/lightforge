package com.librestatic.lightforge.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFoundationsTest {
    @Test
    fun floorRaisesOnlySmallerSizes() {
        assertEquals(12.sp, flooredTextSize(11.sp, 12.sp))
        assertEquals(16.sp, flooredTextSize(16.sp, 12.sp))
        assertEquals(TextUnit.Unspecified, flooredTextSize(TextUnit.Unspecified, 12.sp))
    }

    @Test
    fun typographyHasNothingBelowTheFloor() {
        val t = galleryTypography()
        listOf(
            t.displayLarge, t.displayMedium, t.displaySmall,
            t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall,
            t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium, t.labelSmall,
        ).forEach { style ->
            assertTrue("${style.fontSize}", style.fontSize.value >= GalleryTypeFloor.Metadata.value)
        }
        assertTrue(t.labelLarge.fontSize.value >= GalleryTypeFloor.Control.value)
    }

    @Test
    fun raisedStyleKeepsItsLeadingRatio() {
        val base = Typography()
        val raised = galleryTypography(base).labelSmall
        assertEquals(12f, raised.fontSize.value, 0.001f)
        val expected = base.labelSmall.lineHeight.value * 12f / base.labelSmall.fontSize.value
        assertEquals(expected, raised.lineHeight.value, 0.001f)
    }

    @Test
    fun everyColorRoleIsReadableInLightAndDark() {
        listOf("light" to lightColorScheme(), "dark" to darkColorScheme()).forEach { (name, scheme) ->
            GalleryColorRoles.all(scheme).forEach { (role, pair) ->
                val ratio = galleryContrastRatio(pair.content, pair.container)
                assertTrue("$name/$role contrast $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun contrastRatioMatchesWcagExtremes() {
        assertEquals(21f, galleryContrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, galleryContrastRatio(Color.White, Color.White), 0.01f)
    }

    @Test
    fun scrimKeepsOverlayTextReadableOverAWhitePhoto() {
        listOf(GalleryOverlayTokens.ScrimBottom, GalleryOverlayTokens.ScrimTop).forEach { scrim ->
            val behind = scrim.compositeOver(Color.White)
            val ratio = galleryContrastRatio(GalleryScrims.Content, behind)
            assertTrue("scrim $scrim contrast $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun heightsMeetTheTouchTarget() {
        assertTrue(GalleryHeights.Row >= GalleryHeights.TouchTarget)
        assertTrue(GalleryHeights.FloatingBar >= GalleryHeights.TouchTarget)
        assertEquals(48.dp, GalleryHeights.TouchTarget)
    }
}
