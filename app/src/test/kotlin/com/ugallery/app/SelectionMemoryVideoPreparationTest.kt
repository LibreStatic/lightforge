package com.ugallery.app

import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.mediastore.MediaStoreRecord
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class SelectionMemoryVideoPreparationTest {
    private val key = MediaKey("external_primary", 5)
    private val row = MediaItemEntity("external_primary", 5, 1, "image/png", "photo.png", 30, 4, 4, 0, 0, null,
        1, 1, 1000, 7, 9, null, null, "Pictures/", false, false, true, 1)
    private val actual = MediaStoreRecord(key, MediaKind.Image, "image/png", "photo.png", 30, 4, 4, 0, 0, null,
        1, 1, 7, 9, null, null, "Pictures/", false, false)

    @Test fun boundedUniqueSelectionIncludesSinglePhoto() {
        assertTrue(SelectionMemoryVideoPreparation.acceptsSelection(listOf(key)))
        assertTrue(SelectionMemoryVideoPreparation.acceptsSelection((1L..120L).map { key.copy(mediaStoreId = it) }))
        assertFalse(SelectionMemoryVideoPreparation.acceptsSelection(emptyList()))
        assertFalse(SelectionMemoryVideoPreparation.acceptsSelection((1L..121L).map { key.copy(mediaStoreId = it) }))
        assertFalse(SelectionMemoryVideoPreparation.acceptsSelection(listOf(key, key)))
    }

    @Test fun exactPhotoIdentityAndGenerationsAreRequired() {
        assertTrue(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual.copy(generationAdded = 8)))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual.copy(generationModified = 10)))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row.copy(mediaStoreId = 6), actual))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual.copy(key = key.copy(volumeName = "other"))))
    }

    @Test fun inaccessibleTrashedAndNonPhotoRowsAreRejected() {
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row.copy(isAccessible = false), actual))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row.copy(isTrashed = true), actual))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual.copy(isTrashed = true)))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row.copy(mediaType = 3), actual))
        assertFalse(SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual.copy(kind = MediaKind.Video)))
    }
}
