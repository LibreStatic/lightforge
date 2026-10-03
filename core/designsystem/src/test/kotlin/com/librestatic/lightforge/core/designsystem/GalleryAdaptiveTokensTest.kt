package com.librestatic.lightforge.core.designsystem

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
        assertEquals(GalleryNavigationType.Floating, galleryAdaptiveLayoutInfo(390.dp).navigationType)
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

    @Test
    fun navigationFollowsWidthAndHeightNotOrientation() {
        // Phone portrait and landscape both float: the landscape phone is too short for a rail.
        assertEquals(GalleryNavigationType.Floating, galleryNavigationType(393.dp, 852.dp))
        assertEquals(GalleryNavigationType.Floating, galleryNavigationType(852.dp, 393.dp))
        // Foldable inner screen, tablet and desktop in either orientation get the rail.
        assertEquals(GalleryNavigationType.Rail, galleryNavigationType(673.dp, 841.dp))
        assertEquals(GalleryNavigationType.Rail, galleryNavigationType(852.dp, 883.dp))
        assertEquals(GalleryNavigationType.Rail, galleryNavigationType(1_280.dp, 800.dp))
        assertEquals(GalleryNavigationType.Rail, galleryNavigationType(800.dp, 1_280.dp))
        assertEquals(GalleryNavigationType.Rail, galleryNavigationType(1_920.dp, 1_080.dp))
        // Exact thresholds.
        assertEquals(GalleryNavigationType.Floating, galleryNavigationType(599.dp, 900.dp))
        assertEquals(GalleryNavigationType.Floating, galleryNavigationType(900.dp, 479.dp))
        assertEquals(GalleryNavigationType.Rail, galleryNavigationType(600.dp, 480.dp))
    }

    @Test
    fun navigationHasHysteresisForResizedWindows() {
        val floating = GalleryNavigationType.Floating
        val rail = GalleryNavigationType.Rail
        // Growing out of the floating navigation needs 616 x 496.
        assertEquals(floating, galleryNavigationType(610.dp, 700.dp, previous = floating))
        assertEquals(floating, galleryNavigationType(700.dp, 490.dp, previous = floating))
        assertEquals(rail, galleryNavigationType(616.dp, 496.dp, previous = floating))
        // An existing rail survives until the window drops below 600 x 480.
        assertEquals(rail, galleryNavigationType(605.dp, 485.dp, previous = rail))
        assertEquals(floating, galleryNavigationType(599.dp, 700.dp, previous = rail))
        assertEquals(floating, galleryNavigationType(700.dp, 479.dp, previous = rail))
    }

    @Test
    fun postureOverridesTheSizeRule() {
        val tabletop = GalleryFoldInfo(
            GalleryFoldOrientation.Horizontal, isSeparating = true,
            left = 0.dp, top = 416.dp, right = 673.dp, bottom = 424.dp,
        )
        val book = GalleryFoldInfo(
            GalleryFoldOrientation.Vertical, isSeparating = true,
            left = 332.dp, top = 0.dp, right = 340.dp, bottom = 841.dp,
        )
        assertEquals(GalleryNavigationType.Floating, galleryNavigationType(673.dp, 841.dp, tabletop))
        assertEquals(
            GalleryNavigationType.Rail,
            galleryNavigationType(673.dp, 841.dp, book, previous = GalleryNavigationType.Floating),
        )
        assertEquals(GalleryNavigationType.Floating, galleryNavigationType(841.dp, 400.dp, book))
    }

    @Test
    fun contentWidthExcludesTheRail() {
        assertEquals(393.dp, galleryAdaptiveLayoutInfo(393.dp, height = 852.dp).contentWidth)
        assertEquals(
            1_280.dp - GalleryNavigationRailWidth,
            galleryAdaptiveLayoutInfo(1_280.dp, height = 800.dp).contentWidth,
        )
    }

    @Test
    fun masterPaneUsesDpBoundsNeverAFraction() {
        assertEquals(null, galleryMasterPaneWidth(659.dp))
        assertEquals(GalleryPaneMetrics.MasterMinWidth, galleryMasterPaneWidth(660.dp))
        assertEquals(GalleryPaneMetrics.MasterMinWidth, galleryMasterPaneWidth(721.dp))
        assertEquals(320.dp, galleryMasterPaneWidth(880.dp))
        assertEquals(GalleryPaneMetrics.MasterMaxWidth, galleryMasterPaneWidth(1_160.dp))
        assertEquals(GalleryPaneMetrics.MasterMaxWidth, galleryMasterPaneWidth(1_800.dp))
        assertEquals(false, galleryShowsSupportingPane(1_099.dp))
        assertEquals(true, galleryShowsSupportingPane(1_160.dp))
        assertEquals(true, ReadableContentMaxWidth in 640.dp..760.dp)
    }

    @Test
    fun sidePanelDefaultsToOpenOnlyWithRoomUnlessTheUserChose() {
        assertEquals(false, GallerySidePanelMetrics.initiallyOpen(GalleryWindowClass.Compact, null))
        assertEquals(true, GallerySidePanelMetrics.initiallyOpen(GalleryWindowClass.Medium, null))
        assertEquals(true, GallerySidePanelMetrics.initiallyOpen(GalleryWindowClass.Expanded, null))
        assertEquals(false, GallerySidePanelMetrics.initiallyOpen(GalleryWindowClass.Expanded, false))
        assertEquals(true, GallerySidePanelMetrics.initiallyOpen(GalleryWindowClass.Compact, true))
    }

    @Test
    fun sidePanelWidensWithWindowClass() {
        assertEquals(96.dp, GallerySidePanelMetrics.width(GalleryWindowClass.Compact))
        assertEquals(136.dp, GallerySidePanelMetrics.width(GalleryWindowClass.Medium))
        assertEquals(168.dp, GallerySidePanelMetrics.width(GalleryWindowClass.Expanded))
    }
}
