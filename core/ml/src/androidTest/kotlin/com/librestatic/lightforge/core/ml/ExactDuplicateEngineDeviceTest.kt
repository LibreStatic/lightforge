package com.librestatic.lightforge.core.ml

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.DuplicateHashEntity
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExactDuplicateEngineDeviceTest {
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

    @Test fun exactGroupsRequireFullShaAndRecommendDeterministicKeep() = runBlocking {
        database.libraryDao().upsertMedia(
            listOf(
                media(1, favorite = false, width = 4_000),
                media(2, favorite = true, width = 1_000),
                media(3, favorite = false, width = 8_000),
                media(4, favorite = false, width = 500).copy(sizeBytes = 200),
            ),
        )
        val hasher = object : DuplicateContentHasher {
            override suspend fun sample(item: MediaItemEntity) = if (item.mediaStoreId == 4L) "other" else "collision"
            override suspend fun full(item: MediaItemEntity) = if (item.mediaStoreId <= 2L) "exact" else "different"
        }
        val engine = ExactDuplicateMlEngine(database, hasher, { true }, { 1 })
        runToCompletion(engine)

        val groups = ExactDuplicateRepository(database).groups(null, 50)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().memberCount)
        assertEquals(100, groups.single().recoverableBytes)
        assertEquals(MediaKey("external_primary", 2), groups.single().recommendedKeep)
        assertEquals(
            listOf(1L, 2L),
            ExactDuplicateRepository(database).members(groups.single(), null, 50).map { it.mediaStoreId },
        )
        assertEquals(4, database.libraryDao().mediaCount())
    }

    @Test fun sampleCollisionDoesNotCreateAFalseExactGroup() = runBlocking {
        val original = ByteArray(512 * 1_024) { (it % 251).toByte() }
        val adversarial = original.copyOf().also { it[100_000] = (it[100_000] + 1).toByte() }
        val first = rawFixture("first-${UUID.randomUUID()}.jpg", original)
        val second = rawFixture("second-${UUID.randomUUID()}.jpg", original)
        val collision = rawFixture("collision-${UUID.randomUUID()}.jpg", adversarial)
        val items = listOf(first, second, collision).mapIndexed { index, uri ->
            media(uri.lastPathSegment!!.toLong()).copy(sizeBytes = original.size.toLong(), timelineSortMillis = index.toLong())
        }
        database.libraryDao().upsertMedia(items)
        val hasher = ResolverDuplicateContentHasher(context.contentResolver)
        assertEquals(hasher.sample(items[0]), hasher.sample(items[2]))
        assertNotEquals(hasher.full(items[0]), hasher.full(items[2]))

        runToCompletion(ExactDuplicateMlEngine(database, hasher, { true }))
        val groups = ExactDuplicateRepository(database).groups(null, 50)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().memberCount)
    }

    @Test fun interruptionKeepsCommittedProgressAndResumes() = runBlocking {
        database.libraryDao().upsertMedia(listOf(media(11), media(12), media(13)))
        var calls = 0
        val interrupted = ExactDuplicateMlEngine(
            database,
            object : DuplicateContentHasher {
                override suspend fun sample(item: MediaItemEntity): String {
                    calls += 1
                    if (calls == 2) error("interrupted")
                    return "sample-${item.mediaStoreId}"
                }
                override suspend fun full(item: MediaItemEntity) = error("not needed")
            },
            { true },
        )
        assertTrue(runCatching { interrupted.process(null, 50) }.isFailure)
        assertEquals(1, listOf(11L, 12L, 13L).count { database.libraryDao().duplicateHash("external_primary", it) != null })

        val resumed = ExactDuplicateMlEngine(
            database,
            object : DuplicateContentHasher {
                override suspend fun sample(item: MediaItemEntity) = "sample-${item.mediaStoreId}"
                override suspend fun full(item: MediaItemEntity) = error("not needed")
            },
            { true },
        )
        runToCompletion(resumed)
        assertEquals(3, listOf(11L, 12L, 13L).count { database.libraryDao().duplicateHash("external_primary", it) != null })
    }

    @Test fun hundredThousandRowIndexReturnsOnlyBoundedGroups() = runBlocking {
        val dao = database.libraryDao()
        val total = 100_000
        (1..total).chunked(500).forEach { ids ->
            val media = ids.map { media(it.toLong()).copy(sizeBytes = 1_000) }
            dao.upsertMedia(media)
            dao.upsertDuplicateHashes(
                media.map { item ->
                    val pair = item.mediaStoreId <= 200
                    val group = if (pair) (item.mediaStoreId - 1) / 2 else item.mediaStoreId
                    DuplicateHashEntity(
                        item.volumeName,
                        item.mediaStoreId,
                        item.generationModified,
                        item.sizeBytes,
                        ExactDuplicateMlEngine.HashVersion,
                        "sample-$group",
                        if (pair) "sha-$group" else null,
                        1,
                    )
                },
            )
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val groups = ExactDuplicateRepository(database).groups(null, 20)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertEquals(20, groups.size)
        assertTrue("100k duplicate-group page took ${elapsed}ms", elapsed < 5_000)
        assertTrue(dao.pendingDuplicateSampleCandidates(ExactDuplicateMlEngine.HashVersion, 1).isEmpty())
    }

    private suspend fun runToCompletion(engine: ExactDuplicateMlEngine) {
        repeat(20) {
            if (engine.process(null, 50) is MlChunkOutcome.Complete) return
        }
        error("Duplicate engine did not converge")
    }

    private fun rawFixture(name: String, bytes: ByteArray): Uri {
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lightforge-M3-Duplicates")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        fixtures += uri
        return uri
    }

    private fun media(
        id: Long,
        favorite: Boolean = false,
        width: Int = 1_000,
    ) = MediaItemEntity(
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        mediaStoreId = id,
        mediaType = 1,
        mimeType = "image/jpeg",
        displayName = "$id.jpg",
        sizeBytes = 100,
        width = width,
        height = 1_000,
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
