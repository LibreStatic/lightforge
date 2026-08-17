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
}
