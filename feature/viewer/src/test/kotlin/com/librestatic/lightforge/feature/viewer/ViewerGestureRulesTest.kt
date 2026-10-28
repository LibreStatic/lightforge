package com.librestatic.lightforge.feature.viewer

import android.view.KeyEvent
import com.librestatic.lightforge.core.preferences.GestureSettings
import com.librestatic.lightforge.core.preferences.VideoDoubleTapAction
import com.librestatic.lightforge.core.preferences.VideoHoldAction
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

    @Test fun `zones are the thirds of the width`() {
        assertEquals(ViewerZone.Left, viewerZone(0f, width))
        assertEquals(ViewerZone.Left, viewerZone(299f, width))
        assertEquals(ViewerZone.Center, viewerZone(300f, width))
        assertEquals(ViewerZone.Center, viewerZone(600f, width))
        assertEquals(ViewerZone.Right, viewerZone(601f, width))
        assertEquals(ViewerZone.Center, viewerZone(10f, 0f))
    }

    @Test fun `each zone looks up its own hold and double tap action`() {
        val settings = GestureSettings(
            videoHoldLeft = VideoHoldAction.None,
            videoHoldCenter = VideoHoldAction.FastForward,
            videoHoldRight = VideoHoldAction.SlowMotion,
            videoDoubleTapLeft = VideoDoubleTapAction.PlayPause,
            videoDoubleTapCenter = VideoDoubleTapAction.None,
            videoDoubleTapRight = VideoDoubleTapAction.SeekBack,
        )
        assertEquals(VideoHoldAction.None, settings.videoHoldActionFor(ViewerZone.Left))
        assertEquals(VideoHoldAction.FastForward, settings.videoHoldActionFor(ViewerZone.Center))
        assertEquals(VideoHoldAction.SlowMotion, settings.videoHoldActionFor(ViewerZone.Right))
        assertEquals(VideoDoubleTapAction.PlayPause, settings.videoDoubleTapActionFor(ViewerZone.Left))
        assertEquals(VideoDoubleTapAction.None, settings.videoDoubleTapActionFor(ViewerZone.Center))
        assertEquals(VideoDoubleTapAction.SeekBack, settings.videoDoubleTapActionFor(ViewerZone.Right))
    }

    @Test fun `shuttle holds the frame inside the dead zone then speeds up in steps`() {
        val density = 2f
        assertEquals(0f, shuttleRate(0f, density), 0f)
        assertEquals(0f, shuttleRate(48f, density), 0f) // exactly 24 dp
        assertEquals(0.125f, shuttleRate(49f, density), 0f)
        assertEquals(0.125f, shuttleRate((24f + 55f) * density, density), 0f)
        assertEquals(0.25f, shuttleRate((24f + 56f) * density, density), 0f)
        assertEquals(0.5f, shuttleRate((24f + 112f) * density, density), 0f)
        assertEquals(0.5f, shuttleRate(5_000f, density), 0f)
    }

    @Test fun `shuttle sign follows the drag direction`() {
        assertEquals(-0.125f, shuttleRate(-60f, 1f), 0f)
        assertEquals(-0.5f, shuttleRate(-500f, 1f), 0f)
        assertEquals(0.5f, shuttleRate(500f, 1f), 0f)
        assertEquals(0f, shuttleRate(-10f, 1f), 0f)
    }

    @Test fun `scrub spans three seconds of source across the width and never goes negative`() {
        assertEquals(5_000L, scrubTargetMillis(5_000L, 0f, 900f))
        assertEquals(6_500L, scrubTargetMillis(5_000L, 450f, 900f))
        assertEquals(2_000L, scrubTargetMillis(5_000L, -900f, 900f))
        assertEquals(0L, scrubTargetMillis(1_000L, -900f, 900f))
        assertEquals(1_000L, scrubTargetMillis(1_000L, 300f, 0f))
    }

    @Test fun `live speed steps every 48 dp upwards and truncates toward zero`() {
        assertEquals(0, liveSpeedSteps(0f, 0f, forward = true, density = 2f))
        assertEquals(0, liveSpeedSteps(0f, -95f, forward = true, density = 2f))
        assertEquals(1, liveSpeedSteps(0f, -96f, forward = true, density = 2f))
        assertEquals(2, liveSpeedSteps(0f, -200f, forward = false, density = 2f))
        assertEquals(0, liveSpeedSteps(0f, 95f, forward = true, density = 2f))
        assertEquals(-1, liveSpeedSteps(0f, 96f, forward = true, density = 2f))
    }

    @Test fun `live speed also follows a horizontal slide in the playback direction`() {
        assertEquals(2, liveSpeedSteps(200f, 0f, forward = true, density = 2f))
        assertEquals(-2, liveSpeedSteps(-200f, 0f, forward = true, density = 2f))
        assertEquals(2, liveSpeedSteps(-200f, 0f, forward = false, density = 2f))
        assertEquals(-1, liveSpeedSteps(100f, 0f, forward = false, density = 2f))
        // The longer axis wins.
        assertEquals(1, liveSpeedSteps(-100f, -150f, forward = true, density = 2f))
        assertEquals(-2, liveSpeedSteps(-200f, -150f, forward = true, density = 2f))
        assertEquals(0, liveSpeedSteps(200f, 0f, forward = true, density = 0f))
    }

    @Test fun `live speed walks the speed list from the configured speed and clamps`() {
        assertEquals(2f, liveSpeedAfter(2f, 0), 0f)
        assertEquals(3f, liveSpeedAfter(2f, 1), 0f)
        assertEquals(4f, liveSpeedAfter(2f, 2), 0f)
        assertEquals(4f, liveSpeedAfter(2f, 9), 0f)
        assertEquals(1.5f, liveSpeedAfter(2f, -1), 0f)
        assertEquals(1.5f, liveSpeedAfter(2f, -9), 0f)
        assertEquals(3f, liveSpeedAfter(2.9f, 0), 0f)
    }

    @Test fun `rewind moves back by speed times elapsed and stops at the start`() {
        assertEquals(8_000L, rewindTargetMillis(10_000L, 2f, 1_000L))
        assertEquals(9_700L, rewindTargetMillis(10_000L, 3f, 100L))
        assertEquals(0L, rewindTargetMillis(500L, 4f, 1_000L))
        assertEquals(10_000L, rewindTargetMillis(10_000L, 2f, -50L))
    }

    @Test fun `seek accumulation continues in the same direction within the window`() {
        val first = accumulateSeek(null, 1, 1_000L, 10)
        assertEquals(SeekAccumulation(1, 10, 1_000L), first)
        assertTrue(continuesSeekAccumulation(first, 1, 1_700L))
        assertFalse(continuesSeekAccumulation(first, 1, 1_701L))
        assertFalse(continuesSeekAccumulation(first, -1, 1_100L))
        assertFalse(continuesSeekAccumulation(first, 0, 1_100L))
        assertFalse(continuesSeekAccumulation(null, 1, 1_100L))
        val second = accumulateSeek(first, 1, 1_600L, 10)
        assertEquals(SeekAccumulation(1, 20, 1_600L), second)
        // The window extends from the latest tap.
        assertEquals(30, accumulateSeek(second, 1, 2_300L, 10).totalSeconds)
    }

    @Test fun `seek accumulation restarts after the window or a direction change`() {
        val running = SeekAccumulation(1, 30, 1_000L)
        assertEquals(SeekAccumulation(1, 10, 2_000L), accumulateSeek(running, 1, 2_000L, 10))
        assertEquals(SeekAccumulation(-1, 10, 1_200L), accumulateSeek(running, -1, 1_200L, 10))
    }

    @Test fun `seek direction comes from the seek actions only`() {
        assertEquals(-1, VideoDoubleTapAction.SeekBack.seekDirection())
        assertEquals(1, VideoDoubleTapAction.SeekForward.seekDirection())
        assertEquals(0, VideoDoubleTapAction.Zoom.seekDirection())
        assertEquals(0, VideoDoubleTapAction.PlayPause.seekDirection())
        assertEquals(0, VideoDoubleTapAction.None.seekDirection())
    }

    @Test fun `a tap resolves at once without a double tap action and repeats inside a seek run`() {
        val running = SeekAccumulation(1, 10, 1_000L)
        assertEquals(ViewerTapResolution.Immediate, tapResolution(VideoDoubleTapAction.None, null, 5_000L))
        assertEquals(ViewerTapResolution.AwaitDoubleTap, tapResolution(VideoDoubleTapAction.Zoom, null, 5_000L))
        assertEquals(ViewerTapResolution.AwaitDoubleTap, tapResolution(VideoDoubleTapAction.PlayPause, running, 1_100L))
        assertEquals(ViewerTapResolution.AwaitDoubleTap, tapResolution(VideoDoubleTapAction.SeekForward, null, 1_100L))
        assertEquals(ViewerTapResolution.RepeatSeek, tapResolution(VideoDoubleTapAction.SeekForward, running, 1_500L))
        // Opposite zone, or after the window, is a normal tap again.
        assertEquals(ViewerTapResolution.AwaitDoubleTap, tapResolution(VideoDoubleTapAction.SeekBack, running, 1_500L))
        assertEquals(ViewerTapResolution.AwaitDoubleTap, tapResolution(VideoDoubleTapAction.SeekForward, running, 1_701L))
    }

    @Test fun `two-finger tap needs two quick fingers that stayed put`() {
        assertTrue(isTwoFingerTap(2, 120L, 3f, 8f))
        assertTrue(isTwoFingerTap(2, 300L, 8f, 8f))
        assertFalse(isTwoFingerTap(2, 301L, 3f, 8f))
        assertFalse(isTwoFingerTap(2, 120L, 9f, 8f))
        assertFalse(isTwoFingerTap(1, 120L, 0f, 8f))
        assertFalse(isTwoFingerTap(3, 120L, 0f, 8f))
        assertFalse(isTwoFingerTap(2, -1L, 0f, 8f))
    }
}
