package com.ugallery.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomViewerMediaSourceDeviceTest {
    private lateinit var database: GalleryDatabase
    private lateinit var source: RoomViewerMediaSource

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        source = RoomViewerMediaSource(database)
    }

    @After fun tearDown() = database.close()

    @Test fun timelineWindowKeepsSourceOrderAndSignalsBothBoundaries() = runBlocking {
        database.libraryDao().upsertMedia((1L..7L).map(::media))
        val window = source.window(MediaQuery(), timeline(4), radius = 2)

        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), window.items.map { it.key.mediaStoreId })
        assertTrue(window.hasPrevious)
        assertTrue(window.hasNext)
    }

    @Test fun oldestTimelineWindowKeepsSourceOrderAndNearestNeighbors() = runBlocking {
        database.libraryDao().upsertMedia((1L..7L).map(::media))
        val window = source.window(
            MediaQuery(sort = MediaQuery.Sort.OldestFirst),
            timeline(4),
            radius = 2,
        )

        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), window.items.map { it.key.mediaStoreId })
        assertTrue(window.hasPrevious)
        assertTrue(window.hasNext)
    }

    @Test fun oldestAlbumWindowAppliesScopeAndKindFilter() = runBlocking {
        database.libraryDao().upsertMedia(
            listOf(
                media(1),
                media(2).copy(mediaType = 3, mimeType = "video/mp4"),
                media(3),
                media(4).copy(bucketId = 99),
            ),
        )
        val query = MediaQuery(
            scope = MediaQuery.Scope.PhysicalAlbum("external_primary", 10),
            kindFilter = MediaQuery.KindFilter.Images,
            sort = MediaQuery.Sort.OldestFirst,
        )
        val window = source.window(query, timeline(3), radius = 5)

        assertEquals(listOf(1L, 3L), window.items.map { it.key.mediaStoreId })
        assertFalse(window.hasPrevious)
        assertFalse(window.hasNext)
    }

    private fun timeline(id: Long) = TimelineMedia(
        MediaKey("external_primary", id), MediaKind.Image, 1, id, 1, 1, 0,
    )

    private fun media(id: Long) = MediaItemEntity(
        volumeName = "external_primary", mediaStoreId = id, mediaType = 1,
        mimeType = "image/jpeg", displayName = "$id", sizeBytes = 1, width = 1, height = 1,
        durationMillis = 0, orientationDegrees = 0, dateTakenMillis = id,
        dateAddedSeconds = id, dateModifiedSeconds = id, timelineSortMillis = id,
        generationAdded = 1, generationModified = 1, bucketId = 10,
        bucketDisplayName = "Camera", relativePath = "DCIM/Camera/", isFavorite = false,
        isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )
}
