package com.librestatic.lightforge.core.search

import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
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

    @Test fun `drain puts present documents removes absent ones and acknowledges only the snapshot`() = runTest {
        val index = FakeIndex().apply { put(listOf(document(2), document(3))) }
        val journal = FakeJournal(eligible = mapOf(1L to document(1), 3L to document(3)))
        journal.dirty(1, 2, 3, 3)
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), journal)

        assertEquals(3, coordinator.drain())

        assertEquals(setOf(1L, 3L), index.documents.keys.map { it.mediaStoreId }.toSet())
        assertTrue(journal.entries.isEmpty())
    }

    @Test fun `a key dirtied during the drain keeps its newer journal row`() = runTest {
        val index = FakeIndex()
        val journal = FakeJournal(eligible = (1L..2L).associateWith(::document))
        journal.dirty(1, 2)
        journal.onDocuments = { journal.dirty(2) }
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), journal)

        coordinator.drain()

        assertEquals(listOf(2L), journal.entries.map { it.second })
        assertTrue(journal.entries.single().first > 2)
    }

    @Test fun `a crash between put and acknowledge is redone idempotently`() = runTest {
        val index = FakeIndex()
        val journal = FakeJournal(eligible = (1L..3L).associateWith(::document))
        journal.dirty(1, 2, 3)
        journal.failAcknowledgeOnce = true
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), journal)

        runCatching { coordinator.drain() }
        assertEquals(3, journal.entries.size)
        assertEquals(3, index.documents.size)

        assertEquals(3, coordinator.drain())
        assertTrue(journal.entries.isEmpty())
        assertEquals(3, index.documents.size)
    }

    @Test fun `empty journal does not touch the index`() = runTest {
        val index = FakeIndex()
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), FakeJournal())

        assertEquals(0, coordinator.drain())
        assertTrue(index.putBatchSizes.isEmpty() && index.removeBatchSizes.isEmpty())
    }

    @Test fun `large journals drain in bounded batches`() = runTest {
        val index = FakeIndex()
        val journal = FakeJournal(eligible = (0L until 1_100L).associateWith(::document))
        journal.dirty(*LongArray(1_100) { it.toLong() })
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), journal)

        coordinator.drain()

        assertEquals(listOf(500, 500, 100), index.putBatchSizes)
    }

    @Test fun `matching count after a drain does not rebuild`() = runTest {
        val index = FakeIndex()
        val journal = FakeJournal(eligible = mapOf(1L to document(1)))
        journal.dirty(1)
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), journal)

        val result = coordinator.synchronize(verifyCount = true)

        assertEquals(SearchSyncResult(1, null, verified = true), result)
        assertEquals(0, index.clearCount)
    }

    @Test fun `count mismatch triggers a rebuild that clears the journal through its snapshot`() = runTest {
        val index = FakeIndex().apply { put(listOf(document(1), document(99))) }
        val journal = FakeJournal(eligible = mapOf(1L to document(1)))
        val source = SearchDocumentSource { after, limit ->
            journal.eligible.values.filter { after == null || it.key.mediaStoreId > after.mediaStoreId }.take(limit)
        }
        val reasons = mutableListOf<String>()
        val coordinator = SearchIndexCoordinator(index, source, FakeState(), journal)

        val result = coordinator.synchronize(verifyCount = true) { reasons += it }

        assertTrue(result.rebuildReason!!.contains("count"))
        assertEquals(1, reasons.size)
        assertEquals(1, index.clearCount)
        assertEquals(setOf(1L), index.documents.keys.map { it.mediaStoreId }.toSet())
    }

    @Test fun `count is not verified unless asked`() = runTest {
        val index = FakeIndex().apply { put(listOf(document(99))) }
        val coordinator = SearchIndexCoordinator(index, emptySource, FakeState(), FakeJournal())

        assertEquals(SearchSyncResult(0, null), coordinator.synchronize(verifyCount = false))
    }

    @Test fun `index failure resets the index and rebuilds`() = runTest {
        val index = FakeIndex().apply { failNextPut = true }
        val journal = FakeJournal(eligible = mapOf(1L to document(1)))
        journal.dirty(1)
        val source = SearchDocumentSource { after, limit ->
            journal.eligible.values.filter { after == null || it.key.mediaStoreId > after.mediaStoreId }.take(limit)
        }
        val state = FakeState()
        val coordinator = SearchIndexCoordinator(index, source, state, journal)

        val result = coordinator.synchronize(verifyCount = false)

        assertTrue(result.rebuildReason!!.startsWith("incremental update failed"))
        assertEquals(1, index.clearCount)
        assertEquals(setOf(1L), index.documents.keys.map { it.mediaStoreId }.toSet())
        assertTrue(journal.entries.isEmpty())
        assertEquals(null, state.value)
    }

    @Test fun `rebuild keeps changes made after its snapshot`() = runTest {
        val index = FakeIndex()
        val journal = FakeJournal(eligible = mapOf(1L to document(1)))
        journal.dirty(1)
        var calls = 0
        val source = SearchDocumentSource { _, _ ->
            if (calls++ == 0) { journal.dirty(7); journal.eligible.values.toList() } else emptyList()
        }
        SearchIndexCoordinator(index, source, FakeState(), journal).rebuild(restart = true)

        assertEquals(listOf(7L), journal.entries.map { it.second })
    }

    @Test fun `resumed rebuild leaves the journal alone`() = runTest {
        val journal = FakeJournal()
        journal.dirty(1)
        val state = FakeState(SearchRebuildCheckpoint(MediaKey("external_primary", 5), 5))
        SearchIndexCoordinator(FakeIndex(), emptySource, state, journal).rebuild()

        assertEquals(1, journal.entries.size)
    }

    private val emptySource = SearchDocumentSource { _, _ -> emptyList() }

    private class FakeJournal(val eligible: Map<Long, MediaSearchDocument> = emptyMap()) : SearchIndexJournal {
        val entries = mutableListOf<Pair<Long, Long>>()
        private var seq = 0L
        var failAcknowledgeOnce = false
        var onDocuments: () -> Unit = {}
        fun dirty(vararg ids: Long) { ids.forEach { entries += ++seq to it } }
        override suspend fun maxSeq() = entries.maxOfOrNull { it.first } ?: 0L
        override suspend fun keys(throughSeq: Long, limit: Int) = entries.filter { it.first <= throughSeq }
            .map { it.second }.distinct().sorted().take(limit).map { MediaKey("external_primary", it) }
        override suspend fun documents(keys: List<MediaKey>): List<MediaSearchDocument> {
            onDocuments(); onDocuments = {}
            return keys.mapNotNull { eligible[it.mediaStoreId] }
        }
        override suspend fun acknowledge(throughSeq: Long, keys: List<MediaKey>) {
            if (failAcknowledgeOnce) { failAcknowledgeOnce = false; error("crash") }
            val ids = keys.map { it.mediaStoreId }.toSet()
            entries.removeAll { it.first <= throughSeq && it.second in ids }
        }
        override suspend fun clear(throughSeq: Long) { entries.removeAll { it.first <= throughSeq } }
        override suspend fun eligibleCount() = eligible.size
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
        var failNextPut = false
        override suspend fun documentCount() = documents.size
        override suspend fun ensureSchema(forceOverride: Boolean) = Unit
        override suspend fun put(documents: List<MediaSearchDocument>) {
            if (failNextPut) { failNextPut = false; error("put failed") }
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
