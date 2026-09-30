package com.librestatic.lightforge.core.ml

import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.database.DuplicateHashEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.SimilarityFeatureEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupRepositoryDeviceTest {
    private lateinit var database: GalleryDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GalleryDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test fun summaryIsDerivedAndUpdatesAfterTrashWithoutDeletingMedia() = runBlocking {
        val dao = database.libraryDao()
        val items = listOf(
            media(1).copy(sizeBytes = 1_000),
            media(2).copy(sizeBytes = 1_000),
            media(3, type = 3).copy(sizeBytes = 150L * 1_024 * 1_024),
            media(4).copy(bucketDisplayName = "Screenshots"),
            media(5),
        )
        dao.upsertMedia(items)
        val sha = "b".repeat(64)
        dao.upsertDuplicateHashes(listOf(1L, 2L).map { id ->
            DuplicateHashEntity("external_primary", id, 1, 1_000, ExactDuplicateMlEngine.HashVersion, "s", sha, 1)
        })
        dao.upsertSimilarityFeature(
            SimilarityFeatureEntity(
                "external_primary", 5, 1, SimilarityMlEngine.AlgorithmVersion, 0, ByteArray(48),
                0, 0, 0, 0, 3f, 1,
            ),
        )
        val repository = CleanupRepository(database)
        val initial = repository.summary.first()
        assertEquals(1, initial.exactGroupCount)
        assertEquals(1_000, initial.exactRecoverableBytes)
        assertEquals(1, initial.largeVideoCount)
        assertEquals(1, initial.screenshotCount)
        assertEquals(1, initial.blurryCandidateCount)
        assertEquals(5, dao.mediaCount())

        dao.upsertMedia(listOf(items[1].copy(isTrashed = true)))
        val updated = repository.summary.first()
        assertEquals(0, updated.exactGroupCount)
        assertEquals(0, updated.exactRecoverableBytes)
        assertEquals(5, dao.mediaCount())
    }

    private fun media(id: Long, type: Int = 1) = MediaItemEntity(
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        mediaStoreId = id,
        mediaType = type,
        mimeType = if (type == 3) "video/mp4" else "image/jpeg",
        displayName = "$id",
        sizeBytes = 10,
        width = 100,
        height = 100,
        durationMillis = 0,
        orientationDegrees = 0,
        dateTakenMillis = id,
        dateAddedSeconds = id,
        dateModifiedSeconds = id,
        timelineSortMillis = id,
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
