package com.librestatic.lightforge.core.ml

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.LabelSuppressionEntity
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MediaLabelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetCollectionsDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: GalleryDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        PetCollectionSettings(context).setEnabled(false)
    }

    @After fun tearDown() {
        PetCollectionSettings(context).setEnabled(false)
        database.close()
    }

    @Test fun petCountsAreTypedAccessibleAndSuppressibleWithoutIdentity() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertMedia(
            listOf(
                media(1, accessible = true),
                media(2, accessible = true),
                media(3, accessible = false),
            ),
        )
        dao.upsertLabels(
            listOf(
                label(1, "dog"), label(1, "dog"),
                label(2, "cat"), label(3, "dog"),
            ),
        )
        val repository = PetCollectionRepository(database)

        assertEquals(PetCollectionSummary(dogCount = 1, catCount = 1), repository.summary().first())
        dao.suppressLabel(LabelSuppressionEntity("dog", 1))
        assertEquals(PetCollectionSummary(dogCount = 0, catCount = 1), repository.summary().first())
    }

    @Test fun collectionVisibilityIsExplicitAndPersistent() {
        val settings = PetCollectionSettings(context)
        assertFalse(settings.isEnabled())
        settings.setEnabled(true)
        assertTrue(PetCollectionSettings(context).isEnabled())
        settings.setEnabled(false)
        assertFalse(settings.isEnabled())
    }

    private fun label(id: Long, canonical: String) = MediaLabelEntity(
        "external_primary", id, canonical, canonical, .9f, ImageLabelMlEngine.ModelVersion,
    )

    private fun media(id: Long, accessible: Boolean) = MediaItemEntity(
        volumeName = "external_primary", mediaStoreId = id, mediaType = 1,
        mimeType = "image/jpeg", displayName = "$id.jpg", sizeBytes = 100,
        width = 100, height = 100, durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = id, dateAddedSeconds = id, dateModifiedSeconds = id,
        timelineSortMillis = id, generationAdded = 1, generationModified = 1,
        bucketId = 1, bucketDisplayName = "Camera", relativePath = "DCIM/Camera/",
        isFavorite = false, isTrashed = false, isAccessible = accessible, lastSeenScanId = 1,
    )
}
