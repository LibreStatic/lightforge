package com.ugallery.feature.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDensityStateTest {
    @Test
    fun adaptiveColumnsCoverCompactMediumAndExpanded() {
        assertEquals(listOf(2, 3, 4, 5, 7), adaptiveDensityColumns(599).toList())
        assertEquals(listOf(3, 5, 6, 7, 9), adaptiveDensityColumns(600).toList())
        assertEquals(listOf(5, 7, 9, 11, 13), adaptiveDensityColumns(840).toList())
    }

    @Test
    fun compactScaleReachesTwoColumnsLikeGooglePhotos() {
        val state = TimelineDensityState(0, 0, 0)
        assertEquals(2, state.columns(widthDp = 360))
    }

    @Test
    fun seedFromPreferredColumnsSnapsOnceToClosestOption() {
        val state = TimelineDensityState(0, 0, 0)
        // Persisted gridColumns=4 snaps exactly to the 4-column option.
        state.seedFromPreferredColumns(columns = 4, widthDp = 360)
        assertEquals(4, state.columns(360))

        // A second seed is ignored: later preference updates never override
        // user pinch/button changes made during the session.
        state.seedFromPreferredColumns(columns = 2, widthDp = 360)
        assertEquals(4, state.columns(360))
    }

    @Test
    fun changeDensityCanReachTwoColumnFloorAndStopThere() {
        val state = TimelineDensityState(0, 0, 0)
        assertFalse(state.changeDensity(delta = -1, anchorIndex = 0))
        assertEquals(2, state.columns(360))
        repeat(4) { assertTrue(state.changeDensity(delta = 1, anchorIndex = 0)) }
        assertFalse(state.changeDensity(delta = 1, anchorIndex = 99))
        assertEquals(7, state.columns(360))
    }

    @Test
    fun densityChangeCapturesAnchorAndClampsAtLimits() {
        val state = TimelineDensityState(0, 0, 0)
        assertTrue(state.changeDensity(1, anchorIndex = 42, anchorOffset = 17))
        assertEquals(42, state.anchorIndex)
        assertEquals(17, state.anchorOffset)
        repeat(3) { assertTrue(state.changeDensity(1, 42)) }
        assertFalse(state.changeDensity(1, 99))
        assertEquals(4, state.densityIndex)
    }

    @Test
    fun cycleDensityWrapsAfterLargestGridAndPreservesAnchor() {
        val state = TimelineDensityState(4, 0, 0)
        assertTrue(state.cycleDensity(anchorIndex = 24, anchorOffset = 9))
        assertEquals(0, state.densityIndex)
        assertEquals(24, state.anchorIndex)
        assertEquals(9, state.anchorOffset)
    }

    @Test
    fun pendingDensityRestoreCannotBeOverwrittenByOldLayoutObservation() {
        val state = TimelineDensityState(0, 5, 0)

        assertTrue(state.changeDensity(1, anchorIndex = 42, anchorOffset = 17))
        state.observeAnchor(index = 8, offset = 2)

        assertEquals(42, state.anchorIndex)
        assertEquals(17, state.anchorOffset)
        state.completeAnchorRestore(index = 42, offset = 0)
        state.observeAnchor(index = 43, offset = 3)
        assertEquals(43, state.anchorIndex)
        assertEquals(3, state.anchorOffset)
    }

    @Test
    fun pendingDensityRestorePrefersStableKeyResolution() {
        val state = TimelineDensityState(0, 5, 0)

        assertTrue(
            state.changeDensity(
                delta = 1,
                anchorIndex = 42,
                anchorStableKey = "media:external_primary:123",
            ),
        )

        assertEquals(39, state.resolveRestoreIndex { key -> if (key.endsWith(":123")) 39 else null })
    }

    @Test
    fun adaptiveColumnChangeCapturesPreLayoutStableAnchor() {
        val state = TimelineDensityState(0, 5, 0)

        state.prepareColumnChange(columns = 7, index = 5, stableKey = "media:primary:5")
        state.prepareColumnChange(columns = 5, index = 42, stableKey = "media:sd:42")
        state.observeAnchor(index = 99, offset = 7)

        assertEquals(42, state.anchorIndex)
        assertEquals(37, state.resolveRestoreIndex { key -> if (key == "media:sd:42") 37 else null })
    }
}
