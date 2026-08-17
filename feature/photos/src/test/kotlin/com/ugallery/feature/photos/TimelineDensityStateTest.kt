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
}
