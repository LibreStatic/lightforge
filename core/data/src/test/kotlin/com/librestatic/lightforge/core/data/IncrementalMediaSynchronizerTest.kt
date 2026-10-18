package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MediaStoreCheckpointEntity
import com.librestatic.lightforge.core.mediastore.MediaStoreDeltaSource
import com.librestatic.lightforge.core.mediastore.MediaStoreGenerationPage
import com.librestatic.lightforge.core.mediastore.MediaStoreIdPage
import com.librestatic.lightforge.core.mediastore.MediaStoreRecord
import com.librestatic.lightforge.core.mediastore.MediaStoreRow
import com.librestatic.lightforge.core.mediastore.VolumeGeneration
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun rowHintOnHiddenRowKeepsIdentity() = runTest {
        val key = MediaKey("external_primary", 42)
        val store = FakeStore(checkpoint()).apply {
            items["external_primary" to 42] = record(42, 10).toEntity(1)
        }
        val source = FakeDeltaSource(emptyList(), hidden = setOf(key))
        val result = IncrementalMediaSynchronizer(source, store, ::fullAccess).applyRowHint(key)

        assertEquals(RowHintResult.Hidden, result)
        assertEquals(0, store.deleteCount)
        assertFalse(store.items.getValue("external_primary" to 42L).isAccessible)
        assertEquals(record(42, 10).toEntity(1).copy(isAccessible = false), store.items.getValue("external_primary" to 42L))
    }

    @Test
    fun hiddenRowIsNotDeletedEvenWithoutFullAccess() = runTest {
        val key = MediaKey("external_primary", 42)
        val store = FakeStore(checkpoint()).apply { items["external_primary" to 42] = record(42, 10).toEntity(1) }
        val result = IncrementalMediaSynchronizer(
            FakeDeltaSource(emptyList(), hidden = setOf(key)), store,
            { LibraryAccess(GrantLevel.Full, GrantLevel.None, false) },
        ).applyRowHint(key)
        assertEquals(RowHintResult.Hidden, result)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun reconcileFolderUpsertsReturnedRowsAndHidesTheMissingOnesUnderThePrefix() = runTest {
        fun inFolder(id: Long, path: String) = record(id, 10).copy(relativePath = path)
        val store = FakeStore(checkpoint()).apply {
            listOf(inFolder(1, "DCIM/Camera/"), inFolder(2, "DCIM/Camera/Sub/"), inFolder(3, "DCIM/Other/"), inFolder(4, "Pictures/"))
                .forEach { items["external_primary" to it.key.mediaStoreId] = it.toEntity(1) }
        }
        // Row 2 vanished from MediaStore (hidden or moved); row 5 is new; row 3 and 4 are outside.
        val source = FakeDeltaSource(listOf(inFolder(1, "DCIM/Camera/"), inFolder(5, "DCIM/Camera/")))
        val result = IncrementalMediaSynchronizer(source, store, ::fullAccess, pageSize = 1)
            .reconcileFolder("external_primary", "DCIM/Camera/")

        assertEquals(FolderReconcileResult.Complete(upserted = 2, hidden = 1), result)
        assertTrue(store.items.getValue("external_primary" to 1L).isAccessible)
        assertTrue(store.items.getValue("external_primary" to 5L).isAccessible)
        assertFalse(store.items.getValue("external_primary" to 2L).isAccessible)
        assertTrue(store.items.getValue("external_primary" to 3L).isAccessible)
        assertTrue(store.items.getValue("external_primary" to 4L).isAccessible)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun reconcileFolderWithoutAccessChangesNothing() = runTest {
        val store = FakeStore(checkpoint()).apply { items["external_primary" to 1] = record(1, 10).toEntity(1) }
        val result = IncrementalMediaSynchronizer(FakeDeltaSource(emptyList()), store, ::noAccess)
            .reconcileFolder("external_primary", "DCIM/Camera/")
        assertEquals(FolderReconcileResult.PermissionLost, result)
        assertTrue(store.items.getValue("external_primary" to 1L).isAccessible)
    }

    @Test
    fun reconcileFolderWaitsForTheRefreshLock() = runTest {
        val lock = Mutex(locked = true)
        val source = FakeDeltaSource(emptyList())
        val reconcile = async(start = CoroutineStart.UNDISPATCHED) {
            IncrementalMediaSynchronizer(source, FakeStore(checkpoint()), ::fullAccess, refreshMutex = lock)
                .reconcileFolder("external_primary", "DCIM/")
        }
        advanceUntilIdle()
        assertFalse(reconcile.isCompleted)
        assertEquals(0, source.folderQueries)
        lock.unlock()
        assertEquals(FolderReconcileResult.Complete(0, 0), reconcile.await())
    }

    @Test
    fun rowHintDoesNotWriteBackAStaleCheckpoint() = runTest {
        val newer = checkpoint().copy(generation = 12)
        val store = FakeStore(checkpoint()).apply {
            // A sync finishing while the hint is in flight advances the checkpoint.
            afterCheckpointRead = { checkpoint = newer }
        }
        val result = IncrementalMediaSynchronizer(FakeDeltaSource(listOf(record(42, 11))), store, ::fullAccess)
            .applyRowHint(MediaKey("external_primary", 42))

        assertEquals(RowHintResult.Upserted, result)
        assertEquals(newer, store.checkpoint)
        assertEquals(1, store.items.size)
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

    private class FakeDeltaSource(
        private val records: List<MediaStoreRecord>,
        private val hidden: Set<MediaKey> = emptySet(),
    ) : MediaStoreDeltaSource {
        var pageQueries = 0
        var folderQueries = 0
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

        override fun readRow(key: MediaKey): MediaStoreRow = when {
            key in hidden -> MediaStoreRow.Hidden
            else -> records.firstOrNull { it.key == key }?.let(MediaStoreRow::Present) ?: MediaStoreRow.Absent
        }

        override fun readFolderPage(
            volumeName: String,
            relativePathPrefix: String,
            afterId: Long,
            limit: Int,
        ): MediaStoreIdPage {
            folderQueries++
            val page = records.filter {
                it.key.volumeName == volumeName && it.relativePath.orEmpty().startsWith(relativePathPrefix) &&
                    it.key.mediaStoreId > afterId
            }.sortedBy { it.key.mediaStoreId }.take(limit)
            return MediaStoreIdPage(page, page.lastOrNull()?.key?.mediaStoreId)
        }
    }

    private class FakeStore(initial: MediaStoreCheckpointEntity) : IncrementalMediaIndexStore {
        var checkpoint: MediaStoreCheckpointEntity? = initial
        val items = linkedMapOf<Pair<String, Long>, MediaItemEntity>()
        var afterCommit: (() -> Unit)? = null
        var deleteCount = 0

        var afterCheckpointRead: (() -> Unit)? = null

        override suspend fun checkpoint(volumeName: String) = checkpoint.also { afterCheckpointRead?.invoke() }
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
        override suspend fun upsertHintedMedia(items: List<MediaItemEntity>) {
            items.forEach { this.items[it.volumeName to it.mediaStoreId] = it }
        }
        override suspend fun markMediaInaccessible(volumeName: String, id: Long): Int {
            val row = items[volumeName to id] ?: return 0
            items[volumeName to id] = row.copy(isAccessible = false)
            return 1
        }
        override suspend fun hideFolderRowsNotIn(
            volumeName: String, relativePathPrefix: String, seenIds: Set<Long>,
        ): Int {
            val missing = items.values.filter {
                it.volumeName == volumeName && it.isAccessible && it.mediaStoreId !in seenIds &&
                    it.relativePath.orEmpty().startsWith(relativePathPrefix)
            }
            missing.forEach { items[it.volumeName to it.mediaStoreId] = it.copy(isAccessible = false) }
            return missing.size
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
