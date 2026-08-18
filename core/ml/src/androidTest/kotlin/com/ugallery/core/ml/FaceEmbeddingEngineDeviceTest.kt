package com.ugallery.core.ml

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.face.FaceLandmark
import com.ugallery.core.database.DetectedFaceEntity
import com.ugallery.core.database.FaceDetectionRunEntity
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FaceEmbeddingEngineDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: GalleryDatabase
    private var fixture: Uri? = null

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
    }

    @After fun tearDown() {
        database.close()
        fixture?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun embeddingsAreCompactVersionedRebuildableAndCascadeWithDetection() = runBlocking {
        val uri = imageFixture()
        fixture = uri
        val volume = uri.pathSegments[0]
        val id = uri.lastPathSegment!!.toLong()
        database.libraryDao().upsertMedia(listOf(media(volume, id)))
        database.libraryDao().replaceFaceDetection(
            FaceDetectionRunEntity(volume, id, 1, "detector-v1", 1, 1),
            listOf(face(volume, id)),
        )
        val first = FaceEmbeddingMlEngine(
            context.contentResolver,
            database,
            { true },
            FaceEmbeddingInference { FloatArray(128) { index -> index + 1f } },
            embeddingModelVersion = "embedding-v1",
            nowMillis = { 10 },
        )
        first.use {
            assertEquals(MlChunkOutcome.Complete(1), it.process(null, 10))
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 10))
        }
        val storedV1 = database.libraryDao().faceEmbeddings(volume, id).single()
        assertEquals(128, storedV1.quantizedVector.size)
        assertEquals("embedding-v1", storedV1.embeddingModelVersion)

        FaceEmbeddingMlEngine(
            context.contentResolver,
            database,
            { true },
            FaceEmbeddingInference { FloatArray(128) { index -> 128f - index } },
            embeddingModelVersion = "embedding-v2",
            nowMillis = { 20 },
        ).use { assertEquals(MlChunkOutcome.Complete(1), it.process(null, 10)) }
        val storedV2 = database.libraryDao().faceEmbeddings(volume, id).single()
        assertEquals("embedding-v2", storedV2.embeddingModelVersion)
        assertTrue(!storedV1.quantizedVector.contentEquals(storedV2.quantizedVector))

        database.libraryDao().purgeFaceDetections()
        assertEquals(0, database.libraryDao().faceEmbeddingCount())
    }

    private fun imageFixture(): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "embedding-${UUID.randomUUID()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGallery-M4-Test")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(uri)!!.use { output ->
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.rgb(130, 90, 60))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun media(volume: String, id: Long) = MediaItemEntity(
        volumeName = volume,
        mediaStoreId = id,
        mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
        mimeType = "image/png",
        displayName = "embedding.png",
        bucketId = 1L,
        bucketDisplayName = "M4",
        relativePath = "Pictures/UGallery-M4-Test/",
        dateTakenMillis = 1,
        dateAddedSeconds = 1,
        dateModifiedSeconds = 1,
        generationAdded = 1,
        generationModified = 1,
        timelineSortMillis = 1,
        width = 512,
        height = 512,
        durationMillis = 0,
        sizeBytes = 1,
        orientationDegrees = 0,
        isFavorite = false,
        isTrashed = false,
        isAccessible = true,
        lastSeenScanId = 1,
    )

    private fun face(volume: String, id: Long): DetectedFaceEntity {
        val landmarks = JSONArray().apply {
            put(point(FaceLandmark.LEFT_EYE, 400, 400))
            put(point(FaceLandmark.RIGHT_EYE, 600, 400))
            put(point(FaceLandmark.MOUTH_LEFT, 430, 650))
            put(point(FaceLandmark.MOUTH_RIGHT, 570, 650))
        }.toString()
        return DetectedFaceEntity(
            volume, id, 0, "detector-v1",
            250, 200, 750, 800,
            180, 150, 820, 850,
            0f, 0f, 0f, 0.9f, landmarks,
        )
    }

    private fun point(type: Int, x: Int, y: Int) = JSONObject().put("t", type).put("x", x).put("y", y)
}
