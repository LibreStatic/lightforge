package com.ugallery.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailPrefetchCandidate
import com.ugallery.core.thumbnail.ThumbnailPrefetchPlanner
import com.ugallery.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** Retains the complete safe viewport thumbnail window plus one directional row. */
@Composable
fun RetainGridThumbnailViewport(
    state: LazyGridState,
    loader: ThumbnailLoader,
    columns: Int,
    itemCount: Int,
    contentKey: Any? = Unit,
    itemAtIndex: (Int) -> ThumbnailPrefetchCandidate?,
) {
    if (columns <= 0 || itemCount <= 0 || loader.prefetchPolicy == null) return
    val currentItems = rememberUpdatedState(itemCount to itemAtIndex)
    val retained = remember(loader) { mutableStateMapOf<ThumbnailRequest, Bitmap>() }
    val owner = remember(loader) { Any() }
    DisposableEffect(loader, owner) {
        onDispose {
            loader.releaseWindow(owner)
            retained.clear()
        }
    }
    LaunchedEffect(state, loader, columns, itemCount, contentKey) {
        var previousAnchor: Pair<Int, Int>? = null
        var forward = true
        var observedMemoryPressure = loader.memoryPressureGeneration.value
        val viewportFlow = snapshotFlow {
            GridViewportSnapshot(
                visibleIndices = state.layoutInfo.visibleItemsInfo.map { it.index },
                anchorIndex = state.firstVisibleItemIndex,
                anchorOffset = state.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged()
        viewportFlow.combine(loader.memoryPressureGeneration) { viewport, pressure -> viewport to pressure }
            .collectLatest { (viewport, pressure) ->
                if (pressure != observedMemoryPressure) {
                    observedMemoryPressure = pressure
                    retained.clear()
                    loader.retainWindow(owner, emptyMap())
                    return@collectLatest
                }
                val anchor = viewport.anchorIndex to viewport.anchorOffset
                previousAnchor?.let { previous ->
                    forward = anchor.first > previous.first ||
                        (anchor.first == previous.first && anchor.second >= previous.second)
                }
                previousAnchor = anchor
                // The old layout may outlive a Paging refresh. Read count and accessor from
                // the same composition; never feed stale viewport indices to the new dataset.
                val (currentCount, currentItemAtIndex) = currentItems.value
                val visibleIndices = boundedViewportIndices(viewport.visibleIndices, currentCount)
                if (visibleIndices.isEmpty()) {
                    retained.clear()
                    loader.retainWindow(owner, emptyMap())
                    return@collectLatest
                }
                val center = (visibleIndices.first() + visibleIndices.last()) / 2
                val visible = visibleIndices.mapNotNull { index ->
                    currentItemAtIndex(index)?.copy(
                        distanceFromViewportCenter = abs(index - center),
                    )
                }
                val extra = ArrayList<ThumbnailPrefetchCandidate>(columns)
                val range = if (forward) {
                    (visibleIndices.last() + 1) until currentCount
                } else {
                    (visibleIndices.first() - 1 downTo 0)
                }
                for (index in range) {
                    currentItemAtIndex(index)?.let { candidate ->
                        extra += candidate.copy(distanceFromViewportCenter = abs(index - center))
                    }
                    if (extra.size >= columns) break
                }
                val plan = ThumbnailPrefetchPlanner.plan(
                    visible = visible,
                    extraRow = extra,
                    policy = requireNotNull(loader.prefetchPolicy),
                )
                val requests = plan.mapTo(linkedSetOf()) { it.request }
                retained.keys.toList().filterNot(requests::contains).forEach(retained::remove)
                val loaded = coroutineScope {
                    plan.map { planned ->
                        async {
                            runCatching {
                                planned.request to loader.load(planned.request, planned.priority)
                            }.getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                }
                loaded.forEach { (request, bitmap) -> retained[request] = bitmap }
                loader.retainWindow(owner, retained)
            }
    }
}

private data class GridViewportSnapshot(
    val visibleIndices: List<Int>,
    val anchorIndex: Int,
    val anchorOffset: Int,
)

internal fun boundedViewportIndices(indices: List<Int>, itemCount: Int): List<Int> =
    indices.filter { it >= 0 && it < itemCount }.distinct().sorted()
