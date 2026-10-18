package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.ArchivedMediaEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderMediaDeviceTest {
    private lateinit var database: GalleryDatabase
    private lateinit var repository: GalleryAlbumRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        repository = GalleryAlbumRepository(database) { 1_000L }
    }

    @After
    fun closeDatabase() = database.close()

    private suspend fun ids(path: String, subfolders: Boolean): List<Long> {
        val source = FolderMediaPagingSource(database, "external_primary", path, subfolders)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.mediaStoreId }
    }

    @Test
    fun folderListingCountMatchesTheCardAndHonoursSubfoldersHiddenAndArchivedRows() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(
            listOf(
                media(1, 1_000, "DCIM/Camera/"),
                media(2, 3_000, "DCIM/Camera/"),
                media(3, 2_000, "DCIM/Camera/", accessible = false),
                media(4, 4_000, "DCIM/Camera/", trashed = true),
                media(5, 5_000, "DCIM/Camera/"),
                media(6, 6_000, "DCIM/Camera/Sub/"),
                media(7, 7_000, "DCIM/Cameras/"),
            ),
        )
        dao.upsertArchived(ArchivedMediaEntity("external_primary", 5, 1))

        assertEquals(listOf(2L, 1L), ids("DCIM/Camera/", subfolders = false))
        assertEquals(listOf(6L, 2L, 1L), ids("DCIM/Camera/", subfolders = true))

        val card = dao.folderSummaries().first().single { it.relativePath == "DCIM/Camera/" }
        assertEquals(2L, card.directCount)
        assertEquals(card.directCount, repository.folderMediaCount("external_primary", "DCIM/Camera/", false))
        assertEquals(3L, repository.folderMediaCount("external_primary", "DCIM/Camera/", true))
    }

    private fun media(
        id: Long,
        sort: Long,
        relativePath: String,
        accessible: Boolean = true,
        trashed: Boolean = false,
    ) = MediaItemEntity(
        volumeName = "external_primary",
        mediaStoreId = id,
        mediaType = 1,
        mimeType = "image/jpeg",
        displayName = "$id",
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
        bucketId = 99,
        bucketDisplayName = "Test",
        relativePath = relativePath,
        isFavorite = false,
        isTrashed = trashed,
        isAccessible = accessible,
        lastSeenScanId = 1,
    )
}
