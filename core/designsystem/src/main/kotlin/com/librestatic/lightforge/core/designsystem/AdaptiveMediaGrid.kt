package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry of one media grid at one width: how many columns, how big each square cell is,
 * and the padding that places the grid inside the available width.
 *
 * [startPadding] is the edge margin. [endPadding] is the edge margin plus whatever a sparse grid
 * leaves unused, so a grid with fewer items than columns keeps its cells at a sensible size and
 * aligned with the headers above it instead of stretching or leaving a ghost column.
 */
@Immutable
data class MediaGridLayout(
    val columns: Int,
    val cellSize: Dp,
    val gap: Dp,
    val startPadding: Dp,
    val endPadding: Dp,
) {
    /** Width of the cells and gaps, without the padding. */
    val contentWidth: Dp get() = cellSize * columns + gap * (columns - 1)

    fun contentPadding(top: Dp = 0.dp, bottom: Dp = 0.dp) =
        PaddingValues(start = startPadding, top = top, end = endPadding, bottom = bottom)

    /** Thumbnail decode size for one cell. */
    fun cellSizePx(density: Float): Int = (cellSize.value * density).toInt().coerceAtLeast(1)
}

/**
 * Sizing rules for every media grid (Photos, search results, albums, picker, trash).
 *
 * Columns come from the available width and a minimum cell size, never from a fixed count, so a
 * fold, a tablet and a desktop window each get a full row instead of fixed cells and a ghost
 * column. Edge margins grow with the window class; gaps stay at [Gap] everywhere.
 */
object AdaptiveMediaGridDefaults {
    val Gap: Dp = GalleryGridMetrics.Gap

    /** Upper bound for a cell in the sparse variant (one to a few results on a wide window). */
    val MaxSparseCell: Dp = 240.dp

    const val MinColumns: Int = 2
    const val MaxColumns: Int = 16

    /**
     * Minimum cell size for a grid whose own width is [width]: 112 dp on phones, growing linearly
     * to 160 dp at 1200 dp. About three columns on a phone, four to five on a fold, seven on a
     * tablet and ten on a desktop window. The ramp is continuous so resizing a window never drops
     * several columns at a breakpoint.
     */
    fun minCellSize(width: Dp): Dp = when {
        width < 600.dp -> 112.dp
        width >= 1200.dp -> 160.dp
        else -> 112.dp + 48.dp * ((width - 600.dp) / 600.dp)
    }

    /** Edge margin for a grid whose own width is [width]. Phones run the grid nearly edge to edge. */
    fun edgePadding(width: Dp): Dp = when {
        width < 600.dp -> GallerySpacing.Xs
        width < 840.dp -> GallerySpacing.Lg
        else -> GallerySpacing.Xxl
    }
}

/**
 * Computes the [MediaGridLayout] for [availableWidth].
 *
 * @param itemCount the number of items when the whole grid is known and small (search results,
 *   a short album); null for paged or sectioned grids. When it is below the column count, the
 *   sparse variant uses one column per item with cells up to [maxSparseCellSize].
 */
fun mediaGridLayout(
    availableWidth: Dp,
    minCellSize: Dp = AdaptiveMediaGridDefaults.minCellSize(availableWidth),
    gap: Dp = AdaptiveMediaGridDefaults.Gap,
    edgePadding: Dp = AdaptiveMediaGridDefaults.edgePadding(availableWidth),
    minColumns: Int = AdaptiveMediaGridDefaults.MinColumns,
    maxColumns: Int = AdaptiveMediaGridDefaults.MaxColumns,
    itemCount: Int? = null,
    maxSparseCellSize: Dp = AdaptiveMediaGridDefaults.MaxSparseCell,
): MediaGridLayout {
    require(minColumns in 1..maxColumns)
    val width = max(availableWidth.value, 0f)
    val edge = min(max(edgePadding.value, 0f), width / 4f)
    val usable = width - 2 * edge
    val fit = floor((usable + gap.value) / (max(minCellSize.value, 1f) + gap.value)).toInt()
    val columns = fit.coerceIn(minColumns, maxColumns)
    val cell = max((usable - gap.value * (columns - 1)) / columns, 1f)
    if (itemCount != null && itemCount in 1 until columns) {
        val sparseCell = min(
            max((usable - gap.value * (itemCount - 1)) / itemCount, 1f),
            max(maxSparseCellSize.value, cell),
        )
        val used = sparseCell * itemCount + gap.value * (itemCount - 1)
        return MediaGridLayout(
            columns = itemCount,
            cellSize = sparseCell.dp,
            gap = gap,
            startPadding = edge.dp,
            endPadding = (width - edge - used).coerceAtLeast(edge).dp,
        )
    }
    return MediaGridLayout(
        columns = columns,
        cellSize = cell.dp,
        gap = gap,
        startPadding = edge.dp,
        endPadding = edge.dp,
    )
}

/**
 * A lazy grid of square media cells sized by [mediaGridLayout]. The grid measures its own width,
 * so it adapts to panes, rails and side panels as well as to the window.
 *
 * @param itemCount pass the total for a small, fully known list to enable the sparse variant.
 * @param content receives the computed layout (columns, cell size, decode size) so items and
 *   headers (`GridItemSpan(maxLineSpan)`) can use it.
 */
@Composable
fun AdaptiveMediaGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    itemCount: Int? = null,
    minCellSize: Dp? = null,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = galleryBottomContentPadding(),
    gridModifier: Modifier = Modifier,
    content: LazyGridScope.(MediaGridLayout) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val layout = mediaGridLayout(
            availableWidth = maxWidth,
            minCellSize = minCellSize ?: AdaptiveMediaGridDefaults.minCellSize(maxWidth),
            itemCount = itemCount,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            state = state,
            contentPadding = layout.contentPadding(top = topPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
            modifier = Modifier.fillMaxSize().then(gridModifier),
        ) {
            content(layout)
        }
    }
}
