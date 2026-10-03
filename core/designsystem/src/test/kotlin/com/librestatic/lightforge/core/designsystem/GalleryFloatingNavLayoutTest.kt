package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFloatingNavLayoutTest {
    // A 393 dp phone: 393 - 2 * 12 side margins - 56 search - 8 gap.
    private val phonePill = 305.dp

    @Test
    fun wideWindowsShowEveryIcon() {
        val layout = galleryFloatingNavLayout(listOf(48.dp, 80.dp, 50.dp), availablePillWidth = 700.dp)
        assertEquals(GalleryFloatingNavIcons.All, layout.icons)
        assertEquals(GalleryFloatingNavigationDefaults.ItemPadding, layout.itemPadding)
        assertFalse(layout.ellipsize)
    }

    @Test
    fun portraitPhoneShowsOnlyTheSelectedIcon() {
        // English labels at 1x: Photos · Collections · Create.
        val layout = galleryFloatingNavLayout(listOf(44.dp, 75.dp, 44.dp), phonePill)
        assertEquals(GalleryFloatingNavIcons.SelectedOnly, layout.icons)
        assertEquals(16.dp, layout.itemPadding)
        assertFalse(layout.ellipsize)
    }

    @Test
    fun longLocalesAtLargeFontScaleTightenPaddingBeforeEllipsizing() {
        // Roughly French at 1.3x: Photos · Collections · Créer.
        val tighter = galleryFloatingNavLayout(listOf(57.dp, 98.dp, 52.dp), phonePill)
        assertFalse(tighter.ellipsize)
        assertTrue(tighter.itemPadding < 16.dp)
        // Roughly German at 1.3x: Fotos · Sammlungen · Erstellen drops the icon before ellipsizing.
        val labelsOnly = galleryFloatingNavLayout(listOf(52.dp, 104.dp, 78.dp), phonePill)
        assertFalse(labelsOnly.ellipsize)
        assertEquals(GalleryFloatingNavIcons.None, labelsOnly.icons)
        // Labels that cannot fit even at 8 dp padding ellipsize instead of overflowing.
        val overflow = galleryFloatingNavLayout(listOf(100.dp, 140.dp, 90.dp), phonePill)
        assertTrue(overflow.ellipsize)
        assertEquals(GalleryFloatingNavIcons.None, overflow.icons)
    }

    @Test
    fun contentPaddingClearsTheClusterByTwentyFourDp() {
        assertEquals(24.dp + 56.dp + 48.dp, GalleryFloatingNavigationDefaults.contentBottomPadding(48.dp))
        assertEquals(48.dp + 8.dp + 56.dp, GalleryFloatingNavigationDefaults.occupiedHeight(48.dp))
    }
}
