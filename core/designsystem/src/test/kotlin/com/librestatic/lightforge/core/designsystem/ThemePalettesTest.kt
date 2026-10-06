package com.librestatic.lightforge.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.librestatic.lightforge.core.preferences.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePalettesTest {
    private fun schemes(): List<Pair<String, ColorScheme>> = buildList {
        ThemePalette.entries.filter { it != ThemePalette.MaterialYou }.forEach { palette ->
            listOf(false, true).forEach { dark ->
                val scheme = fixedColorScheme(palette, dark)!!
                add("${palette.name}/${if (dark) "dark" else "light"}" to scheme)
                if (dark) add("${palette.name}/pure-black" to scheme.toPureBlack())
            }
        }
        add("MaterialYouFallback/pure-black" to darkColorScheme().toPureBlack())
    }

    @Test
    fun everyRoleOfEveryPaletteIsReadable() {
        schemes().forEach { (name, scheme) ->
            GalleryColorRoles.all(scheme).forEach { (role, pair) ->
                val ratio = galleryContrastRatio(pair.content, pair.container)
                assertTrue("$name/$role contrast $ratio", ratio >= 4.5f)
            }
            listOf(
                "error" to galleryContrastRatio(scheme.onError, scheme.error),
                "secondary" to galleryContrastRatio(scheme.onSecondary, scheme.secondary),
                "tertiary" to galleryContrastRatio(scheme.onTertiary, scheme.tertiary),
                "primary-on-surface" to galleryContrastRatio(scheme.primary, scheme.surface),
            ).forEach { (role, ratio) -> assertTrue("$name/$role contrast $ratio", ratio >= 4.5f) }
        }
    }

    @Test
    fun pureBlackKeepsTheSurfaceLadder() {
        schemes().filter { it.first.endsWith("pure-black") }.forEach { (name, scheme) ->
            assertEquals(name, Color.Black, scheme.background)
            assertEquals(name, Color.Black, scheme.surface)
            val ladder = listOf(
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).map { it.luminance() }
            assertEquals(name, ladder.sorted(), ladder)
            assertTrue(name, ladder.zipWithNext().all { (a, b) -> a < b })
        }
    }

    @Test
    fun minimalistBlackIsAlwaysDark() {
        assertEquals(fixedColorScheme(ThemePalette.MinimalistBlack, dark = false), fixedColorScheme(ThemePalette.MinimalistBlack, dark = true))
        assertEquals(Color.Black, fixedColorScheme(ThemePalette.MinimalistBlack, dark = false)!!.background)
        assertNull(fixedColorScheme(ThemePalette.MaterialYou, dark = true))
    }
}
