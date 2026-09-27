package com.librestatic.lightforge

import com.librestatic.lightforge.feature.collections.CollectionsSectionEmptyState
import com.librestatic.lightforge.feature.collections.collectionsSectionEmptyState
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionsSectionEmptyStateTest {
    @Test
    fun `empty virtual album section only claims that albums are missing`() {
        assertEquals(
            CollectionsSectionEmptyState.NoAlbums,
            collectionsSectionEmptyState("virtual-albums", itemCount = 0, refreshing = false),
        )
    }

    @Test
    fun `empty device folder section claims only its own emptiness`() {
        assertEquals(
            CollectionsSectionEmptyState.NoDeviceFolders,
            collectionsSectionEmptyState("physical-albums", itemCount = 0, refreshing = false),
        )
    }

    @Test
    fun `a populated section shows no empty state`() {
        assertEquals(
            CollectionsSectionEmptyState.Hidden,
            collectionsSectionEmptyState("physical-albums", itemCount = 6, refreshing = false),
        )
    }

    @Test
    fun `a still loading section never claims emptiness`() {
        assertEquals(
            CollectionsSectionEmptyState.Hidden,
            collectionsSectionEmptyState("virtual-albums", itemCount = 0, refreshing = true),
        )
    }

    @Test
    fun `non album sections own no empty state`() {
        assertEquals(
            CollectionsSectionEmptyState.Hidden,
            collectionsSectionEmptyState("memories", itemCount = 0, refreshing = false),
        )
    }
}
