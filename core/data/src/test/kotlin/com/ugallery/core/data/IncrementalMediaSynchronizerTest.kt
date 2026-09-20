package com.ugallery.core.data

import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.MediaStoreCheckpointEntity
import com.ugallery.core.mediastore.MediaStoreDeltaSource
import com.ugallery.core.mediastore.MediaStoreGenerationPage
import com.ugallery.core.mediastore.MediaStoreRecord
import com.ugallery.core.mediastore.VolumeGeneration
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncrementalMediaSynchronizerTest {
    @Test
    fun newObserverReconcilesMissedChangesButDoesNotScanUnchangedVolumes() = runTest {
        val source = FakeDeltaSource(emptyList())
        val store = FakeStore(checkpoint())
        val sync = IncrementalMediaSynchronizer(source, store, ::fullAccess)
        assertEquals(IncrementalSyncResult.NeedsFullVolumeReconciliation,
            sync.sync(volume(11), reconcileUnobservedChanges = true))
        assertEquals(10L, store.checkpoint!!.generation)
        assertEquals(0, source.pageQueries)
        assertEquals(IncrementalSyncResult.NoChange,
            sync.sync(volume(10), reconcileUnobservedChanges = true))
        assertEquals(0, source.pageQueries)
    }

    @Test
    fun absentRowUnderPartialAccessDoesNotEraseSavedIdentity() = runTest {
        val store = FakeStore(checkpoint()).apply {
            items["external_primary" to 42] = record(42, 10).toEntity(1)
        }
        val result = IncrementalMediaSynchronizer(FakeDeltaSource(emptyList()), store,
            { LibraryAccess(GrantLevel.Full, GrantLevel.None, false) })
            .applyRowHint(MediaKey("external_primary", 42))
        assertEquals(RowHintResult.PermissionLost, result)
        assertEquals(0, store.deleteCount)
        assertEquals(1, store.items.size)
    }

    @Test
    fun sameGenerationRowsResumeByIdAndKeepOriginalTarget() = runTest {
        var access = fullAccess()
        val source = FakeDeltaSource(
            listOf(record(1, 11), record(2, 11), record(3, 11), record(4, 12), record(5, 13)),
        )
        val store = FakeStore(checkpoint()).apply { afterCommit = { access = noAccess() } }
        val sync = IncrementalMediaSynchronizer(source, store, { access }, pageSize = 2)

        assertEquals(IncrementalSyncResult.PausedPermission(2), sync.sync(volume(12)))
        assertEquals(11L, store.checkpoint!!.deltaGenerationCursor)
        assertEquals(2L, store.checkpoint!!.deltaMediaStoreIdCursor)
        assertEquals(12L, store.checkpoint!!.deltaTargetGeneration)

        access = fullAccess()
        store.afterCommit = null
        assertEquals(IncrementalSyncResult.Complete(2), sync.sync(volume(13)))
        assertEquals(setOf(1L, 2L, 3L, 4L), store.items.keys.map { it.second }.toSet())
        assertEquals(12L, store.checkpoint!!.generation)
        assertNull(store.checkpoint!!.deltaTargetGeneration)

        assertEquals(IncrementalSyncResult.Complete(1), sync.sync(volume(13)))
        assertEquals(13L, store.checkpoint!!.generation)
    }

    @Test
    fun rowHintDeletesMissingExternalItem() = runTest {
        val store = FakeStore(checkpoint()).apply {
            items["external_primary" to 42] = record(42, 10).toEntity(1)
        }
        val source = FakeDeltaSource(emptyList())
        val result = IncrementalMediaSynchronizer(source, store, ::fullAccess)
            .applyRowHint(MediaKey("external_primary", 42))

        assertEquals(RowHintResult.Deleted, result)
        assertEquals(1, store.deleteCount)
        assertEquals(0, store.items.size)
    }

    @Test
    fun providerVersionChangeRequiresVolumeReconciliation() = runTest {
        val result = IncrementalMediaSynchronizer(
            FakeDeltaSource(emptyList()), FakeStore(checkpoint()), ::fullAccess,
        ).sync(volume(11, version = "v2"))
        assertEquals(IncrementalSyncResult.NeedsFullVolumeReconciliation, result)
    }

    @Test
    fun equalGenerationDoesNoProviderQuery() = runTest {
        val source = FakeDeltaSource(emptyList())
        val result = IncrementalMediaSynchronizer(source, FakeStore(checkpoint()), ::fullAccess)
            .sync(volume(10))
        assertEquals(IncrementalSyncResult.NoChange, result)
        assertEquals(0, source.pageQueries)
    }

    private class FakeDeltaSource(private val records: List<MediaStoreRecord>) : MediaStoreDeltaSource {
        var pageQueries = 0
        override fun readGenerationPage(
            volumeName: String,
            afterGeneration: Long,
            afterId: Long,
            throughGeneration: Long,
            limit: Int,
        ): MediaStoreGenerationPage {
            pageQueries++
            val page = records.filter {
                it.key.volumeName == volumeName && it.generationModified <= throughGeneration &&
                    (it.generationModified > afterGeneration ||
                        it.generationModified == afterGeneration && it.key.mediaStoreId > afterId)
            }.sortedWith(compareBy(MediaStoreRecord::generationModified).thenBy { it.key.mediaStoreId })
                .take(limit)
            val last = page.lastOrNull()
            return MediaStoreGenerationPage(page, last?.generationModified, last?.key?.mediaStoreId)
        }

        override fun readOne(key: MediaKey): MediaStoreRecord? = records.firstOrNull { it.key == key }
    }

    private class FakeStore(initial: MediaStoreCheckpointEntity) : IncrementalMediaIndexStore {
        var checkpoint: MediaStoreCheckpointEntity? = initial
        val items = linkedMapOf<Pair<String, Long>, MediaItemEntity>()
        var afterCommit: (() -> Unit)? = null
        var deleteCount = 0

        override suspend fun checkpoint(volumeName: String) = checkpoint
        override suspend fun resetVolumeForScan(checkpoint: MediaStoreCheckpointEntity) {
            items.keys.removeAll { it.first == checkpoint.volumeName }
            this.checkpoint = checkpoint
        }
        override suspend fun commitMediaPage(
            items: List<MediaItemEntity>, checkpoint: MediaStoreCheckpointEntity,
        ) {
            items.forEach { this.items[it.volumeName to it.mediaStoreId] = it }
            this.checkpoint = checkpoint
            afterCommit?.invoke()
        }
        override suspend fun upsertCheckpoint(checkpoint: MediaStoreCheckpointEntity) {
            this.checkpoint = checkpoint
        }

        override suspend fun reconcileMediaPage(
            items: List<MediaItemEntity>,
            checkpoint: MediaStoreCheckpointEntity,
        ) = commitMediaPage(items, checkpoint)

        override suspend fun completeScan(checkpoint: MediaStoreCheckpointEntity, scanId: Long) {
            this.checkpoint = checkpoint
        }
        override suspend fun deleteMedia(volumeName: String, id: Long): Int {
            deleteCount++
            return if (items.remove(volumeName to id) != null) 1 else 0
        }
    }

    private companion object {
        fun checkpoint() = MediaStoreCheckpointEntity(
            "external_primary", "v1", 10, 100, null, ScanState.Complete.name, 1,
        )
        fun volume(generation: Long, version: String = "v1") =
            VolumeGeneration("external_primary", version, generation)
        fun fullAccess() = LibraryAccess(GrantLevel.Full, GrantLevel.Full, false)
        fun noAccess() = LibraryAccess(GrantLevel.None, GrantLevel.None, false)
        fun record(id: Long, generation: Long) = MediaStoreRecord(
            MediaKey("external_primary", id), MediaKind.Image, "image/jpeg", "$id.jpg", 1,
            1, 1, 0, 0, generation * 1_000, generation, generation, generation, generation,
            1, "Camera", "DCIM/Camera/", false, false,
        )
    }
}
