package com.librestatic.lightforge.core.ml

import android.content.Context
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.database.DetectedFaceEntity
import com.librestatic.lightforge.core.database.FaceDetectionRunEntity
import com.librestatic.lightforge.core.database.FaceEmbeddingEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonClusteringEngineDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: GalleryDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
    }

    @After fun tearDown() = database.close()

    @Test fun conservativeClustersAndManualCorrectionsSurviveRecluster() = runBlocking {
        val library = database.libraryDao()
        val person = database.personDao()
        val a = CompactFaceEmbedding.quantize(FloatArray(128) { if (it < 64) 1f else -1f })
        val b = CompactFaceEmbedding.quantize(FloatArray(128) { if (it < 64) -1f else 1f })
        listOf(1L to a, 2L to a, 3L to b).forEach { (id, vector) ->
            library.upsertMedia(listOf(media(id)))
            library.replaceFaceDetection(
                FaceDetectionRunEntity(Volume, id, 1, DetectorVersion, 1, 1),
                listOf(face(id)),
            )
            library.upsertFaceEmbeddings(
                listOf(FaceEmbeddingEntity(Volume, id, 0, DetectorVersion, SFaceLiteRtEmbeddingInference.ModelVersion, vector, 1)),
            )
        }
        val engine = PersonClusteringMlEngine(database, { true }, nowMillis = { 10 })
        assertEquals(MlChunkOutcome.Complete(3), engine.process(null, 10))
        val aCluster = requireNotNull(person.membership(Volume, 1, 0)).clusterId
        assertEquals(aCluster, person.membership(Volume, 2, 0)?.clusterId)
        val bCluster = requireNotNull(person.membership(Volume, 3, 0)).clusterId
        assertNotEquals(aCluster, bCluster)

        val corrections = PersonCorrectionRepository(database, nowMillis = { 20 })
        corrections.rename(aCluster, "Alice")
        corrections.hide(aCluster, true)
        corrections.merge(aCluster, listOf(bCluster))
        assertEquals(aCluster, person.membership(Volume, 3, 0)?.clusterId)
        val splitCluster = corrections.split(aCluster, listOf(FaceIdentityKey(Volume, 3, 0)))
        assertNotEquals(aCluster, splitCluster)
        assertEquals(splitCluster, person.membership(Volume, 3, 0)?.clusterId)

        person.purgeMemberships()
        assertEquals(MlChunkOutcome.Complete(3), engine.process(null, 10))
        assertEquals(aCluster, person.membership(Volume, 1, 0)?.clusterId)
        assertEquals(aCluster, person.membership(Volume, 2, 0)?.clusterId)
        assertEquals(splitCluster, person.membership(Volume, 3, 0)?.clusterId)
        val restored = requireNotNull(person.cluster(aCluster))
        assertEquals("Alice", restored.displayName)
        assertTrue(restored.isHidden)
        assertTrue(restored.isUserEdited)
        assertFalse(requireNotNull(person.cluster(splitCluster)).displayName?.isNotEmpty() == true)
        purgeAllPersonIdentityData(database)
        assertEquals(null, person.cluster(aCluster))
        assertEquals(null, person.cluster(splitCluster))
        assertEquals(0, database.libraryDao().faceEmbeddingCount())
    }

    private fun media(id: Long) = MediaItemEntity(
        volumeName = Volume, mediaStoreId = id,
        mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
        mimeType = "image/jpeg", displayName = "$id.jpg", bucketId = 1,
        bucketDisplayName = "Test", relativePath = "Pictures/Test/",
        dateTakenMillis = id, dateAddedSeconds = id, dateModifiedSeconds = id,
        generationAdded = 1, generationModified = 1, timelineSortMillis = id,
        width = 512, height = 512, durationMillis = 0, sizeBytes = 1,
        orientationDegrees = 0, isFavorite = false, isTrashed = false,
        isAccessible = true, lastSeenScanId = 1,
    )

    private fun face(id: Long) = DetectedFaceEntity(
        Volume, id, 0, DetectorVersion,
        200, 200, 800, 800, 150, 150, 850, 850,
        0f, 0f, 0f, 0.9f, "[]",
    )

    private companion object {
        const val Volume = "external_primary"
        const val DetectorVersion = "detector-v1"
    }
}
