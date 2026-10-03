package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMediaGridTest {
    private fun columns(width: Dp) = mediaGridLayout(width).columns

    @Test
    fun columnsFollowWidthAcrossFormFactors() {
        assertEquals(3, columns(360.dp)) // small phone
        assertEquals(3, columns(393.dp)) // phone portrait
        assertEquals(4, columns(577.dp)) // fold pane next to a rail
        assertEquals(4, columns(673.dp)) // unfolded portrait
        assertEquals(5, columns(756.dp)) // phone landscape pane
        assertEquals(6, columns(1184.dp)) // tablet pane
        assertEquals(10, columns(1824.dp)) // desktop pane
    }

    @Test
    fun cellsAndGapsFillTheWidthExactly() {
        listOf(360, 393, 577, 673, 756, 841, 1184, 1824).forEach { w ->
            val layout = mediaGridLayout(w.dp)
            val total = layout.startPadding + layout.contentWidth + layout.endPadding
            assertEquals("width $w", w.toFloat(), total.value, 0.01f)
            assertEquals(layout.startPadding, layout.endPadding)
            assertTrue("cell at $w", layout.cellSize >= AdaptiveMediaGridDefaults.minCellSize(w.dp))
        }
    }

    @Test
    fun edgeMarginsGrowWithWindowClass() {
        assertEquals(GallerySpacing.Xs, mediaGridLayout(393.dp).startPadding)
        assertEquals(GallerySpacing.Lg, mediaGridLayout(700.dp).startPadding)
        assertEquals(GallerySpacing.Xxl, mediaGridLayout(1280.dp).startPadding)
    }

    @Test
    fun edgeMarginIsCappedOnTinyWidths() {
        val layout = mediaGridLayout(200.dp, edgePadding = 100.dp)
        assertEquals(50.dp, layout.startPadding)
    }

    @Test
    fun columnCountIsClamped() {
        assertEquals(2, mediaGridLayout(150.dp).columns)
        assertEquals(16, mediaGridLayout(8000.dp).columns)
    }

    @Test
    fun sparseGridKeepsCellsBoundedAndLeftAligned() {
        val layout = mediaGridLayout(1184.dp, itemCount = 2)
        assertEquals(2, layout.columns)
        assertEquals(AdaptiveMediaGridDefaults.MaxSparseCell, layout.cellSize)
        assertEquals(GallerySpacing.Xxl, layout.startPadding)
        val total = layout.startPadding + layout.contentWidth + layout.endPadding
        assertEquals(1184f, total.value, 0.01f)
    }

    @Test
    fun sparseVariantOnlyAppliesBelowTheColumnCount() {
        assertEquals(3, mediaGridLayout(393.dp, itemCount = 3).columns)
        assertEquals(3, mediaGridLayout(393.dp, itemCount = 500).columns)
        assertEquals(3, mediaGridLayout(393.dp, itemCount = 0).columns)
    }

    @Test
    fun decodeSizeFollowsCellAndDensity() {
        val layout = mediaGridLayout(393.dp)
        assertEquals((layout.cellSize.value * 2.75f).toInt(), layout.cellSizePx(2.75f))
    }
}
