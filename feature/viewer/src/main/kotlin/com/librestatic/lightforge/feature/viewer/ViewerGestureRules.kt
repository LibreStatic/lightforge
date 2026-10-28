package com.librestatic.lightforge.feature.viewer

import android.view.KeyEvent
import com.librestatic.lightforge.core.preferences.GestureSettings
import com.librestatic.lightforge.core.preferences.VideoDoubleTapAction
import com.librestatic.lightforge.core.preferences.VideoHoldAction
import kotlin.math.abs
import kotlin.math.sign

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

/** Thirds of the viewer width: the zone a tap or hold lands in picks its configured action. */
internal enum class ViewerZone { Left, Center, Right }

internal fun viewerZone(x: Float, width: Float): ViewerZone = when {
    width <= 0f -> ViewerZone.Center
    x < width / 3f -> ViewerZone.Left
    x > width * 2f / 3f -> ViewerZone.Right
    else -> ViewerZone.Center
}

internal fun GestureSettings.videoHoldActionFor(zone: ViewerZone): VideoHoldAction = when (zone) {
    ViewerZone.Left -> videoHoldLeft
    ViewerZone.Center -> videoHoldCenter
    ViewerZone.Right -> videoHoldRight
}

internal fun GestureSettings.videoDoubleTapActionFor(zone: ViewerZone): VideoDoubleTapAction = when (zone) {
    ViewerZone.Left -> videoDoubleTapLeft
    ViewerZone.Center -> videoDoubleTapCenter
    ViewerZone.Right -> videoDoubleTapRight
}

/** Horizontal travel (dp) around the anchor that still holds the slow-motion frame. */
internal const val SHUTTLE_DEAD_ZONE_DP = 24f

/** Every further [SHUTTLE_STEP_DP] of travel moves the shuttle to the next faster rate. */
internal const val SHUTTLE_STEP_DP = 56f
private val ShuttleRates = floatArrayOf(0.125f, 0.25f, 0.5f)

/**
 * Signed slow-motion source rate for a finger [dxPx] from the shuttle anchor: 0 inside the dead
 * zone, then 0.125, 0.25 and 0.5 with distance; left (negative) plays in reverse.
 */
internal fun shuttleRate(dxPx: Float, density: Float): Float {
    if (density <= 0f) return 0f
    val distance = abs(dxPx) / density
    if (distance <= SHUTTLE_DEAD_ZONE_DP) return 0f
    val step = ((distance - SHUTTLE_DEAD_ZONE_DP) / SHUTTLE_STEP_DP).toInt().coerceIn(0, ShuttleRates.lastIndex)
    return sign(dxPx) * ShuttleRates[step]
}

/** Source time a full-width drag scrubs in slow-motion scrub mode. */
internal const val SCRUB_SPAN_MILLIS = 3_000L

/** Source position the scrub shows after the finger moved [dxPx] from the anchor ([anchorMillis]). */
internal fun scrubTargetMillis(anchorMillis: Long, dxPx: Float, widthPx: Float): Long {
    if (widthPx <= 0f) return anchorMillis.coerceAtLeast(0L)
    return (anchorMillis + (dxPx / widthPx * SCRUB_SPAN_MILLIS).toLong()).coerceAtLeast(0L)
}

/** Travel (dp) that moves a fast-forward or rewind hold one speed step. */
internal const val LIVE_SPEED_STEP_DP = 48f

/**
 * Whole speed steps for a finger [dxPx], [dyPx] from where the hold started. The longer axis wins:
 * up is faster, and so is sliding the way the video runs (right for [forward], left for rewind).
 */
internal fun liveSpeedSteps(dxPx: Float, dyPx: Float, forward: Boolean, density: Float): Int {
    if (density <= 0f) return 0
    val travel = if (abs(dyPx) >= abs(dxPx)) -dyPx else if (forward) dxPx else -dxPx
    return (travel / (LIVE_SPEED_STEP_DP * density)).toInt()
}

/** The speed [steps] along [speeds] from the entry nearest [base], clamped to both ends. */
internal fun liveSpeedAfter(
    base: Float,
    steps: Int,
    speeds: List<Float> = GestureSettings.VideoFastSpeeds,
): Float {
    if (speeds.isEmpty()) return base
    val baseIndex = speeds.indices.minBy { abs(speeds[it] - base) }
    return speeds[(baseIndex + steps).coerceIn(0, speeds.lastIndex)]
}

/** Where a rewind at [speed] times real time stands [elapsedMillis] after [targetMillis]. */
internal fun rewindTargetMillis(targetMillis: Long, speed: Float, elapsedMillis: Long): Long =
    (targetMillis - (speed * elapsedMillis.coerceAtLeast(0L)).toLong()).coerceAtLeast(0L)

/** A tap this soon after the previous seek tap in the same direction seeks again straight away. */
internal const val SEEK_ACCUMULATION_WINDOW_MILLIS = 700L

/** Seeks added up by consecutive taps: [direction] is -1 back or +1 forward. */
internal data class SeekAccumulation(val direction: Int, val totalSeconds: Int, val lastTapMillis: Long)

internal fun VideoDoubleTapAction.seekDirection(): Int = when (this) {
    VideoDoubleTapAction.SeekBack -> -1
    VideoDoubleTapAction.SeekForward -> 1
    else -> 0
}

internal fun continuesSeekAccumulation(accumulation: SeekAccumulation?, direction: Int, nowMillis: Long): Boolean =
    accumulation != null && direction != 0 && accumulation.direction == direction &&
        nowMillis - accumulation.lastTapMillis in 0..SEEK_ACCUMULATION_WINDOW_MILLIS

/** The accumulation after one more seek of [stepSeconds]; a fresh one when the window has passed. */
internal fun accumulateSeek(
    accumulation: SeekAccumulation?,
    direction: Int,
    nowMillis: Long,
    stepSeconds: Int,
): SeekAccumulation =
    if (accumulation != null && continuesSeekAccumulation(accumulation, direction, nowMillis)) {
        accumulation.copy(totalSeconds = accumulation.totalSeconds + stepSeconds, lastTapMillis = nowMillis)
    } else {
        SeekAccumulation(direction, stepSeconds, nowMillis)
    }

/** How a finished single tap is handled. */
internal enum class ViewerTapResolution {
    /** Part of a running seek accumulation: seek again, no chrome toggle, no double-tap wait. */
    RepeatSeek,

    /** Nothing is bound to a double tap here, so the tap needs no double-tap wait. */
    Immediate,

    /** Wait the double-tap timeout for a second tap before treating it as a single tap. */
    AwaitDoubleTap,
}

internal fun tapResolution(
    doubleTap: VideoDoubleTapAction,
    accumulation: SeekAccumulation?,
    nowMillis: Long,
): ViewerTapResolution = when {
    continuesSeekAccumulation(accumulation, doubleTap.seekDirection(), nowMillis) -> ViewerTapResolution.RepeatSeek
    doubleTap == VideoDoubleTapAction.None -> ViewerTapResolution.Immediate
    else -> ViewerTapResolution.AwaitDoubleTap
}

/** Both fingers must lift within this long of the first touching down. */
internal const val TWO_FINGER_TAP_MAX_MILLIS = 300L

/** A two-finger tap: exactly two fingers, quick, and none moved past [slopPx]. */
internal fun isTwoFingerTap(maxPointers: Int, durationMillis: Long, maxMovementPx: Float, slopPx: Float): Boolean =
    maxPointers == 2 && durationMillis in 0..TWO_FINGER_TAP_MAX_MILLIS && maxMovementPx <= slopPx
