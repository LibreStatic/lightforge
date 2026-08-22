package com.ugallery.app

import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.search.MediaSearchHit
import com.ugallery.core.search.SearchRankingDebug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySearchUiStateTest {
    @Test
    fun `editing query clears stale results and execution state`() {
        val dogHit = MediaSearchHit(
            key = MediaKey("external_primary", 42L),
            kind = MediaKind.Image,
            displayName = "dog.jpg",
            timelineSortMillis = 1L,
            generationModified = 1L,
            favorite = false,
            debug = SearchRankingDebug("dog", "relevance", 1.0, listOf("canonicalLabels")),
        )
        val previous = GallerySearchUiState(
            query = "perros",
            hits = listOf(dogHit),
            loading = true,
            terminal = true,
            error = true,
        )

        val edited = previous.withEditedQuery("montañas")

        assertEquals("montañas", edited.query)
        assertTrue(edited.hits.isEmpty())
        assertFalse(edited.loading)
        assertFalse(edited.terminal)
        assertFalse(edited.error)
    }

    @Test
    fun `clearing query returns to terminal discovery state`() {
        val edited = GallerySearchUiState(query = "perros").withEditedQuery("")

        assertEquals("", edited.query)
        assertTrue(edited.terminal)
    }
}
