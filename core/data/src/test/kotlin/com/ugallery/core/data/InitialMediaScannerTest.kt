package com.ugallery.core.data

import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.MediaStoreCheckpointEntity
import com.ugallery.core.mediastore.MediaStoreIdPage
import com.ugallery.core.mediastore.MediaStorePageSource
import com.ugallery.core.mediastore.MediaStoreRecord
import com.ugallery.core.mediastore.VolumeGeneration
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialMediaScannerTest {
    @Test
    fun interruptedPermissionResumesAfterCommittedCursorWithoutDuplicates() = runTest {
        val store = FakeStore()
        var access = fullAccess()
        val source = FakePages(listOf(record(1), record(2), record(3)))
        val scanner = scanner(source, store, { access }, pageSize = 2)

        val paused = scanner.scan(volume()) { access = noAccess() }
        assertEquals(InitialScanResult.PausedPermission(2), paused)
        assertEquals(2, store.items.size)
        assertEquals(2L, store.checkpoint("external_primary")?.lastScannedId)

        access = fullAccess()
        val completed = scanner.scan(volume())
        assertEquals(InitialScanResult.Complete(1), completed)
        assertEquals(listOf(1L, 2L, 3L), store.items.keys.map { it.second }.sorted())
        assertEquals(ScanState.Complete.name, store.checkpoint("external_primary")?.scanState)
    }

    @Test
    fun securityExceptionPausesWithoutAdvancingCheckpoint() = runTest {
        val store = FakeStore()
        val source = MediaStorePageSource { _, _, _ -> throw SecurityException("revoked") }

        val result = scanner(source, store, ::fullAccess).scan(volume())

        assertEquals(InitialScanResult.PausedPermission(0), result)
        assertEquals(-1L, store.checkpoint("external_primary")?.lastScannedId)
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun providerVersionChangeDropsOnlyThatRebuildableVolumeIndex() = runTest {
        val store = FakeStore().apply {
            checkpoint = checkpoint(version = "old", lastId = 5, scanState = ScanState.Running)
            items["external_primary" to 5L] = record(5).toEntity(1)
            items["sd-card" to 5L] = record(5, "sd-card").toEntity(1)
        }

        scanner(FakePages(emptyList()), store, ::fullAccess).scan(volume(version = "new"))

        assertEquals(setOf("sd-card" to 5L), store.items.keys)
        assertEquals("new", store.checkpoint?.providerVersion)
        assertEquals(1, store.resetCount)
    }

    @Test(expected = IllegalStateException::class)
    fun nonAdvancingProviderPageFailsInsteadOfLoopingForever() = runTest {
        val source = MediaStorePageSource { volume, _, _ ->
            MediaStoreIdPage(listOf(record(1, volume)), nextAfterId = -1)
        }
        scanner(source, FakeStore(), ::fullAccess).scan(volume())
    }

    @Test
    fun hundredThousandRowsStayBoundedToShortPageTransactions() = runTest {
        val store = FakeStore()
        val source = MediaStorePageSource { volume, afterId, limit ->
            val first = maxOf(1, afterId + 1)
            val lastExclusive = minOf(first + limit, 100_001)
            val page = if (first >= lastExclusive) emptyList() else
                (first until lastExclusive).map { record(it, volume) }
            MediaStoreIdPage(page, page.lastOrNull()?.key?.mediaStoreId)
        }

        val result = scanner(source, store, ::fullAccess, pageSize = 256).scan(volume())

        assertEquals(InitialScanResult.Complete(100_000), result)
        assertEquals(100_000, store.items.size)
        assertEquals(391, store.commitCount)
        assertTrue(store.largestCommit <= 256)
    }

    private fun scanner(
        source: MediaStorePageSource,
        store: FakeStore,
        access: () -> LibraryAccess,
        pageSize: Int = 2,
    ) = InitialMediaScanner(
        pages = source,
        store = store,
        currentAccess = access,
        scanId = { 99 },
        nowMillis = { 123_456 },
        pageSize = pageSize,
    )

    private class FakePages(private val records: List<MediaStoreRecord>) : MediaStorePageSource {
        override fun readIdPage(volumeName: String, afterId: Long, limit: Int): MediaStoreIdPage {
            val page = records.filter { it.key.volumeName == volumeName && it.key.mediaStoreId > afterId }
                .sortedBy { it.key.mediaStoreId }
                .take(limit)
            return MediaStoreIdPage(page, page.lastOrNull()?.key?.mediaStoreId)
        }
    }

    private class FakeStore : MediaIndexStore {
        var checkpoint: MediaStoreCheckpointEntity? = null
        val items = linkedMapOf<Pair<String, Long>, MediaItemEntity>()
        var resetCount = 0
        var commitCount = 0
        var largestCommit = 0

        override suspend fun checkpoint(volumeName: String) = checkpoint?.takeIf {
            it.volumeName == volumeName
        }

        override suspend fun resetVolumeForScan(checkpoint: MediaStoreCheckpointEntity) {
            items.keys.removeAll { it.first == checkpoint.volumeName }
            this.checkpoint = checkpoint
            resetCount++
        }

        override suspend fun commitMediaPage(
            items: List<MediaItemEntity>,
            checkpoint: MediaStoreCheckpointEntity,
        ) {
            items.forEach { this.items[it.volumeName to it.mediaStoreId] = it }
            this.checkpoint = checkpoint
            commitCount++
            largestCommit = maxOf(largestCommit, items.size)
        }

        override suspend fun upsertCheckpoint(checkpoint: MediaStoreCheckpointEntity) {
            this.checkpoint = checkpoint
        }

    }

    private companion object {
        fun volume(version: String = "v1") = VolumeGeneration("external_primary", version, 10)

        fun checkpoint(version: String, lastId: Long, scanState: ScanState) =
            MediaStoreCheckpointEntity(
                "external_primary", version, 10, lastId, 77, scanState.name, null,
            )

        fun fullAccess() = LibraryAccess(GrantLevel.Full, GrantLevel.Full, false)
        fun noAccess() = LibraryAccess(GrantLevel.None, GrantLevel.None, false)

        fun record(id: Long, volume: String = "external_primary") = MediaStoreRecord(
            key = MediaKey(volume, id),
            kind = MediaKind.Image,
            mimeType = "image/jpeg",
            displayName = "$id.jpg",
            sizeBytes = 10,
            width = 2,
            height = 2,
            durationMillis = 0,
            orientationDegrees = 0,
            dateTakenMillis = null,
            dateAddedSeconds = id,
            dateModifiedSeconds = id,
            generationAdded = 1,
            generationModified = 1,
            bucketId = 1,
            bucketDisplayName = "Camera",
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }
}
