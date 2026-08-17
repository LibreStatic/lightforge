package com.ugallery.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.VirtualAlbumEntity
import com.ugallery.core.database.VirtualAlbumMediaEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSelectionTargetSourceDeviceTest {
    private lateinit var database: GalleryDatabase
    private lateinit var source: RoomSelectionTargetSource

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        source = RoomSelectionTargetSource(database)
    }

    @After fun tearDown() = database.close()

    @Test fun pagesByCompositeIdentityAndAppliesTypedFilters() = runBlocking {
        database.libraryDao().upsertMedia(
            listOf(
                media("external_primary", 2, MediaKind.Image, favorite = true),
                media("external_primary", 3, MediaKind.Video, favorite = true),
                media("sd-card", 1, MediaKind.Image, favorite = false),
                media("sd-card", 4, MediaKind.Image, favorite = true, trashed = true),
            ),
        )
        val query = MediaQuery(favoriteOnly = true)

        assertEquals(2, source.count(query))
        val first = source.page(query, null, 1)
        val second = source.page(query, first.single().key, 1)

        assertEquals(listOf(MediaKey("external_primary", 2)), first.map { it.key })
        assertEquals(listOf(MediaKey("external_primary", 3)), second.map { it.key })
        assertEquals(MediaKind.Video, second.single().kind)
        assertEquals(1, source.count(MediaQuery(trashedOnly = true)))
    }

    @Test fun virtualAlbumScopeReturnsOnlyMembershipRows() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(listOf(media("external_primary", 7, MediaKind.Image), media("sd", 8, MediaKind.Video)))
        val albumId = dao.insertVirtualAlbum(VirtualAlbumEntity(name = "Trip", normalizedName = "trip", createdAtMillis = 1, updatedAtMillis = 1))
        dao.addVirtualAlbumMedia(listOf(VirtualAlbumMediaEntity(albumId, "sd", 8, 1)))

        val query = MediaQuery(scope = MediaQuery.Scope.VirtualAlbum(albumId))
        assertEquals(listOf(MediaKey("sd", 8)), source.page(query, null, 500).map { it.key })
        assertEquals(1, source.count(query))
    }

    private fun media(
        volume: String,
        id: Long,
        kind: MediaKind,
        favorite: Boolean = false,
        trashed: Boolean = false,
    ) = MediaItemEntity(
        volumeName = volume, mediaStoreId = id, mediaType = if (kind == MediaKind.Video) 3 else 1,
        mimeType = if (kind == MediaKind.Video) "video/mp4" else "image/jpeg", displayName = "$id",
        sizeBytes = 1, width = 1, height = 1, durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = 1, dateAddedSeconds = 1, dateModifiedSeconds = 1, timelineSortMillis = id,
        generationAdded = 1, generationModified = 1, bucketId = 10, bucketDisplayName = "Camera",
        relativePath = "DCIM/Camera/", isFavorite = favorite, isTrashed = trashed,
        isAccessible = true, lastSeenScanId = 1,
    )
}
