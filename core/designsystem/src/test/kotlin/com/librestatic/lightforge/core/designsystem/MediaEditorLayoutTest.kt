package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEditorLayoutTest {
    private fun mode(width: Dp, height: Dp, previous: MediaEditorLayoutMode? = null) =
        mediaEditorLayout(width, height, previous = previous).mode

    @Test
    fun phonesStackInPortraitAndSplitInLandscape() {
        assertEquals(MediaEditorLayoutMode.Stacked, mode(393.dp, 852.dp))
        assertEquals(MediaEditorLayoutMode.TwoPane, mode(852.dp, 393.dp))
        // A small phone on its side still fits 440 dp of media beside a 300 dp inspector.
        assertEquals(MediaEditorLayoutMode.TwoPane, mode(780.dp, 360.dp))
        assertEquals(MediaEditorLayoutMode.Stacked, mode(720.dp, 420.dp))
    }

    @Test
    fun squareFoldableGetsTheSameLayoutEitherWayRound() {
        val portrait = mediaEditorLayout(852.dp, 883.dp)
        val landscape = mediaEditorLayout(883.dp, 852.dp)
        assertEquals(MediaEditorLayoutMode.TwoPane, portrait.mode)
        assertEquals(MediaEditorLayoutMode.TwoPane, landscape.mode)
        assertEquals(360.dp, portrait.inspectorWidth)
        assertEquals(360.dp, landscape.inspectorWidth)
    }

    @Test
    fun tallWindowsStackEvenWhenWide() {
        assertEquals(MediaEditorLayoutMode.Stacked, mode(800.dp, 1280.dp))
    }

    @Test
    fun breakpointHasHysteresis() {
        assertEquals(MediaEditorLayoutMode.Stacked, mode(790.dp, 760.dp))
        assertEquals(MediaEditorLayoutMode.TwoPane, mode(800.dp, 760.dp))
        // Shrinking a two-pane window keeps it until it drops below 776 dp.
        assertEquals(MediaEditorLayoutMode.TwoPane, mode(780.dp, 760.dp, MediaEditorLayoutMode.TwoPane))
        assertEquals(MediaEditorLayoutMode.Stacked, mode(775.dp, 760.dp, MediaEditorLayoutMode.TwoPane))
        assertEquals(MediaEditorLayoutMode.Stacked, mode(780.dp, 760.dp, MediaEditorLayoutMode.Stacked))
    }

    @Test
    fun inspectorStaysBetween360And440AndLeavesTheMediaRoom() {
        listOf(852.dp to 393.dp, 1280.dp to 800.dp, 1920.dp to 1080.dp, 883.dp to 852.dp).forEach { (w, h) ->
            val layout = mediaEditorLayout(w, h)
            assertTrue("$w x $h", layout.inspectorWidth in 360.dp..440.dp)
            assertTrue("$w x $h", w - layout.inspectorWidth >= MediaEditorLayoutTokens.MinMediaWidth)
        }
        assertEquals(440.dp, mediaEditorLayout(1920.dp, 1080.dp).inspectorWidth)
        // Below 800 dp the inspector shrinks toward 300 dp before the media drops under 440 dp.
        val short = mediaEditorLayout(760.dp, 360.dp)
        assertEquals(320.dp, short.inspectorWidth)
        assertEquals(300.dp, mediaEditorLayout(740.dp, 360.dp).inspectorWidth)
    }

    @Test
    fun separatingHingesSplitTheEditorAroundTheHinge() {
        val book = mediaEditorLayout(
            900.dp, 1_000.dp,
            GalleryFoldInfo(GalleryFoldOrientation.Vertical, true, 440.dp, 0.dp, 460.dp, 1_000.dp),
        )
        assertEquals(MediaEditorLayoutMode.Book, book.mode)
        assertEquals(440.dp, book.hingeStart)
        assertEquals(460.dp, book.hingeEnd)
        val tabletop = mediaEditorLayout(
            900.dp, 1_000.dp,
            GalleryFoldInfo(GalleryFoldOrientation.Horizontal, true, 0.dp, 500.dp, 900.dp, 520.dp),
        )
        assertEquals(MediaEditorLayoutMode.Tabletop, tabletop.mode)
        // A pane narrower than 320 dp cannot hold a region: fall back to the size rules.
        val lopsided = mediaEditorLayout(
            900.dp, 1_000.dp,
            GalleryFoldInfo(GalleryFoldOrientation.Vertical, true, 200.dp, 0.dp, 220.dp, 1_000.dp),
        )
        assertEquals(MediaEditorLayoutMode.TwoPane, lopsided.mode)
        // A flat (non-separating) fold changes nothing.
        val flat = mediaEditorLayout(
            852.dp, 883.dp,
            GalleryFoldInfo(GalleryFoldOrientation.Vertical, false, 426.dp, 0.dp, 426.dp, 883.dp),
        )
        assertEquals(MediaEditorLayoutMode.TwoPane, flat.mode)
    }

    @Test
    fun foldMovesIntoBodyCoordinates() {
        val fold = GalleryFoldInfo(GalleryFoldOrientation.Horizontal, true, 0.dp, 500.dp, 900.dp, 520.dp)
        val local = fold.toLocal(0.dp, 64.dp, 900.dp, 900.dp)
        assertEquals(436.dp, local.top)
        assertEquals(456.dp, local.bottom)
    }
}
