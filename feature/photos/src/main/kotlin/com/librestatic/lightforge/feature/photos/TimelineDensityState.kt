package com.librestatic.lightforge.feature.photos

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryGridMetrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun adaptiveDensityColumns(widthDp: Int): IntArray = when {
    widthDp < 600 -> intArrayOf(2, 3, 4, 5, 7)
    widthDp < 840 -> intArrayOf(3, 5, 6, 7, 9)
    else -> intArrayOf(5, 7, 9, 11, 13)
}

@Stable
class TimelineDensityState internal constructor(
    densityIndex: Int,
    anchorIndex: Int,
    anchorOffset: Int,
) {
    private var anchorRestorePending = false
    private var anchorStableKey: String? = null
    private var lastColumns = -1
    private var seededFromPreferredColumns = false
    var densityIndex by mutableIntStateOf(densityIndex)
        private set
    var anchorIndex by mutableIntStateOf(anchorIndex)
        private set
    var anchorOffset by mutableIntStateOf(anchorOffset)
        private set

    /**
     * Viewport y (px) the restored anchor's top should land on, set by a pinch so the tile
     * under the fingers stays under them; null restores to [anchorOffset] at the top.
     */
    private var anchorTopPx: Float? = null

    /** Live visual scale of the grid while pinching; springs back to 1 on release. */
    var pinchScale by mutableFloatStateOf(1f)
        internal set

    /** Pinch centroid in grid coordinates, the pivot of [pinchScale]. */
    var pinchPivot by mutableStateOf(Offset.Zero)
        internal set

    fun columns(widthDp: Int): Int {
        val options = adaptiveDensityColumns(widthDp)
        return options[densityIndex.coerceIn(options.indices)]
    }

    /**
     * Seeds the density level from a persisted absolute column count, snapping
     * to the closest option of the active width bucket. An explicit count seeds
     * once; a null count (automatic) follows the width until the user pinches or
     * cycles, so the default grid adapts to folds and rotation. After any user
     * change the density state owns the value for the rest of the session.
     */
    fun seedFromPreferredColumns(columns: Int?, widthDp: Int) {
        if (seededFromPreferredColumns || userAdjusted) return
        if (columns != null) seededFromPreferredColumns = true
        val target = columns ?: GalleryGridMetrics.adaptiveColumns(widthDp.dp)
        val options = adaptiveDensityColumns(widthDp)
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        options.forEachIndexed { index, candidate ->
            val distance = abs(candidate - target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (densityIndex != bestIndex) densityIndex = bestIndex
    }

    /** True once the user changed the density by pinch or button in this session. */
    var userAdjusted = false
        private set

    /** Cycles through density options while preserving the current media anchor. */
    fun cycleDensity(anchorIndex: Int, anchorOffset: Int = 0, anchorStableKey: String? = null): Boolean {
        this.anchorIndex = anchorIndex.coerceAtLeast(0)
        this.anchorOffset = anchorOffset.coerceAtLeast(0)
        this.anchorStableKey = anchorStableKey
        this.anchorTopPx = null
        anchorRestorePending = true
        userAdjusted = true
        densityIndex = (densityIndex + 1) % 5
        return true
    }

    fun changeDensity(
        delta: Int,
        anchorIndex: Int,
        anchorOffset: Int = 0,
        anchorStableKey: String? = null,
        anchorTopPx: Float? = null,
    ): Boolean {
        val next = (densityIndex + delta).coerceIn(0, 4)
        if (next == densityIndex) return false
        this.anchorIndex = anchorIndex.coerceAtLeast(0)
        this.anchorOffset = anchorOffset.coerceAtLeast(0)
        this.anchorStableKey = anchorStableKey
        this.anchorTopPx = anchorTopPx
        anchorRestorePending = true
        userAdjusted = true
        densityIndex = next
        return true
    }

    internal fun observeAnchor(index: Int, offset: Int) {
        if (anchorRestorePending) return
        anchorIndex = index.coerceAtLeast(0)
        anchorOffset = offset.coerceAtLeast(0)
    }

    internal fun prepareColumnChange(columns: Int, index: Int, stableKey: String?) {
        if (lastColumns < 0) {
            lastColumns = columns
            return
        }
        if (columns == lastColumns) return
        lastColumns = columns
        if (!anchorRestorePending) {
            anchorIndex = index.coerceAtLeast(0)
            anchorOffset = 0
            anchorStableKey = stableKey
            anchorTopPx = null
            anchorRestorePending = true
        }
    }

    internal fun completeAnchorRestore(index: Int, offset: Int) {
        anchorRestorePending = false
        anchorStableKey = null
        anchorTopPx = null
        observeAnchor(index, offset)
    }

    internal fun consumeAnchorTop(): Float? = anchorTopPx

    internal fun resolveRestoreIndex(resolveStableKey: (String) -> Int?): Int =
        anchorStableKey?.let(resolveStableKey) ?: anchorIndex

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
    resolveStableKey: (String) -> Int? = { null },
) {
    val hasItems = itemCount > 0
    val restoreIndex = densityState.anchorIndex
    val restoreOffset = densityState.anchorOffset
    LaunchedEffect(columns, hasItems) {
        if (hasItems) {
            val resolvedIndex = densityState.resolveRestoreIndex(resolveStableKey)
            val targetIndex = resolvedIndex.coerceIn(0, itemCount - 1)
            val anchorTop = densityState.consumeAnchorTop()
            if (anchorTop == null) {
                gridState.scrollToItem(targetIndex, restoreOffset)
            } else if (anchorTop < 0f) {
                gridState.scrollToItem(targetIndex, (-anchorTop).roundToInt())
            } else {
                // Keep the pinched tile under the fingers; the list start clamps naturally.
                gridState.scrollToItem(targetIndex, 0)
                gridState.scrollBy(-anchorTop)
            }
            densityState.completeAnchorRestore(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) -> densityState.observeAnchor(index, offset) }
    }
}

/**
 * Google Photos-style pinch: the grid scales live under the fingers, commits to the next
 * column count once the pinch passes most of the way there (keeping the tile under the
 * fingers in place), and springs back to rest on release. Pinching further in at the
 * largest size opens the tile under the fingers through [onPinchOpen].
 */
internal fun Modifier.timelinePinchDensity(
    densityState: TimelineDensityState,
    gridState: LazyGridState,
    stableKeyAt: (Int) -> String? = { null },
    haptics: HapticFeedback? = null,
    onPinchOpen: ((index: Int) -> Boolean)? = null,
): Modifier = pointerInput(densityState, gridState) {
    coroutineScope {
        var settle: Job? = null
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var pinching = false
            var opened = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!opened && event.changes.count { it.pressed } >= 2) {
                    if (!pinching) {
                        pinching = true
                        settle?.cancel()
                        // Resume from wherever an interrupted settle left the grid.
                        zoom = densityState.pinchScale
                    }
                    zoom *= event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid.isSpecified) densityState.pinchPivot = centroid
                    event.changes.forEach { it.consume() }

                    val options = adaptiveDensityColumns(size.width.toDp().value.toInt())
                    val columnsFor = { i: Int -> options[i] }
                    val index = densityState.densityIndex
                    val columns = columnsFor(index)
                    val fewer = if (index > 0) columnsFor(index - 1) else null
                    val more = if (index < 4) columnsFor(index + 1) else null
                    val step = when {
                        fewer != null && zoom > (columns.toFloat() / fewer).pow(CommitFraction) -> -1
                        more != null && zoom < (columns.toFloat() / more).pow(CommitFraction) -> 1
                        else -> 0
                    }
                    if (step != 0) {
                        val next = if (step < 0) fewer!! else more!!
                        val pivotY = densityState.pinchPivot.y
                        val hit = gridState.layoutInfo.itemAt(densityState.pinchPivot)
                            ?: gridState.layoutInfo.visibleItemsInfo.firstOrNull()
                        val anchorIndex = hit?.index ?: gridState.firstVisibleItemIndex
                        // Where the tile's top must sit so the new layout, shown at the carried
                        // over scale, lines up with what the fingers see right now.
                        val anchorTop = hit?.let { pivotY + (it.offset.y - pivotY) * columns / next }
                        if (densityState.changeDensity(
                                delta = step,
                                anchorIndex = anchorIndex,
                                anchorStableKey = stableKeyAt(anchorIndex),
                                anchorTopPx = anchorTop,
                            )
                        ) {
                            zoom *= next.toFloat() / columns
                            haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    } else if (fewer == null && onPinchOpen != null && zoom > OpenZoom) {
                        val hit = gridState.layoutInfo.itemAt(densityState.pinchPivot)
                        if (hit != null && onPinchOpen(hit.index)) {
                            opened = true
                            haptics?.performHapticFeedback(HapticFeedbackType.Confirm)
                        }
                    }
                    densityState.pinchScale = rubberBand(
                        zoom,
                        min = if (more == null) 1f else 0f,
                        max = if (fewer == null && onPinchOpen == null) 1f else Float.MAX_VALUE,
                    )
                }
            } while (event.changes.any { it.pressed })
            if (densityState.pinchScale != 1f) {
                settle = launch {
                    animate(
                        initialValue = densityState.pinchScale,
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) { value, _ -> densityState.pinchScale = value }
                }
            }
        }
    }
}
    .clipToBounds()
    .graphicsLayer {
        val scale = densityState.pinchScale
        scaleX = scale
        scaleY = scale
        val pivot = densityState.pinchPivot
        transformOrigin = if (size.width > 0f && size.height > 0f) {
            TransformOrigin(pivot.x / size.width, pivot.y / size.height)
        } else {
            TransformOrigin.Center
        }
    }

/** Fraction of the way (in log-zoom) to the next column count at which a pinch commits. */
private const val CommitFraction = 0.6f

/** Zoom past the largest tiles at which the tile under the fingers opens. */
private const val OpenZoom = 1.45f

/** Resists scaling beyond [min]/[max] so a pinch at a density limit feels elastic. */
private fun rubberBand(zoom: Float, min: Float, max: Float): Float = when {
    zoom > max -> max * (zoom / max).pow(0.25f)
    zoom < min -> min * (zoom / min).pow(0.25f)
    else -> zoom
}

private fun LazyGridLayoutInfo.itemAt(position: Offset): LazyGridItemInfo? =
    visibleItemsInfo.firstOrNull { item ->
        position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
            position.y >= item.offset.y && position.y < item.offset.y + item.size.height
    }
