package com.librestatic.lightforge.feature.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import kotlin.math.max

/** What the viewer does for the touches [detectViewerGestures] recognises; no player calls live in the detector. */
internal interface ViewerGestureListener {
    val twoFingerTapEnabled: Boolean

    /** A finger went down in [zone]. */
    fun onPress(zone: ViewerZone) = Unit

    /** The finger stayed put past the long-press timeout; true starts a hold that owns the touch. */
    fun onHoldStart(zone: ViewerZone, position: Offset): Boolean = false

    /** Finger travel since it went down, while a hold is active. */
    fun onHoldMove(delta: Offset) = Unit

    /** The hold finished, was cancelled, or its pointer input was torn down. */
    fun onHoldEnd() = Unit

    fun resolveTap(zone: ViewerZone, uptimeMillis: Long): ViewerTapResolution
    fun onTap(position: Offset, zone: ViewerZone)
    fun onRepeatTap(zone: ViewerZone, uptimeMillis: Long)
    fun onDoubleTap(position: Offset, zone: ViewerZone, uptimeMillis: Long)
    fun onTwoFingerTap()
}

private sealed interface Press {
    data class Tap(val position: Offset, val uptimeMillis: Long) : Press
    data object Hold : Press
    data object TwoFingerTap : Press

    /** Cancelled, dragged, pinched or consumed elsewhere; nothing for us to do. */
    data object Ended : Press
}

private const val DOUBLE_TAP_MIN_MILLIS = 40L

/**
 * Taps, double taps, holds and two-finger taps in one gesture so they cannot fight each other.
 *
 * Before a hold starts the detector only observes: it reads events in the Initial pass (it sees
 * every pointer before the pager or the pinch handler do) and checks in the Final pass whether
 * anyone consumed them, which cancels the tap just like the stock tap detector. Once a hold
 * starts, every change is consumed in the Initial pass, so the pager, the pinch handler and the
 * brightness/volume/swipe drag (all Main pass, and all of which cancel on consumed changes) never
 * see the finger move, and no tap fires on release.
 */
internal suspend fun PointerInputScope.detectViewerGestures(listener: ViewerGestureListener) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val zone = viewerZone(down.position.x, size.width.toFloat())
        listener.onPress(zone)
        when (val press = awaitPress(down, listener.twoFingerTapEnabled)) {
            Press.Ended -> Unit
            Press.TwoFingerTap -> listener.onTwoFingerTap()
            Press.Hold -> {
                if (!listener.onHoldStart(zone, down.position)) return@awaitEachGesture
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        event.changes.firstOrNull { it.id == down.id && it.pressed }
                            ?.let { listener.onHoldMove(it.position - down.position) }
                        if (event.changes.none { it.pressed }) break
                    }
                } finally {
                    listener.onHoldEnd()
                }
            }
            is Press.Tap -> handleTap(listener, zone, press)
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitPress(down: PointerInputChange, twoFingerEnabled: Boolean): Press {
    val slop = viewConfiguration.touchSlop
    val starts = mutableMapOf(down.id to down.position)
    var maxPointers = 1
    var maxMovement = 0f
    var result: Press? = null
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (change.changedToDown()) starts.getOrPut(change.id) { change.position }
                val start = starts[change.id] ?: continue
                maxMovement = max(maxMovement, (change.position - start).getDistance())
            }
            maxPointers = max(maxPointers, event.changes.count { it.pressed })
            val allUp = event.changes.none { it.pressed }
            if (maxPointers >= 2) {
                // Pinch handlers consume two-finger touches outright, so only movement counts here.
                if (!twoFingerEnabled || maxMovement > slop) {
                    result = Press.Ended
                    return@withTimeoutOrNull
                }
                if (allUp) {
                    val duration = event.changes.maxOf { it.uptimeMillis } - down.uptimeMillis
                    result = if (isTwoFingerTap(maxPointers, duration, maxMovement, slop)) Press.TwoFingerTap else Press.Ended
                    return@withTimeoutOrNull
                }
                continue
            }
            if (maxMovement > slop) {
                result = Press.Ended
                return@withTimeoutOrNull
            }
            // Same consumed check as the stock tap detector: the Final pass comes after every Main pass.
            val consumeCheck = awaitPointerEvent(PointerEventPass.Final)
            if (consumeCheck.changes.any { it.isConsumed }) {
                result = Press.Ended
                return@withTimeoutOrNull
            }
            if (allUp) {
                val up = event.changes.first()
                result = Press.Tap(up.position, up.uptimeMillis)
                return@withTimeoutOrNull
            }
        }
    }
    // Timed out with everything still down: a hold, unless a second finger joined.
    return result ?: if (maxPointers >= 2) Press.Ended else Press.Hold
}

private suspend fun AwaitPointerEventScope.handleTap(
    listener: ViewerGestureListener,
    zone: ViewerZone,
    tap: Press.Tap,
) {
    when (listener.resolveTap(zone, tap.uptimeMillis)) {
        ViewerTapResolution.RepeatSeek -> listener.onRepeatTap(zone, tap.uptimeMillis)
        ViewerTapResolution.Immediate -> listener.onTap(tap.position, zone)
        ViewerTapResolution.AwaitDoubleTap -> {
            val minimumUptime = tap.uptimeMillis + DOUBLE_TAP_MIN_MILLIS
            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                var change: PointerInputChange
                do {
                    change = awaitFirstDown()
                } while (change.uptimeMillis < minimumUptime)
                change
            }
            if (secondDown == null) {
                listener.onTap(tap.position, zone)
                return
            }
            // A long second press is not a double tap.
            val secondUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
            if (secondUp != null) {
                secondUp.consume()
                listener.onDoubleTap(secondUp.position, zone, secondUp.uptimeMillis)
            } else {
                listener.onTap(tap.position, zone)
            }
        }
    }
}
