package com.ugallery.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GalleryDatabaseDeviceTest {
    private lateinit var database: GalleryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        dao = database.libraryDao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun compositeIdentityKeepsSameIdOnDifferentVolumes() = runBlocking {
        dao.upsertMedia(
            listOf(
                media("external_primary", id = 42, sort = 1_000),
                media("1234-5678", id = 42, sort = 1_000),
            ),
        )

        assertEquals(2, dao.mediaCount())
        assertNotNull(dao.media("external_primary", 42))
        assertNotNull(dao.media("1234-5678", 42))
    }

    @Test
    fun keysetUsesVolumeAsFinalTieBreakerWithoutSkippingRows() = runBlocking {
        val rows = listOf(
            media("volume-c", id = 7, sort = 2_000),
            media("volume-b", id = 7, sort = 2_000),
            media("volume-a", id = 7, sort = 2_000),
            media("external_primary", id = 99, sort = 1_000),
        )
        dao.upsertMedia(rows)

        val first = dao.firstTimelinePage(2)
        val cursor = first.last()
        val second = dao.timelinePageAfter(
            cursor.timelineSortMillis,
            cursor.mediaStoreId,
            cursor.volumeName,
            2,
        )

        assertEquals(rows.size, (first + second).map { it.volumeName to it.mediaStoreId }.distinct().size)
        assertEquals(listOf("volume-c", "volume-b", "volume-a", "external_primary"),
            (first + second).map(MediaItemEntity::volumeName))
    }

    @Test
    fun mediaPageAndCheckpointCommitTogether() = runBlocking {
        val checkpoint = MediaStoreCheckpointEntity(
            volumeName = "external_primary",
            providerVersion = "v1",
            generation = 10,
            lastScannedId = 42,
            activeScanId = 1,
            scanState = "RUNNING",
            lastSuccessfulSyncMillis = null,
        )
        dao.commitMediaPage(listOf(media("external_primary", 42, 1_000)), checkpoint)

        assertEquals(1, dao.mediaCount())
        assertEquals(checkpoint, dao.checkpoint("external_primary"))
    }

    @Test
    fun timelineQueryUsesKeysetIndex() {
        val cursor = database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM media_items " +
                "WHERE isAccessible = 1 AND isTrashed = 0 " +
                "ORDER BY timelineSortMillis DESC, mediaStoreId DESC, volumeName DESC LIMIT 100",
        )
        val details = cursor.use {
            buildList {
                while (it.moveToNext()) add(it.getString(3))
            }
        }
        assertTrue(details.joinToString().contains("index_media_timeline_keyset"))
    }

    @Test
    fun pagingSourceUsesBoundedCompositeKeysetPages() = runBlocking {
        dao.upsertMedia(
            listOf(
                media("volume-c", 7, 2_000),
                media("volume-b", 7, 2_000),
                media("volume-a", 7, 2_000),
                media("external_primary", 99, 1_000),
            ),
        )
        val source = TimelinePagingSource(database)
        val first = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 2,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page
        val second = source.load(
            PagingSource.LoadParams.Append(
                key = requireNotNull(first.nextKey),
                loadSize = 2,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page

        assertEquals(
            listOf("volume-c", "volume-b", "volume-a", "external_primary"),
            (first.data + second.data).map(MediaItemEntity::volumeName),
        )
    }

    @Test
    fun exportedVersionOneMigratesToCurrentSchemaAndValidates() {
        val name = "migration-v1.db"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GalleryDatabase::class.java,
        )
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(
            name,
            3,
            true,
            GalleryDatabaseFactory.Migration1To2,
            GalleryDatabaseFactory.Migration2To3,
        ).close()
    }

    @Test
    fun corruptRebuildableIndexIsRecovered() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "corrupt-recovery.db"
        context.deleteDatabase(name)
        File(context.getDatabasePath(name).absolutePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4_096) { 0x5a })
        }

        val recovered = GalleryDatabaseFactory.open(context, name)
        try {
            assertEquals(0, recovered.libraryDao().mediaCount())
        } finally {
            recovered.close()
            context.deleteDatabase(name)
        }
    }

    private fun media(volume: String, id: Long, sort: Long) = MediaItemEntity(
        volumeName = volume,
        mediaStoreId = id,
        mediaType = 1,
        mimeType = "image/jpeg",
        displayName = "$volume-$id.jpg",
        sizeBytes = 100,
        width = 10,
        height = 10,
        durationMillis = 0,
        orientationDegrees = 0,
        dateTakenMillis = sort,
        dateAddedSeconds = sort / 1_000,
        dateModifiedSeconds = sort / 1_000,
        timelineSortMillis = sort,
        generationAdded = 1,
        generationModified = 1,
        bucketId = 1,
        bucketDisplayName = "Camera",
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
        isAccessible = true,
        lastSeenScanId = 1,
    )
}
