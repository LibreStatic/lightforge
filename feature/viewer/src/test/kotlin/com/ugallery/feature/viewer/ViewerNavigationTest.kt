package com.ugallery.feature.viewer

import com.ugallery.core.model.MediaKey
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerNavigationTest {
    @Test
    fun `neighbor navigation keeps captured source query`() = runTest {
        val captured = MediaQuery(scope = MediaQuery.Scope.PhysicalAlbum("external_primary", 9))
        val calls = mutableListOf<MediaQuery>()
        val controller = ViewerNavigationController(captured, key(10)) { query, current, direction ->
            calls += query
            if (direction == NeighborDirection.Next) key(current.mediaStoreId + 1) else null
        }

        assertTrue(controller.move(NeighborDirection.Next))
        assertFalse(controller.move(NeighborDirection.Previous))
        assertEquals(key(11), controller.position.value.current)
        assertEquals(listOf(captured, captured), calls)
        assertEquals(captured, controller.position.value.sourceQuery)
    }

    @Test
    fun `ten thousand rapid neighbor moves retain the anchor source`() = runTest {
        val captured = MediaQuery(scope = MediaQuery.Scope.Search("camera"))
        val controller = ViewerNavigationController(captured, key(0)) { _, current, _ ->
            key(current.mediaStoreId + 1)
        }
        repeat(10_000) { assertTrue(controller.move(NeighborDirection.Next)) }
        assertEquals(10_000L, controller.position.value.current.mediaStoreId)
        assertEquals(captured, controller.position.value.sourceQuery)
    }

    private fun key(id: Long) = MediaKey("external_primary", id)
}
