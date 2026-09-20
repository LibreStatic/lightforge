package com.ugallery.core.ml

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.search.AppSearchMediaIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * An undecodable file used to retry its whole ML chunk forever, so library analysis never
 * finished. Each engine must now record an empty result for the item's generation instead.
 */
@RunWith(AndroidJUnit4::class)
class UndecodableMediaDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: GalleryDatabase
    private val fixtures = mutableListOf<Uri>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
    }

    @After fun tearDown() {
        database.close()
        fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun undecodableMediaDoesNotStarveFaceDetection() = runBlocking {
        val corrupt = corruptFixture()
        val item = media(corrupt)
        database.libraryDao().upsertMedia(listOf(item))
        val engine = FaceDetectionMlEngine(
            context.contentResolver,
            database,
            { true },
            inference = FaceDetectionInference { emptyList() },
        )

        engine.use {
            val outcome = it.process(null, 50)
            assertTrue("undecodable item must not force a retry", outcome is MlChunkOutcome.Complete)
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 50))
        }

        assertEquals(0, database.libraryDao().detectedFaceCount())
        assertEquals(
            listOf(0),
            runRows("SELECT acceptedFaceCount FROM face_detection_runs WHERE mediaStoreId = ?", item.mediaStoreId),
        )
        assertEquals(
            emptyList<Long>(),
            database.libraryDao()
                .pendingFaceDetectionCandidates(FaceDetectionMlEngine.ModelVersion, 50)
                .map(MediaItemEntity::mediaStoreId),
        )
    }

    @Test fun undecodableMediaDoesNotStarveImageLabels() = runBlocking {
        val corrupt = corruptFixture()
        val item = media(corrupt)
        database.libraryDao().upsertMedia(listOf(item))
        val engine = ImageLabelMlEngine(
            context.contentResolver,
            database,
            AppSearchMediaIndex(context, "undecodable-labels-${UUID.randomUUID()}"),
            { true },
            inference = ImageLabelInference { error("inference must not run for undecodable media") },
        )

        engine.use {
            assertTrue(it.process(null, 50) is MlChunkOutcome.Complete)
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 50))
        }

        assertEquals(
            emptyList<String>(),
            database.libraryDao().labels(item.volumeName, item.mediaStoreId).map { it.canonicalLabel },
        )
        assertEquals(
            listOf(1),
            runRows("SELECT COUNT(*) FROM media_label_runs WHERE mediaStoreId = ?", item.mediaStoreId),
        )
        assertEquals(
            emptyList<Long>(),
            database.libraryDao()
                .pendingLabelCandidates(ImageLabelMlEngine.ModelVersion, 50)
                .map(MediaItemEntity::mediaStoreId),
        )
    }

    @Test fun undecodableMediaDoesNotStarveOcr() = runBlocking {
        val corrupt = corruptFixture()
        val item = media(corrupt)
        database.libraryDao().upsertMedia(listOf(item))
        val engine = OcrMlEngine(
            context.contentResolver,
            database,
            AppSearchMediaIndex(context, "undecodable-ocr-${UUID.randomUUID()}"),
            { true },
            inference = OcrInference { error("inference must not run for undecodable media") },
        )

        engine.use {
            assertTrue(it.process(null, 50) is MlChunkOutcome.Complete)
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 50))
        }

        val stored = database.libraryDao().ocr(item.volumeName, item.mediaStoreId)
        assertEquals("", requireNotNull(stored).rawText)
        assertEquals(
            emptyList<Long>(),
            database.libraryDao()
                .pendingOcrCandidates(OcrMlEngine.ModelVersion, 50)
                .map(MediaItemEntity::mediaStoreId),
        )
    }

    private fun runRows(sql: String, mediaStoreId: Long): List<Int> =
        database.query(sql, arrayOf<Any>(mediaStoreId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }

    /** A MediaStore row whose backing bytes are not a decodable image. */
    private fun corruptFixture(): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "corrupt-${UUID.randomUUID()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGallery-M3-Test")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        // A valid PNG signature followed by garbage: the decoder accepts the file, then fails.
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + ByteArray(2_048) { (it * 31 % 251).toByte() }
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        fixtures += uri
        return uri
    }

    private fun media(uri: Uri) = MediaItemEntity(
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY, mediaStoreId = uri.lastPathSegment!!.toLong(),
        mediaType = 1, mimeType = "image/png", displayName = "corrupt.png", sizeBytes = 1,
        width = 1_200, height = 600, durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = 10, dateAddedSeconds = 10, dateModifiedSeconds = 10,
        timelineSortMillis = 10, generationAdded = 1, generationModified = 1,
        bucketId = 1, bucketDisplayName = "Camera", relativePath = "Pictures/Camera/",
        isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )

    @Suppress("unused")
    private fun Uri.key() = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, lastPathSegment!!.toLong())
}
