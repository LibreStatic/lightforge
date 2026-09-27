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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeProfileRepositoryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: GalleryDatabase

    @Before fun setUp() { database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build() }
    @After fun tearDown() = database.close()

    @Test fun multipleReferencesBuildMatchesResumablyAndResetCascades() = runBlocking {
        val a = CompactFaceEmbedding.quantize(FloatArray(128) { if (it < 64) 1f else -1f })
        val b = CompactFaceEmbedding.quantize(FloatArray(128) { if (it < 64) -1f else 1f })
        listOf(1L to a, 2L to a, 3L to b).forEach { (id, vector) -> insert(id, vector) }
        val repository = MeProfileRepository(database, nowMillis = { 100 })
        repository.selectReferences(listOf(FaceIdentityKey(Volume, 1, 0), FaceIdentityKey(Volume, 2, 0)))
        assertEquals(MeProfileRepository.MeProfileState(2, false, 0), repository.state())
        assertTrue(!repository.rebuildMatchesChunk(2))
        assertTrue(repository.rebuildMatchesChunk(2))
        assertEquals(MeProfileRepository.MeProfileState(2, true, 2), repository.state())
        repository.reset()
        assertNull(repository.state())
        assertEquals(0, database.personDao().meMatchCount())
        assertEquals(0, database.personDao().meReferenceCount())
    }

    private suspend fun insert(id: Long, vector: ByteArray) {
        val library = database.libraryDao()
        library.upsertMedia(listOf(media(id)))
        library.replaceFaceDetection(
            FaceDetectionRunEntity(Volume, id, 1, Detector, 1, 1),
            listOf(DetectedFaceEntity(Volume, id, 0, Detector, 200, 200, 800, 800, 150, 150, 850, 850, 0f, 0f, 0f, 0.9f, "[]")),
        )
        library.upsertFaceEmbeddings(
            listOf(FaceEmbeddingEntity(Volume, id, 0, Detector, SFaceLiteRtEmbeddingInference.ModelVersion, vector, 1)),
        )
    }

    private fun media(id: Long) = MediaItemEntity(
        Volume, id, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, "image/jpeg", "$id.jpg",
        1, 512, 512, 0, 0, id, id, id, id, 1, 1,
        1, "Test", "Pictures/Test/", false, false, true, 1,
    )

    private companion object { const val Volume = "external_primary"; const val Detector = "detector-v1" }
}
