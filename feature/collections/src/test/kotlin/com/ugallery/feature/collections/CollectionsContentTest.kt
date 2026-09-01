package com.ugallery.feature.collections

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionsContentTest {
    @Test
    fun gridColumnsRespectCompactAndExpandedBoundaries() {
        assertEquals(1, collectionGridColumns(291.dp))
        assertEquals(2, collectionGridColumns(292.dp))
        assertEquals(2, collectionGridColumns(839.dp))
        assertEquals(4, collectionGridColumns(840.dp))
    }

    @Test
    fun razrCoverContentWidthUsesTwoColumns() {
        assertEquals(2, collectionGridColumns(328.dp))
    }

    @Test
    fun collectionRowsOnlyAllocateTheRowsRequiredByLoadedItems() {
        assertEquals(0, collectionRowCount(itemCount = 0, columns = 4))
        assertEquals(1, collectionRowCount(itemCount = 1, columns = 4))
        assertEquals(1, collectionRowCount(itemCount = 4, columns = 4))
        assertEquals(2, collectionRowCount(itemCount = 5, columns = 4))
    }
}
