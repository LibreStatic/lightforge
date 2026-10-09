package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Columns for [count] equal tiles in [available] width: as many as fit at [minTile], then
 * balanced so the last row is never a lone orphan (4 -> 4x1 or 2x2, never 3+1). */
internal fun equalTileColumns(count: Int, available: Dp, minTile: Dp, gap: Dp): Int {
    if (count <= 1) return 1
    val fit = ((available + gap) / (minTile + gap)).toInt().coerceIn(1, count)
    val rows = (count + fit - 1) / fit
    return (count + rows - 1) / rows
}

/** Width every tile of an [equalTileColumns] grid gets, capped at [maxTile]. */
internal fun equalTileWidth(count: Int, available: Dp, minTile: Dp, maxTile: Dp, gap: Dp): Dp {
    val cols = equalTileColumns(count, available, minTile, gap)
    return min(maxTile.value, ((available - gap * (cols - 1)) / cols).value).dp
}

/**
 * Grid of [count] tiles that all share ONE width and ONE height (the tallest tile's, across the
 * whole grid, so ragged names/descriptions never produce uneven cards). Start-aligned once tiles
 * reach [maxTile]. Each tile is measured with fixed constraints, so its container (e.g. a Surface,
 * which propagates min constraints) fills the cell while its content stays top-aligned; [tile]
 * also gets a [Modifier] to chain onto its root.
 *
 * A Measurable may be measured only once per pass, so the shared height is found one of two ways.
 * By default via maxIntrinsicHeight (an extra text-layout query per tile, then one real measure).
 * With [uniformHeight] the caller promises every tile has the same natural height at the shared
 * width (e.g. fixed-height swatch + one-line label): each tile is then measured exactly once, with
 * the shared width and free height, and the tallest result is the row height. If the promise is
 * broken, shorter tiles merely do not fill their cell.
 */
@Composable
internal fun PdfEqualTileGrid(
    count: Int,
    minTile: Dp,
    maxTile: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    uniformHeight: Boolean = false,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    Layout(content = { repeat(count) { tile(it, Modifier) } }, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val gapPx = gap.roundToPx()
        val available = constraints.maxWidth
        val minPx = minTile.roundToPx().coerceAtLeast(1)
        val fit = ((available + gapPx) / (minPx + gapPx)).coerceIn(1, count)
        val rows = (count + fit - 1) / fit
        val cols = (count + rows - 1) / rows
        val tileW = min(maxTile.roundToPx(), (available - gapPx * (cols - 1)) / cols)
        val placeables: List<Placeable>
        val tileH: Int
        if (uniformHeight) {
            placeables = measurables.map { it.measure(Constraints(minWidth = tileW, maxWidth = tileW)) }
            tileH = placeables.maxOf { it.height }
        } else {
            tileH = measurables.maxOf { it.maxIntrinsicHeight(tileW) }
            placeables = measurables.map { it.measure(Constraints.fixed(tileW, tileH)) }
        }
        val width = max(constraints.minWidth, min(available, cols * tileW + gapPx * (cols - 1)))
        val height = rows * tileH + gapPx * (rows - 1)
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.placeRelative((i % cols) * (tileW + gapPx), (i / cols) * (tileH + gapPx)) }
        }
    }
}
