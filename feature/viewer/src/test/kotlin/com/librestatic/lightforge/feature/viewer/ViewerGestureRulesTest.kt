package com.librestatic.lightforge.feature.viewer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerGestureRulesTest {
    private val width = 900f
    private val height = 2_000f

    private fun start(x: Float, y: Float = 1_000f, excluded: Float = 300f, zoomed: Boolean = false, selecting: Boolean = false) =
        isViewerSwipeStart(x, y, width, height, excluded, zoomed, selecting)

    @Test fun `only the middle third above the bottom chrome starts a swipe`() {
        assertTrue(start(450f))
        assertTrue(start(300f))
        assertFalse(start(200f))
        assertFalse(start(800f))
        assertFalse(start(450f, y = 1_750f))
        assertTrue(start(450f, y = 1_750f, excluded = 0f))
    }

    @Test fun `zoom and text selection block swipes`() {
        assertFalse(start(450f, zoomed = true))
        assertFalse(start(450f, selecting = true))
    }

    @Test fun `first movement picks the mode`() {
        assertEquals(ViewerDragMode.Details, viewerDragMode(true, -4f, detailsOpen = false, swipeUpForDetails = true, swipeDownToClose = true))
        assertEquals(ViewerDragMode.CloseViewer, viewerDragMode(true, 4f, detailsOpen = false, swipeUpForDetails = true, swipeDownToClose = true))
        assertEquals(ViewerDragMode.None, viewerDragMode(false, -4f, detailsOpen = false, swipeUpForDetails = true, swipeDownToClose = true))
    }

    @Test fun `settings switch each direction off`() {
        assertEquals(ViewerDragMode.None, viewerDragMode(true, -4f, detailsOpen = false, swipeUpForDetails = false, swipeDownToClose = true))
        assertEquals(ViewerDragMode.None, viewerDragMode(true, 4f, detailsOpen = false, swipeUpForDetails = true, swipeDownToClose = false))
    }

    @Test fun `open details take the swipe down before the viewer does`() {
        assertEquals(ViewerDragMode.Details, viewerDragMode(true, 4f, detailsOpen = true, swipeUpForDetails = true, swipeDownToClose = true))
        // Even with the swipe-up setting off, an open sheet can still be swiped away.
        assertEquals(ViewerDragMode.Details, viewerDragMode(true, 4f, detailsOpen = true, swipeUpForDetails = false, swipeDownToClose = false))
    }

    @Test fun `swipe results need distance or a fling`() {
        val threshold = height * VIEWER_SWIPE_DISTANCE_FRACTION
        assertEquals(ViewerSwipeResult.OpenDetails, viewerSwipeResult(ViewerDragMode.Details, -threshold - 1f, 0f, height, detailsOpen = false))
        assertEquals(ViewerSwipeResult.None, viewerSwipeResult(ViewerDragMode.Details, -threshold + 1f, 0f, height, detailsOpen = false))
        assertEquals(ViewerSwipeResult.OpenDetails, viewerSwipeResult(ViewerDragMode.Details, -40f, -3_000f, height, detailsOpen = false))
        assertEquals(ViewerSwipeResult.CloseDetails, viewerSwipeResult(ViewerDragMode.Details, threshold + 1f, 0f, height, detailsOpen = true))
        assertEquals(ViewerSwipeResult.None, viewerSwipeResult(ViewerDragMode.Details, -threshold - 1f, 0f, height, detailsOpen = true))
        assertEquals(ViewerSwipeResult.CloseViewer, viewerSwipeResult(ViewerDragMode.CloseViewer, threshold + 1f, 0f, height, detailsOpen = false))
        assertEquals(ViewerSwipeResult.None, viewerSwipeResult(ViewerDragMode.CloseViewer, 40f, 3_000f, height, detailsOpen = false))
        assertEquals(ViewerSwipeResult.None, viewerSwipeResult(ViewerDragMode.None, -1_000f, -9_000f, height, detailsOpen = false))
    }

    @Test fun `sheet settles at the nearest stop`() {
        assertEquals(DetailsSheetValue.Hidden, settleDetailsSheet(200f, 0f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Half, settleDetailsSheet(700f, 0f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Half, settleDetailsSheet(1_400f, 0f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Full, settleDetailsSheet(1_600f, 0f, halfPx = 1_000f, fullPx = 2_000f))
    }

    @Test fun `a fling moves one stop in its direction`() {
        assertEquals(DetailsSheetValue.Half, settleDetailsSheet(200f, 2_000f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Full, settleDetailsSheet(1_000f, 2_000f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Hidden, settleDetailsSheet(900f, -2_000f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Half, settleDetailsSheet(1_900f, -2_000f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Hidden, settleDetailsSheet(1_000f, -2_000f, halfPx = 1_000f, fullPx = 2_000f))
        assertEquals(DetailsSheetValue.Hidden, settleDetailsSheet(500f, 0f, halfPx = 0f, fullPx = 0f))
    }

    @Test fun `keyboard shortcuts map plain keys only`() {
        assertEquals(ViewerShortcut.Previous, viewerShortcutFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ViewerShortcut.Next, viewerShortcutFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ViewerShortcut.Close, viewerShortcutFor(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(ViewerShortcut.Details, viewerShortcutFor(KeyEvent.KEYCODE_I))
        assertEquals(ViewerShortcut.PlayPause, viewerShortcutFor(KeyEvent.KEYCODE_SPACE))
        assertEquals(ViewerShortcut.ZoomIn, viewerShortcutFor(KeyEvent.KEYCODE_EQUALS))
        assertEquals(ViewerShortcut.ZoomIn, viewerShortcutFor(KeyEvent.KEYCODE_PLUS))
        assertEquals(ViewerShortcut.ZoomOut, viewerShortcutFor(KeyEvent.KEYCODE_MINUS))
        assertEquals(ViewerShortcut.Trash, viewerShortcutFor(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(ViewerShortcut.Favorite, viewerShortcutFor(KeyEvent.KEYCODE_F))
        assertNull(viewerShortcutFor(KeyEvent.KEYCODE_F, ctrl = true))
        assertNull(viewerShortcutFor(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
        assertNull(viewerShortcutFor(KeyEvent.KEYCODE_A))
    }

    @Test fun `strip window stays centred and constant`() {
        assertEquals(0 until 0, stripWindow(0, 0, 10))
        assertEquals(0 until 5, stripWindow(5, 2, 10))
        assertEquals(40 until 61, stripWindow(1_000, 50, 10))
        assertEquals(0 until 21, stripWindow(1_000, 3, 10))
        assertEquals(979 until 1_000, stripWindow(1_000, 998, 10))
        assertEquals(979 until 1_000, stripWindow(1_000, 5_000, 10))
    }
}
