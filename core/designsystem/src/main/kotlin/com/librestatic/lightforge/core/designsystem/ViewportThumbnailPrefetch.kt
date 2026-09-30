package com.librestatic.lightforge.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchCandidate
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchPlanner
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Retains the complete safe viewport thumbnail window plus [aheadRows] in the scroll direction and
 * [behindRows] against it. While [paused] (a fast scrub) nothing new is decoded; the window is
 * planned again as soon as it resumes.
 *
 * The window is replanned only when the visible index range changes, never per scrolled pixel,
 * and a request that stays in the window keeps its running decode. Slow sources (video frames on
 * a cold provider) would otherwise be cancelled and restarted on every frame and never land.
 */
@Composable
fun RetainGridThumbnailViewport(
    state: LazyGridState,
    loader: ThumbnailLoader,
    columns: Int,
    itemCount: Int,
    contentKey: Any? = Unit,
    paused: Boolean = false,
    aheadRows: Int = 1,
    behindRows: Int = 0,
    itemAtIndex: (Int) -> ThumbnailPrefetchCandidate?,
) {
    if (columns <= 0 || itemCount <= 0 || loader.prefetchPolicy == null) return
    val currentItems = rememberUpdatedState(itemCount to itemAtIndex)
    val pausedState = rememberUpdatedState(paused)
    // Main-thread only: written by the collector and its child loads, read on dispose.
    val retained = remember(loader) { LinkedHashMap<ThumbnailRequest, Bitmap>() }
    val owner = remember(loader) { Any() }
    DisposableEffect(loader, owner) {
        onDispose {
            loader.releaseWindow(owner)
            retained.clear()
        }
    }
    LaunchedEffect(state, loader, columns, itemCount, contentKey, aheadRows, behindRows) {
        var previousFirst: Int? = null
        var forward = true
        var observedMemoryPressure = loader.memoryPressureGeneration.value
        val loads = HashMap<ThumbnailRequest, Job>()
        fun cancelLoads() {
            loads.values.forEach { it.cancel() }
            loads.clear()
        }
        val viewportFlow = snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            GridViewportSnapshot(
                firstVisible = visible.minOfOrNull { it.index } ?: -1,
                lastVisible = visible.maxOfOrNull { it.index } ?: -1,
                paused = pausedState.value,
            )
        }.distinctUntilChanged()
        viewportFlow.combine(loader.memoryPressureGeneration) { viewport, pressure -> viewport to pressure }
            .collect { (viewport, pressure) ->
                if (pressure != observedMemoryPressure) {
                    observedMemoryPressure = pressure
                    cancelLoads()
                    retained.clear()
                    loader.retainWindow(owner, emptyMap())
                    return@collect
                }
                if (viewport.paused) {
                    cancelLoads()
                    return@collect
                }
                previousFirst?.let { previous ->
                    if (viewport.firstVisible != previous) forward = viewport.firstVisible > previous
                }
                previousFirst = viewport.firstVisible
                // The old layout may outlive a Paging refresh. Read count and accessor from
                // the same composition; never feed stale viewport indices to the new dataset.
                val (currentCount, currentItemAtIndex) = currentItems.value
                val visibleIndices = if (viewport.firstVisible < 0) emptyList() else boundedViewportIndices(
                    (viewport.firstVisible..viewport.lastVisible).toList(),
                    currentCount,
                )
                if (visibleIndices.isEmpty()) {
                    cancelLoads()
                    retained.clear()
                    loader.retainWindow(owner, emptyMap())
                    return@collect
                }
                val center = (visibleIndices.first() + visibleIndices.last()) / 2
                val visible = visibleIndices.mapNotNull { index ->
                    candidateAtOrNull(index, currentItemAtIndex)?.copy(
                        distanceFromViewportCenter = abs(index - center),
                    )
                }
                val after = (visibleIndices.last() + 1) until currentCount
                val before = visibleIndices.first() - 1 downTo 0
                val extra = ArrayList<ThumbnailPrefetchCandidate>(columns * (aheadRows + behindRows))
                fun collect(range: IntProgression, rows: Int) {
                    var taken = 0
                    for (index in range) {
                        if (taken >= columns * rows) break
                        candidateAtOrNull(index, currentItemAtIndex)?.let { candidate ->
                            extra += candidate.copy(distanceFromViewportCenter = abs(index - center))
                            taken++
                        }
                    }
                }
                collect(if (forward) after else before, aheadRows)
                collect(if (forward) before else after, behindRows)
                val plan = ThumbnailPrefetchPlanner.plan(
                    visible = visible,
                    extraRow = extra,
                    policy = requireNotNull(loader.prefetchPolicy),
                )
                val requests = plan.mapTo(HashSet()) { it.request }
                loads.keys.filterNot(requests::contains).forEach { loads.remove(it)?.cancel() }
                retained.keys.filterNot(requests::contains).forEach(retained::remove)
                loader.retainWindow(owner, retained)
                plan.forEach { planned ->
                    val request = planned.request
                    if (request in retained || loads[request]?.isActive == true) return@forEach
                    loads[request] = launch {
                        val bitmap = try {
                            loader.load(request, planned.priority)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        } finally {
                            if (loads[request] === coroutineContext[Job]) loads.remove(request)
                        }
                        if (bitmap != null) {
                            retained[request] = bitmap
                            loader.retainWindow(owner, retained)
                        }
                    }
                }
            }
    }
}

private data class GridViewportSnapshot(
    val firstVisible: Int,
    val lastVisible: Int,
    val paused: Boolean,
)

internal fun boundedViewportIndices(indices: List<Int>, itemCount: Int): List<Int> =
    indices.filter { it >= 0 && it < itemCount }.distinct().sorted()

/**
 * The accessor reads live Paging data while [itemCount] is the composition's snapshot, so a
 * refresh that empties the list (restoring the last Trash item) can shrink it in between.
 * A vanished index is simply not prefetched.
 */
internal fun candidateAtOrNull(
    index: Int,
    itemAtIndex: (Int) -> ThumbnailPrefetchCandidate?,
): ThumbnailPrefetchCandidate? = try {
    itemAtIndex(index)
} catch (_: IndexOutOfBoundsException) {
    null
}
