package com.ugallery.feature.photos

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.collectLatest

internal fun adaptiveDensityColumns(widthDp: Int): IntArray = when {
    widthDp < 600 -> intArrayOf(3, 4, 5, 7)
    widthDp < 840 -> intArrayOf(5, 6, 7, 9)
    else -> intArrayOf(7, 9, 11, 13)
}

@Stable
class TimelineDensityState internal constructor(
    densityIndex: Int,
    anchorIndex: Int,
    anchorOffset: Int,
) {
    var densityIndex by mutableIntStateOf(densityIndex)
        private set
    var anchorIndex by mutableIntStateOf(anchorIndex)
        private set
    var anchorOffset by mutableIntStateOf(anchorOffset)
        private set

    fun columns(widthDp: Int): Int {
        val options = adaptiveDensityColumns(widthDp)
        return options[densityIndex.coerceIn(options.indices)]
    }

    fun changeDensity(delta: Int, anchorIndex: Int, anchorOffset: Int = 0): Boolean {
        val next = (densityIndex + delta).coerceIn(0, 3)
        if (next == densityIndex) return false
        this.anchorIndex = anchorIndex.coerceAtLeast(0)
        this.anchorOffset = anchorOffset.coerceAtLeast(0)
        densityIndex = next
        return true
    }

    internal fun observeAnchor(index: Int, offset: Int) {
        anchorIndex = index.coerceAtLeast(0)
        anchorOffset = offset.coerceAtLeast(0)
    }

    companion object {
        val Saver = Saver<TimelineDensityState, List<Int>>(
            save = { listOf(it.densityIndex, it.anchorIndex, it.anchorOffset) },
            restore = { TimelineDensityState(it[0], it[1], it[2]) },
        )
    }
}

@Composable
fun rememberTimelineDensityState(): TimelineDensityState = rememberSaveable(saver = TimelineDensityState.Saver) {
    TimelineDensityState(densityIndex = 0, anchorIndex = 0, anchorOffset = 0)
}

@Composable
internal fun PreserveTimelineAnchor(
    densityState: TimelineDensityState,
    gridState: LazyGridState,
    columns: Int,
    itemCount: Int,
) {
    val hasItems = itemCount > 0
    val restoreIndex = densityState.anchorIndex
    val restoreOffset = densityState.anchorOffset
    LaunchedEffect(columns, hasItems) {
        if (hasItems) {
            gridState.scrollToItem(
                restoreIndex.coerceIn(0, itemCount - 1),
                restoreOffset,
            )
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) -> densityState.observeAnchor(index, offset) }
    }
}

internal fun Modifier.timelinePinchDensity(
    densityState: TimelineDensityState,
    gridState: LazyGridState,
): Modifier = pointerInput(densityState.densityIndex) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var changed = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                val anchor = gridState.mediaIndexAt(centroid) ?: gridState.firstVisibleItemIndex
                event.changes.forEach { it.consume() }
                if (!changed && zoom > 1.22f) {
                    changed = densityState.changeDensity(-1, anchor)
                } else if (!changed && zoom < 0.82f) {
                    changed = densityState.changeDensity(1, anchor)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun LazyGridState.mediaIndexAt(point: Offset): Int? = layoutInfo.visibleItemsInfo.firstOrNull { item ->
    point.x >= item.offset.x && point.x < item.offset.x + item.size.width &&
        point.y >= item.offset.y && point.y < item.offset.y + item.size.height
}?.index
