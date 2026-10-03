package com.librestatic.lightforge.feature.viewer

import android.view.KeyEvent
import kotlin.math.abs

/** What a vertical drag on the media does once its direction is known. */
internal enum class ViewerDragMode {
    /** Not ours: an edge drag (brightness/volume), a zoomed or selecting photo, or disabled. */
    None,

    /** Drag the Details sheet: opening it from the photo, or closing an open one. */
    Details,

    /** Swipe down to close the viewer. */
    CloseViewer,
}

/** The outcome of a finished vertical drag. */
internal enum class ViewerSwipeResult { None, OpenDetails, CloseDetails, CloseViewer }

/** Rest positions of the compact Details sheet. */
enum class DetailsSheetValue { Hidden, Half, Full }

/** Fraction of the viewer height a swipe must travel to close the viewer or open/close Details. */
internal const val VIEWER_SWIPE_DISTANCE_FRACTION = 0.16f

/** A release faster than this (px/s) counts as a fling in its direction. */
internal const val VIEWER_SWIPE_FLING_VELOCITY = 1_400f

/**
 * Whether a vertical drag that started at ([startX], [startY]) may become a viewer swipe: it must
 * start in the middle third, above the bottom chrome ([bottomExcludedPx], covering the scrubber
 * and thumbnail strip), and the photo must be neither zoomed nor in text selection.
 */
internal fun isViewerSwipeStart(
    startX: Float,
    startY: Float,
    width: Float,
    height: Float,
    bottomExcludedPx: Float,
    zoomed: Boolean,
    textSelecting: Boolean,
): Boolean {
    if (width <= 0f || height <= 0f || zoomed || textSelecting) return false
    val center = startX >= width / 3f && startX <= width * 2f / 3f
    return center && startY < height - bottomExcludedPx.coerceAtLeast(0f)
}

/**
 * Picks the drag mode from the first movement ([firstDeltaY] < 0 is upward). An open Details
 * sheet always takes the drag, so swiping down closes Details before it can close the viewer.
 */
internal fun viewerDragMode(
    eligible: Boolean,
    firstDeltaY: Float,
    detailsOpen: Boolean,
    swipeUpForDetails: Boolean,
    swipeDownToClose: Boolean,
): ViewerDragMode = when {
    !eligible || firstDeltaY == 0f -> ViewerDragMode.None
    detailsOpen -> ViewerDragMode.Details
    firstDeltaY < 0f && swipeUpForDetails -> ViewerDragMode.Details
    firstDeltaY > 0f && swipeDownToClose -> ViewerDragMode.CloseViewer
    else -> ViewerDragMode.None
}

/**
 * The result of a drag in [mode] that moved [totalY] px (negative is up) and was released at
 * [velocityY] px/s (negative is up) on a viewer [height] px tall. Used where the sheet does not
 * follow the finger (side panel) and for closing the viewer.
 */
internal fun viewerSwipeResult(
    mode: ViewerDragMode,
    totalY: Float,
    velocityY: Float,
    height: Float,
    detailsOpen: Boolean,
): ViewerSwipeResult {
    val threshold = height * VIEWER_SWIPE_DISTANCE_FRACTION
    val up = -totalY > threshold || (velocityY < -VIEWER_SWIPE_FLING_VELOCITY && totalY < 0f)
    val down = totalY > threshold || (velocityY > VIEWER_SWIPE_FLING_VELOCITY && totalY > 0f)
    return when (mode) {
        ViewerDragMode.None -> ViewerSwipeResult.None
        ViewerDragMode.CloseViewer -> if (totalY > threshold) ViewerSwipeResult.CloseViewer else ViewerSwipeResult.None
        ViewerDragMode.Details -> when {
            detailsOpen && down -> ViewerSwipeResult.CloseDetails
            !detailsOpen && up -> ViewerSwipeResult.OpenDetails
            else -> ViewerSwipeResult.None
        }
    }
}

/**
 * Where the compact sheet settles after a drag: a fling moves one stop in its direction,
 * otherwise the nearest stop wins. [visiblePx] is how much of the sheet shows; [velocityUp] is
 * positive when the finger moves up.
 */
fun settleDetailsSheet(
    visiblePx: Float,
    velocityUp: Float,
    halfPx: Float,
    fullPx: Float,
    flingVelocity: Float = VIEWER_SWIPE_FLING_VELOCITY,
): DetailsSheetValue {
    if (halfPx <= 0f || fullPx <= 0f) return DetailsSheetValue.Hidden
    val stops = listOf(DetailsSheetValue.Hidden to 0f, DetailsSheetValue.Half to halfPx, DetailsSheetValue.Full to fullPx)
    if (abs(velocityUp) >= flingVelocity) {
        return if (velocityUp > 0f) {
            stops.firstOrNull { it.second > visiblePx + 1f }?.first ?: DetailsSheetValue.Full
        } else {
            stops.lastOrNull { it.second < visiblePx - 1f }?.first ?: DetailsSheetValue.Hidden
        }
    }
    return stops.minBy { abs(it.second - visiblePx) }.first
}

/** Keyboard shortcuts of the viewer. */
internal enum class ViewerShortcut { Previous, Next, Close, Details, PlayPause, ZoomIn, ZoomOut, Trash, Favorite }

/**
 * Maps a key press to a viewer shortcut. Modified presses (Ctrl, Alt, Meta) are left to the
 * system and the app, so Ctrl+F or Alt+← keep their usual meaning.
 */
internal fun viewerShortcutFor(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false): ViewerShortcut? {
    if (ctrl || alt || meta) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> ViewerShortcut.Previous
        KeyEvent.KEYCODE_DPAD_RIGHT -> ViewerShortcut.Next
        KeyEvent.KEYCODE_ESCAPE -> ViewerShortcut.Close
        KeyEvent.KEYCODE_I -> ViewerShortcut.Details
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ViewerShortcut.PlayPause
        // "+" is Shift+= on most layouts, so a bare "=" zooms in too.
        KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_ZOOM_IN -> ViewerShortcut.ZoomIn
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_ZOOM_OUT -> ViewerShortcut.ZoomOut
        // Backspace too: it is the "delete" key on many laptop keyboards.
        KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> ViewerShortcut.Trash
        KeyEvent.KEYCODE_F -> ViewerShortcut.Favorite
        else -> null
    }
}

/**
 * The slice of [size] items the thumbnail strip composes: [radius] items on each side of
 * [selectedIndex], shifted inwards at either end so the strip keeps a constant length.
 */
internal fun stripWindow(size: Int, selectedIndex: Int, radius: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val span = (radius * 2 + 1).coerceAtMost(size)
    val selected = selectedIndex.coerceIn(0, size - 1)
    val start = (selected - radius).coerceIn(0, size - span)
    return start until start + span
}
