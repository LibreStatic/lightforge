package com.librestatic.lightforge.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Horizontal swipe that drives a start-edge side panel. [progress] runs from 0 (hidden) to 1
 * (shown) and follows the finger; release settles by fling velocity or the halfway point.
 * Swiping toward the end edge opens the panel, mirrored in RTL.
 */
fun Modifier.sidePanelSwipe(
    progress: Animatable<Float, *>,
    panelWidthPx: Float,
    scope: CoroutineScope,
    layoutDirection: LayoutDirection,
    enabled: Boolean = true,
    onSettled: (open: Boolean) -> Unit,
): Modifier = if (!enabled || panelWidthPx <= 0f) this else pointerInput(panelWidthPx, layoutDirection) {
    val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val tracker = VelocityTracker()
    var distance = 0f
    var dragging = false
    fun settle(velocity: Float) {
        val flingThreshold = 400.dp.toPx()
        val open = when {
            velocity * direction > flingThreshold -> true
            velocity * direction < -flingThreshold -> false
            else -> progress.value >= 0.5f
        }
        scope.launch { progress.animateTo(if (open) 1f else 0f, spring()) }
        onSettled(open)
    }
    detectHorizontalDragGestures(
        onDragStart = { distance = 0f; dragging = false; tracker.resetTracking() },
        onHorizontalDrag = { change, amount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            distance += amount
            if (!dragging && abs(distance) > 24.dp.toPx()) dragging = true
            if (dragging) {
                change.consume()
                val target = (progress.value + amount * direction / panelWidthPx).coerceIn(0f, 1f)
                scope.launch { progress.snapTo(target) }
            }
        },
        onDragEnd = { if (dragging) settle(tracker.calculateVelocity().x) },
        onDragCancel = { if (dragging) settle(0f) },
    )
}
