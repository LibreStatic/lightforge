package com.ugallery.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Visual treatment shared by every selectable media grid. */
@Composable
fun MediaSelectionOverlay(selected: Boolean, modifier: Modifier = Modifier) {
    if (!selected) return
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.26f))
            .testTag("media_selection_indicator")
    ) {
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                imageVector = GalleryIcons.Check,
                contentDescription = null,
                modifier = Modifier.padding(5.dp),
            )
        }
    }
}

/**
 * gallery-style selection brush for lazy grids.
 *
 * A long press chooses select or deselect mode from the anchor item. Dragging paints every loaded
 * item crossed by the gesture, including items skipped between pointer samples. Holding near an
 * edge scrolls the grid and keeps extending the selection.
 */
fun <T> Modifier.lazyGridDragSelection(
    state: LazyGridState,
    itemAtIndex: (Int) -> T?,
    itemKey: (T) -> Any,
    isSelected: (T) -> Boolean,
    onSelectionChange: (T, Boolean) -> Unit,
    enabled: Boolean = true,
): Modifier = composed {
    val currentItemAtIndex by rememberUpdatedState(itemAtIndex)
    val currentItemKey by rememberUpdatedState(itemKey)
    val currentIsSelected by rememberUpdatedState(isSelected)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val edgeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val maximumScrollPerFramePx = with(LocalDensity.current) { 24.dp.toPx() }
    val hapticFeedback = LocalHapticFeedback.current

    pointerInput(state, enabled, edgeThresholdPx, maximumScrollPerFramePx) {
        if (!enabled) return@pointerInput
        coroutineScope {
            var lastIndex: Int? = null
            var desiredSelected = true
            var pointerPosition: Offset? = null
            var autoScrollJob: Job? = null
            val visited = mutableSetOf<Any>()

            fun indexAt(position: Offset): Int? =
                state.layoutInfo.visibleItemsInfo
                    .lastOrNull { item ->
                        position.x >= item.offset.x &&
                            position.x < item.offset.x + item.size.width &&
                            position.y >= item.offset.y &&
                            position.y < item.offset.y + item.size.height
                    }
                    ?.index

            fun paintIndex(index: Int) {
                val item = currentItemAtIndex(index) ?: return
                if (
                    visited.add(currentItemKey(item)) && currentIsSelected(item) != desiredSelected
                ) {
                    currentOnSelectionChange(item, desiredSelected)
                }
            }

            fun paintThrough(index: Int) {
                val previous = lastIndex
                if (previous == null) {
                    paintIndex(index)
                } else if (previous <= index) {
                    for (candidate in previous..index) paintIndex(candidate)
                } else {
                    for (candidate in previous downTo index) paintIndex(candidate)
                }
                lastIndex = index
            }

            fun edgeScrollDelta(position: Offset): Float =
                when {
                    position.y < edgeThresholdPx -> {
                        -maximumScrollPerFramePx *
                            ((edgeThresholdPx - position.y) / edgeThresholdPx).coerceIn(0f, 1f)
                    }
                    position.y > size.height - edgeThresholdPx -> {
                        maximumScrollPerFramePx *
                            ((position.y - (size.height - edgeThresholdPx)) / edgeThresholdPx)
                                .coerceIn(0f, 1f)
                    }
                    else -> 0f
                }

            fun restartAutoScroll(position: Offset) {
                val delta = edgeScrollDelta(position)
                if (delta == 0f) {
                    autoScrollJob?.cancel()
                    autoScrollJob = null
                    return
                }
                if (autoScrollJob?.isActive == true) return
                autoScrollJob = launch {
                    while (isActive) {
                        val activePosition = pointerPosition ?: break
                        val activeDelta = edgeScrollDelta(activePosition)
                        if (activeDelta == 0f) break
                        state.scrollBy(activeDelta)
                        indexAt(activePosition)?.let(::paintThrough)
                        kotlinx.coroutines.delay(16L)
                    }
                }
            }

            fun finishGesture() {
                autoScrollJob?.cancel()
                autoScrollJob = null
                pointerPosition = null
                lastIndex = null
                visited.clear()
            }

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val index = indexAt(held.position) ?: return@awaitEachGesture
                val anchor = currentItemAtIndex(index) ?: return@awaitEachGesture
                visited.clear()
                pointerPosition = held.position
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                desiredSelected = !currentIsSelected(anchor)
                paintThrough(index)
                restartAutoScroll(held.position)
                try {
                    while (true) {
                        // Intercept the release before a clickable cell handles it in Main.
                        // A stationary long press otherwise selects, then immediately toggles off.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == held.id } ?: break
                        if (change.isConsumed) break
                        if (change.pressed && change.position != change.previousPosition) {
                            pointerPosition = change.position
                            indexAt(change.position)?.let(::paintThrough)
                            restartAutoScroll(change.position)
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                } finally {
                    finishGesture()
                }
            }
        }
    }
}
