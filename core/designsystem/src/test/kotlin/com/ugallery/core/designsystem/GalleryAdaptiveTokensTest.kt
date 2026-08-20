package com.ugallery.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryAdaptiveTokensTest {
    @Test
    fun androidWindowClassesDoNotReuseWebBreakpoints() {
        assertEquals(GalleryWindowClass.Compact, galleryWindowClass(599.dp))
        assertEquals(GalleryWindowClass.Medium, galleryWindowClass(600.dp))
        assertEquals(GalleryWindowClass.Medium, galleryWindowClass(839.dp))
        assertEquals(GalleryWindowClass.Expanded, galleryWindowClass(840.dp))
    }

    @Test
    fun gridCellTokenFollowsWindowClass() {
        assertEquals(GalleryGridMetrics.CompactCell, galleryGridCellSize(360.dp))
        assertEquals(GalleryGridMetrics.MediumCell, galleryGridCellSize(700.dp))
        assertEquals(GalleryGridMetrics.ExpandedCell, galleryGridCellSize(1_000.dp))
    }

    @Test
    fun navigationAndPanePolicyFollowWindowAndFold() {
        assertEquals(GalleryNavigationType.BottomBar, galleryAdaptiveLayoutInfo(390.dp).navigationType)
        assertEquals(GalleryNavigationType.Rail, galleryAdaptiveLayoutInfo(600.dp).navigationType)
        assertEquals(false, galleryAdaptiveLayoutInfo(820.dp).supportsTwoPane)
        assertEquals(true, galleryAdaptiveLayoutInfo(840.dp).supportsTwoPane)
        val verticalFold = GalleryFoldInfo(
            GalleryFoldOrientation.Vertical,
            isSeparating = true,
            left = 296.dp,
            top = 0.dp,
            right = 304.dp,
            bottom = 800.dp,
        )
        val horizontalFold = GalleryFoldInfo(
            GalleryFoldOrientation.Horizontal,
            isSeparating = true,
            left = 0.dp,
            top = 396.dp,
            right = 600.dp,
            bottom = 404.dp,
        )
        assertEquals(true, galleryAdaptiveLayoutInfo(600.dp, verticalFold).supportsTwoPane)
        assertEquals(false, galleryAdaptiveLayoutInfo(600.dp, horizontalFold).supportsTwoPane)
        assertEquals(8.dp, verticalFold.hingeWidth)
        assertEquals(8.dp, horizontalFold.hingeHeight)
    }
}
