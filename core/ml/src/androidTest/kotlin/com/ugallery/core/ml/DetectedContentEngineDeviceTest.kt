package com.ugallery.core.ml

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.search.AppSearchMediaIndex
import com.ugallery.core.search.AppSearchMediaSearchRepository
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DetectedContentEngineDeviceTest {
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

    @Test fun labelPipelineCanonicalizesSuppressesVersionsAndIndexes() = runBlocking {
        val fixture = imageFixture("label-${UUID.randomUUID()}.png", "BEACH")
        database.libraryDao().upsertMedia(listOf(media(fixture, bucket = "Camera", sort = 10)))
        database.libraryDao().suppressLabel(
            com.ugallery.core.database.LabelSuppressionEntity("dog", 1),
        )
        val searchName = "labels-${UUID.randomUUID()}"
        val engine = ImageLabelMlEngine(
            context.contentResolver, database, AppSearchMediaIndex(context, searchName), { true },
            inference = ImageLabelInference {
                listOf(
                    RawImageLabel("Beach", .91f), RawImageLabel("Coast", .82f),
                    RawImageLabel("Dog", .95f), RawImageLabel("Noise", .20f),
                )
            },
        )
        engine.use {
            assertEquals(MlChunkOutcome.Complete(1), it.process(null, 50))
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 50))
        }
        val key = fixture.key()
        val labels = database.libraryDao().labels(key.volumeName, key.mediaStoreId)
        assertEquals(listOf("beach"), labels.map { it.canonicalLabel })
        assertEquals(ImageLabelMlEngine.ModelVersion, labels.single().modelVersion)
        AppSearchMediaSearchRepository(context, searchName).search("playa").use { cursor ->
            assertEquals(listOf(key), cursor.nextPage().hits.map { it.key })
        }
    }

    @Test fun ocrPrioritizesScreenshotsStoresBlocksAndIsSearchable() = runBlocking {
        val normal = imageFixture("normal-${UUID.randomUUID()}.png", "NORMAL")
        val screenshot = imageFixture("screenshot-${UUID.randomUUID()}.png", "FACTURA 123")
        database.libraryDao().upsertMedia(
            listOf(media(normal, bucket = "Camera", sort = 20), media(screenshot, bucket = "Screenshots", sort = 10)),
        )
        val seen = mutableListOf<String>()
        val searchName = "ocr-${UUID.randomUUID()}"
        val engine = OcrMlEngine(
            context.contentResolver, database, AppSearchMediaIndex(context, searchName), { true },
            inference = OcrInference { bitmap ->
                val text = if (seen.isEmpty()) "FACTURA 123" else "NORMAL"
                seen += text
                RawOcrResult(text, "[{\"text\":\"$text\",\"box\":[0,0,10,10]}]")
            },
        )
        engine.use {
            assertTrue(it.process(null, 1) is MlChunkOutcome.More)
            assertTrue(it.process(null, 1) is MlChunkOutcome.More)
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 1))
        }
        val screenshotKey = screenshot.key()
        val stored = database.libraryDao().ocr(screenshotKey.volumeName, screenshotKey.mediaStoreId)
        assertEquals("factura 123", stored?.normalizedText)
        assertTrue(requireNotNull(stored).blocksJson.contains("box"))
        AppSearchMediaSearchRepository(context, searchName).search("fáctura").use { cursor ->
            assertEquals(listOf(screenshotKey), cursor.nextPage().hits.map { it.key })
        }
        assertEquals(listOf("FACTURA 123", "NORMAL"), seen)
    }

    @Test fun bundledMlKitModelsRunWithoutDownload() = runBlocking {
        val bitmap = textBitmap("FACTURA 123")
        val started = android.os.SystemClock.elapsedRealtime()
        BundledMlKitImageLabelInference().use { labels -> labels.infer(bitmap) }
        BundledMlKitOcrInference().use { ocr ->
            assertTrue(ocr.infer(bitmap).text.uppercase().contains("FACTURA"))
        }
        BundledMlKitFaceDetectionInference().use { faces -> faces.infer(bitmap) }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue("Bundled cold label+OCR inference took ${elapsed}ms", elapsed < 15_000)
    }

    @Test fun faceDetectionFiltersLowQualityAndStoresOnlyGeometry() = runBlocking {
        val fixture = imageFixture("faces-${UUID.randomUUID()}.png", "FACES")
        database.libraryDao().upsertMedia(listOf(media(fixture, bucket = "Camera", sort = 40)))
        val engine = FaceDetectionMlEngine(
            context.contentResolver,
            database,
            { true },
            inference = FaceDetectionInference { bitmap ->
                listOf(
                    RawDetectedFace(
                        Rect(180, 80, 460, 360), 2f, -4f, 1f,
                        listOf(RawFaceLandmark(FaceLandmark.LEFT_EYE, 260f, 180f)),
                    ),
                    RawDetectedFace(Rect(10, 10, 55, 55), 0f, 0f, 0f, emptyList()),
                    RawDetectedFace(
                        Rect(bitmap.width - 80, 40, bitmap.width + 100, 260),
                        0f, 0f, 0f, emptyList(),
                    ),
                )
            },
        )
        engine.use { assertEquals(MlChunkOutcome.Complete(1), it.process(null, 50)) }

        val key = fixture.key()
        val stored = database.libraryDao().detectedFaces(key.volumeName, key.mediaStoreId)
        assertEquals(1, stored.size)
        assertTrue(stored.single().qualityScore >= FaceQualityFilter.MinimumScore)
        assertTrue(stored.single().landmarksJson.contains("\"t\""))
        assertTrue(stored.single().cropLeftPermille in 0..1000)
        assertEquals(1, database.libraryDao().detectedFaceCount())

        engine.purgeDerivedData()
        assertEquals(0, database.libraryDao().detectedFaceCount())
    }

    @Test fun missingMediaRowDoesNotStarveFaceDetectionQueue() = runBlocking {
        val missing = imageFixture("missing-face-${UUID.randomUUID()}.png", "MISSING")
        database.libraryDao().upsertMedia(listOf(media(missing, bucket = "Camera", sort = 50)))
        context.contentResolver.delete(missing, null, null)
        fixtures.remove(missing)
        val engine = FaceDetectionMlEngine(
            context.contentResolver,
            database,
            { true },
            inference = FaceDetectionInference { error("inference must not run for missing media") },
        )

        engine.use {
            assertEquals(MlChunkOutcome.Complete(1), it.process(null, 50))
            assertEquals(MlChunkOutcome.Complete(0), it.process(null, 50))
        }
        assertEquals(0, database.libraryDao().detectedFaceCount())
    }

    @Test fun inferenceFailureDoesNotCommitAnEmptyResult() = runBlocking {
        val fixture = imageFixture("retry-${UUID.randomUUID()}.png", "RETRY")
        database.libraryDao().upsertMedia(listOf(media(fixture, bucket = "Camera", sort = 30)))
        val engine = ImageLabelMlEngine(
            context.contentResolver,
            database,
            AppSearchMediaIndex(context, "retry-${UUID.randomUUID()}"),
            { true },
            inference = ImageLabelInference { error("temporary inference failure") },
        )
        engine.use {
            assertTrue(runCatching { it.process(null, 50) }.exceptionOrNull() is IllegalStateException)
        }
        val key = fixture.key()
        assertTrue(database.libraryDao().labels(key.volumeName, key.mediaStoreId).isEmpty())
    }

    private fun imageFixture(name: String, text: String): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGallery-M3-Test")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(uri)!!.use { textBitmap(text).compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        fixtures += uri
        return uri
    }

    private fun textBitmap(text: String): Bitmap = Bitmap.createBitmap(1_200, 600, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.WHITE)
            drawText(text, 80f, 330f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 150f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun media(uri: Uri, bucket: String, sort: Long) = MediaItemEntity(
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY, mediaStoreId = uri.lastPathSegment!!.toLong(),
        mediaType = 1, mimeType = "image/png", displayName = "$sort.png", sizeBytes = 1,
        width = 1_200, height = 600, durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = sort, dateAddedSeconds = sort, dateModifiedSeconds = sort,
        timelineSortMillis = sort, generationAdded = 1, generationModified = 1,
        bucketId = 1, bucketDisplayName = bucket, relativePath = "Pictures/$bucket/",
        isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )

    private fun Uri.key() = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, lastPathSegment!!.toLong())
}
