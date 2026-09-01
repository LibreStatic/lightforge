package com.ugallery.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumPagingSource
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.database.AlbumTarget
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.AlbumAvailability
import com.ugallery.core.model.AlbumKey
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryAlbumRepositoryDeviceTest {
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

    @Test
    fun physicalFolderOptionsExposeRelativePathsForHierarchy() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(listOf(media("external_primary", 7, 7_000, relativePath = "Pictures/Family/")))

        val option = dao.physicalAlbumOptions().first().single()

        assertEquals("Pictures/Family/", option.relativePath)
        assertEquals("external_primary", option.volumeName)
        assertEquals(BucketId, option.bucketId)
    }

    @Test
    fun virtualAlbumCrudStoresReferencesWithoutMovingMedia() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(listOf(media("external_primary", 7, 7_000, relativePath = "DCIM/Camera/")))

        val albumId = repository.createVirtualAlbum("  Road   trip  ")
        assertEquals("Road trip", dao.virtualAlbum(albumId)?.name)
        assertEquals(1, repository.addToVirtualAlbum(albumId, listOf(MediaKey("external_primary", 7))))
        assertEquals(0, repository.addToVirtualAlbum(albumId, listOf(MediaKey("external_primary", 7))))
        assertEquals(1, dao.virtualAlbumMediaCount(albumId))
        assertEquals("DCIM/Camera/", dao.media("external_primary", 7)?.relativePath)

        assertTrue(repository.renameVirtualAlbum(albumId, "Holiday"))
        assertEquals("Holiday", dao.virtualAlbum(albumId)?.name)
        assertTrue(repository.removeFromVirtualAlbum(albumId, MediaKey("external_primary", 7)))
        assertEquals(0, dao.virtualAlbumMediaCount(albumId))
        assertTrue(repository.deleteVirtualAlbum(albumId))
        assertNull(dao.virtualAlbum(albumId))
    }

    @Test
    fun unavailablePhysicalVolumeIsExplicitAndProducesNoContent() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(listOf(media("1a2b-3c4d", 1, 1_000)))
        val key = AlbumKey.Physical("1a2b-3c4d", BucketId)
        assertEquals(AlbumAvailability.Available, repository.physicalAvailability(key))

        dao.markVolumeInaccessible("1a2b-3c4d")

        assertEquals(AlbumAvailability.VolumeUnavailable, repository.physicalAvailability(key))
        val page = AlbumPagingSource(
            database,
            AlbumTarget.Physical("1a2b-3c4d", BucketId),
            AlbumMediaFilter.All,
            AlbumSort.NewestFirst,
        ).refresh(20)
        assertTrue(page.data.isEmpty())
    }

    @Test
    fun physicalAlbumPagingFiltersAndSortsWithoutOffset() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(
            listOf(
                media("external_primary", 1, 1_000, mediaType = 1),
                media("external_primary", 2, 2_000, mediaType = 3),
                media("external_primary", 3, 3_000, mediaType = 1),
            ),
        )
        val newestImages = AlbumPagingSource(
            database,
            AlbumTarget.Physical("external_primary", BucketId),
            AlbumMediaFilter.Images,
            AlbumSort.NewestFirst,
        ).refresh(1)
        val next = AlbumPagingSource(
            database,
            AlbumTarget.Physical("external_primary", BucketId),
            AlbumMediaFilter.Images,
            AlbumSort.NewestFirst,
        ).append(requireNotNull(newestImages.nextKey), 1)
        val oldest = AlbumPagingSource(
            database,
            AlbumTarget.Physical("external_primary", BucketId),
            AlbumMediaFilter.All,
            AlbumSort.OldestFirst,
        ).refresh(3)

        assertEquals(listOf(3L, 1L), (newestImages.data + next.data).map { it.mediaStoreId })
        assertEquals(listOf(1L, 2L, 3L), oldest.data.map { it.mediaStoreId })
        assertFalse(newestImages.data.any { it.mediaType == 3 })
    }

    @Test
    fun hundredThousandItemAlbumStreamsBoundedKeysetPages() = runBlocking {
        val dao = database.libraryDao()
        var start = 0L
        while (start < 100_000L) {
            dao.upsertMedia(
                (start until start + 500L).map { id ->
                    media("external_primary", id, sort = id * 1_000L)
                },
            )
            start += 500L
        }

        val source = AlbumPagingSource(
            database,
            AlbumTarget.Physical("external_primary", BucketId),
            AlbumMediaFilter.All,
            AlbumSort.NewestFirst,
        )
        var page = source.refresh(500)
        var count = 0L
        var pages = 0
        while (true) {
            assertTrue(page.data.size <= 500)
            count += page.data.size
            pages++
            val next = page.nextKey ?: break
            page = source.append(next, 500)
        }

        assertEquals(100_000L, count)
        assertEquals(200, pages)
    }

    private suspend fun AlbumPagingSource.refresh(size: Int) = load(
        PagingSource.LoadParams.Refresh(key = null, loadSize = size, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page

    private suspend fun AlbumPagingSource.append(key: com.ugallery.core.database.TimelineKeyset, size: Int) = load(
        PagingSource.LoadParams.Append(key = key, loadSize = size, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page

    private fun media(
        volume: String,
        id: Long,
        sort: Long,
        mediaType: Int = 1,
        relativePath: String = "DCIM/Test/",
    ) = MediaItemEntity(
        volumeName = volume,
        mediaStoreId = id,
        mediaType = mediaType,
        mimeType = if (mediaType == 3) "video/mp4" else "image/jpeg",
        displayName = "$id",
        sizeBytes = 100,
        width = 10,
        height = 10,
        durationMillis = if (mediaType == 3) 1_000 else 0,
        orientationDegrees = 0,
        dateTakenMillis = sort,
        dateAddedSeconds = sort / 1_000,
        dateModifiedSeconds = sort / 1_000,
        timelineSortMillis = sort,
        generationAdded = 1,
        generationModified = 1,
        bucketId = BucketId,
        bucketDisplayName = "Test",
        relativePath = relativePath,
        isFavorite = false,
        isTrashed = false,
        isAccessible = true,
        lastSeenScanId = 1,
    )

    private companion object {
        const val BucketId = 99L
    }
}
