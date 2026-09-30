package com.librestatic.lightforge.core.ml

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.SimilarityFeatureEntity
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SimilarityEngineDeviceTest {
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

    @Test fun stacksAppearDisappearAndRestoreIncrementally() = runBlocking {
        database.libraryDao().upsertMedia(
            listOf(media(1), media(2, favorite = true), media(3)),
        )
        val extractor = SimilarityFeatureExtractor { item ->
            when (item.mediaStoreId) {
                1L -> RawSimilarityFeature(0L, ByteArray(48) { 100 }, 10f)
                2L -> RawSimilarityFeature(1L, ByteArray(48) { 102 }, 11f)
                else -> RawSimilarityFeature(-1L, ByteArray(48) { 240.toByte() }, 12f)
            }
        }
        val engine = SimilarityMlEngine(database, extractor, { true }, { 1 })
        runToCompletion(engine)
        val repository = SimilarityRepository(database, { 2 })
        val stack = repository.stacks(null, 20).single()
        assertEquals(2L, stack.memberCount)
        assertEquals(MediaKey("external_primary", 2), stack.recommendedKeep)
        assertTrue(stack.bestScore >= SimilarityMlEngine.SimilarityThreshold)

        repository.ungroup(MediaKey("external_primary", 2))
        assertTrue(repository.stacks(null, 20).isEmpty())
        repository.restore(MediaKey("external_primary", 2))
        runToCompletion(engine)
        assertEquals(2L, repository.stacks(null, 20).single().memberCount)

        database.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 2)))
        assertTrue(engine.process(null, 50) is MlChunkOutcome.More)
        assertEquals(2L, database.libraryDao().similarityFeature("external_primary", 1)?.generationModified)
    }

    @Test fun nativeDescriptorRanksSmallVisualChangeAboveDifferentImage() = runBlocking {
        val firstUri = imageFixture("similar-a-${UUID.randomUUID()}.png", Color.RED, 0)
        val nearUri = imageFixture("similar-b-${UUID.randomUUID()}.png", Color.rgb(245, 10, 10), 2)
        val farUri = imageFixture("different-${UUID.randomUUID()}.png", Color.BLUE, 80)
        val items = listOf(firstUri, nearUri, farUri).map { uri -> media(uri.lastPathSegment!!.toLong()) }
        val extractor = NativeSimilarityFeatureExtractor(context.contentResolver)
        val features = items.map { item ->
            val raw = extractor.extract(item)
            SimilarityFeatureEntity(
                item.volumeName, item.mediaStoreId, 1, SimilarityMlEngine.AlgorithmVersion,
                raw.pHash, raw.compactEmbedding,
                (raw.pHash and 0xffff).toInt(), ((raw.pHash ushr 16) and 0xffff).toInt(),
                ((raw.pHash ushr 32) and 0xffff).toInt(), ((raw.pHash ushr 48) and 0xffff).toInt(),
                raw.blurScore, 1,
            )
        }
        assertTrue(similarity(features[0], features[1]) > similarity(features[0], features[2]))
        assertTrue(features.all { it.blurScore >= 0f })
    }

    @Test fun lshCandidateLookupIsBoundedAtHundredThousandRows() = runBlocking {
        val dao = database.libraryDao()
        (1..100_000).chunked(500).forEach { ids ->
            val media = ids.map { media(it.toLong()) }
            dao.upsertMedia(media)
            dao.upsertSimilarityFeatures(media.map { item ->
                val hash = item.mediaStoreId * 0x9e3779b97f4a7c15UL.toLong()
                SimilarityFeatureEntity(
                    item.volumeName, item.mediaStoreId, 1, SimilarityMlEngine.AlgorithmVersion,
                    hash, ByteArray(48), (hash and 0xffff).toInt(),
                    ((hash ushr 16) and 0xffff).toInt(), ((hash ushr 32) and 0xffff).toInt(),
                    ((hash ushr 48) and 0xffff).toInt(), 1f, 1,
                )
            })
        }
        val probe = requireNotNull(dao.similarityFeature("external_primary", 50_000))
        val started = android.os.SystemClock.elapsedRealtime()
        val candidates = dao.similarityLshCandidates(
            SimilarityMlEngine.AlgorithmVersion,
            probe.volumeName,
            probe.mediaStoreId,
            probe.lsh0,
            probe.lsh1,
            probe.lsh2,
            probe.lsh3,
            SimilarityMlEngine.MaxCandidates,
        )
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue(candidates.size <= SimilarityMlEngine.MaxCandidates)
        assertTrue("100k LSH lookup took ${elapsed}ms", elapsed < 5_000)
    }

    private suspend fun runToCompletion(engine: SimilarityMlEngine) {
        repeat(10) { if (engine.process(null, 50) is MlChunkOutcome.Complete) return }
        error("Similarity engine did not converge")
    }

    private fun imageFixture(name: String, color: Int, offset: Int): Uri {
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lightforge-M3-Similarity")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawRect(60f + offset, 80f, 250f + offset, 230f, Paint().apply { this.color = Color.WHITE })
        }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        fixtures += uri
        return uri
    }

    private fun media(id: Long, favorite: Boolean = false) = MediaItemEntity(
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        mediaStoreId = id,
        mediaType = 1,
        mimeType = "image/png",
        displayName = "$id.png",
        sizeBytes = 1_000,
        width = 320,
        height = 320,
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
        isFavorite = favorite,
        isTrashed = false,
        isAccessible = true,
        lastSeenScanId = 1,
    )
}
