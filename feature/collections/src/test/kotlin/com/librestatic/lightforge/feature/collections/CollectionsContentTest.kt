package com.librestatic.lightforge.feature.collections

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionsContentTest {
    @Test
    fun tileColumnsFollowWidthWithATwoColumnFloor() {
        assertEquals(2, collectionGridColumns(200.dp))
        assertEquals(2, collectionGridColumns(328.dp))
        assertEquals(2, collectionGridColumns(495.dp))
        assertEquals(3, collectionGridColumns(496.dp))
        assertEquals(5, collectionGridColumns(840.dp))
        assertEquals(6, collectionGridColumns(1_600.dp))
    }

    @Test
    fun collectionRowsOnlyAllocateTheRowsRequiredByLoadedItems() {
        assertEquals(0, collectionRowCount(itemCount = 0, columns = 4))
        assertEquals(1, collectionRowCount(itemCount = 1, columns = 4))
        assertEquals(1, collectionRowCount(itemCount = 4, columns = 4))
        assertEquals(2, collectionRowCount(itemCount = 5, columns = 4))
    }
}
