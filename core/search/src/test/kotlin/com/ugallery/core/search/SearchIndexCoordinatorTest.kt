package com.ugallery.core.search

import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexCoordinatorTest {
    @Test fun `interrupted rebuild resumes from bounded checkpoint without clearing again`() = runTest {
        val index = FakeIndex()
        val state = FakeState()
        val documents = (0L until 1_200L).map(::document)
        val source = SearchDocumentSource { after, limit ->
            documents.filter { after == null || it.key.mediaStoreId > after.mediaStoreId }.take(limit)
        }
        val coordinator = SearchIndexCoordinator(index, source, state)
        var checks = 0

        val paused = coordinator.rebuild { checks++ == 0 }
        assertTrue(paused is SearchRebuildResult.Paused)
        assertEquals(500L, state.value?.indexedCount)
        assertEquals(500, index.documents.size)

        val complete = coordinator.rebuild()
        assertEquals(SearchRebuildResult.Complete(1_200), complete)
        assertEquals(1, index.clearCount)
        assertEquals(1_200, index.documents.size)
        assertEquals(null, state.value)
    }

    @Test fun `permission purge chunks keys without materializing provider operations`() = runTest {
        val index = FakeIndex()
        val coordinator = SearchIndexCoordinator(index, SearchDocumentSource { _, _ -> emptyList() }, FakeState())

        coordinator.onPermissionRevoked((0L until 1_201L).map { MediaKey("external_primary", it) })

        assertEquals(listOf(500, 500, 201), index.removeBatchSizes)
    }

    @Test fun `incremental changes are written in bounded batches`() = runTest {
        val index = FakeIndex()
        val coordinator = SearchIndexCoordinator(index, SearchDocumentSource { _, _ -> emptyList() }, FakeState())

        coordinator.onMediaChanged((0L until 1_001L).map(::document))

        assertEquals(listOf(500, 500, 1), index.putBatchSizes)
        assertEquals(1_001, index.documents.size)
    }

    @Test fun `restart clears derived index and ignores previous cursor`() = runTest {
        val index = FakeIndex()
        val state = FakeState(SearchRebuildCheckpoint(MediaKey("external_primary", 99), 100))
        val coordinator = SearchIndexCoordinator(
            index,
            SearchDocumentSource { _, _ -> emptyList() },
            state,
        )

        assertEquals(SearchRebuildResult.Complete(0), coordinator.rebuild(restart = true))
        assertEquals(1, index.clearCount)
        assertEquals(null, state.value)
    }

    private class FakeState(initial: SearchRebuildCheckpoint? = null) : SearchRebuildStateStore {
        var value = initial
        override fun read() = value
        override fun write(checkpoint: SearchRebuildCheckpoint) { value = checkpoint }
        override fun clear() { value = null }
    }

    private class FakeIndex : MediaSearchIndex {
        val documents = linkedMapOf<MediaKey, MediaSearchDocument>()
        val removeBatchSizes = mutableListOf<Int>()
        val putBatchSizes = mutableListOf<Int>()
        var clearCount = 0
        override suspend fun ensureSchema(forceOverride: Boolean) = Unit
        override suspend fun put(documents: List<MediaSearchDocument>) {
            putBatchSizes += documents.size
            documents.forEach { this.documents[it.key] = it }
        }
        override suspend fun remove(keys: List<MediaKey>) {
            removeBatchSizes += keys.size
            keys.forEach(documents::remove)
        }
        override suspend fun purgeVolume(volumeName: String) {
            documents.keys.removeAll { it.volumeName == volumeName }
        }
        override suspend fun clear() { clearCount++; documents.clear() }
        override fun close() = Unit
    }

    private fun document(id: Long) = MediaSearchDocument(
        key = MediaKey("external_primary", id),
        kind = MediaKind.Image,
        mimeType = "image/jpeg",
        displayName = "$id.jpg",
        bucketName = "Camera",
        timelineSortMillis = id,
        generationModified = 1,
        favorite = false,
    )
}
