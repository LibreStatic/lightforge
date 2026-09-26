package com.ugallery.core.designsystem

import com.ugallery.core.thumbnail.ThumbnailPrefetchCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewportThumbnailBoundsTest {
    @Test fun shrinkDropsStaleIndicesBeforeReadingTheNewDataset() {
        assertEquals(listOf(4, 5), boundedViewportIndices(listOf(4, 5, 6, 7), 6))
    }
    @Test fun emptyOrEntirelyRemovedViewportHasNoReads() {
        assertEquals(emptyList<Int>(), boundedViewportIndices(listOf(6, 7), 6))
        assertEquals(emptyList<Int>(), boundedViewportIndices(listOf(0), 0))
    }
    @Test fun validIndicesAreDistinctOrderedAndNegativeIndicesAreIgnored() {
        assertEquals(listOf(0, 2, 5), boundedViewportIndices(listOf(5, -1, 2, 0, 2), 6))
    }
    @Test fun accessorThatShrankUnderneathThePrefetchYieldsNoCandidate() {
        val live = emptyList<ThumbnailPrefetchCandidate>()
        assertNull(candidateAtOrNull(0) { index -> live[index] })
    }
}
