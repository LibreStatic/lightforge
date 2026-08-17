package com.ugallery.feature.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDensityStateTest {
    @Test
    fun adaptiveColumnsCoverCompactMediumAndExpanded() {
        assertEquals(listOf(3, 4, 5, 7), adaptiveDensityColumns(599).toList())
        assertEquals(listOf(5, 6, 7, 9), adaptiveDensityColumns(600).toList())
        assertEquals(listOf(7, 9, 11, 13), adaptiveDensityColumns(840).toList())
    }

    @Test
    fun densityChangeCapturesAnchorAndClampsAtLimits() {
        val state = TimelineDensityState(0, 0, 0)
        assertTrue(state.changeDensity(1, anchorIndex = 42, anchorOffset = 17))
        assertEquals(42, state.anchorIndex)
        assertEquals(17, state.anchorOffset)
        repeat(2) { assertTrue(state.changeDensity(1, 42)) }
        assertFalse(state.changeDensity(1, 99))
        assertEquals(3, state.densityIndex)
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
