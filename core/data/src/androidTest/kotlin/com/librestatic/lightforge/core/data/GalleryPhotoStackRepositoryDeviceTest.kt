package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class GalleryPhotoStackRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun key(id: Long, volume: String = "external_primary") = MediaKey(volume, id)

    private fun media(id: Long, volume: String = "external_primary") =
        MediaItemEntity(
            volume,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            10,
            10,
            0,
            0,
            id * 1000,
            id,
            id,
            id * 1000,
            1,
            1,
            99,
            "Fixture",
            "DCIM/Fixture/",
            false,
            false,
            true,
            1,
        )

    private suspend fun db(block: suspend (GalleryDatabase, GalleryPhotoStackRepository) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            block(database, GalleryPhotoStackRepository(database) { 12345L })
        } finally {
            database.close()
        }
    }

    private suspend fun <T : Any> rows(source: PagingSource<Int, T>): List<T> {
        val result = source.load(PagingSource.LoadParams.Refresh(null, 100, false))
        check(result is PagingSource.LoadResult.Page) { result.toString() }
        return result.data
    }

    private suspend fun seed(database: GalleryDatabase, count: Long = 3) {
        database.libraryDao().upsertMedia((1L..count).map(::media))
    }

    private suspend fun similarity(
        database: GalleryDatabase,
        id: Long,
        volume: String = "external_primary",
        version: String = "fixture",
        generation: Long = 1,
    ) {
        database
            .libraryDao()
            .upsertSimilarityFeature(
                SimilarityFeatureEntity(
                    volume,
                    id,
                    generation,
                    version,
                    0,
                    byteArrayOf(1),
                    0,
                    0,
                    0,
                    0,
                    id.toFloat(),
                    1,
                )
            )
        database
            .libraryDao()
            .upsertSimilarityMemberships(
                listOf(SimilarityMembershipEntity(volume, id, "fixture-cluster", 0.9f))
            )
    }

    private suspend fun stack(database: GalleryDatabase, id: String) =
        database.photoStackDao().get(id)!!

    @Test
    fun manualStacksPreserveOrderCoverAndSourceRowsAcrossDerivedDataPurge(): Unit = runBlocking {
        db { database, repo ->
            seed(database)
            (1L..3).forEach { similarity(database, it) }
            val id = repo.create(listOf(key(3), key(1), key(2)), key(1), "  My trip  ")
            database.libraryDao().purgeSimilarityFeatures()
            assertEquals(listOf(3L, 1L, 2L), repo.members(id).first().map { it.media.mediaStoreId })
            assertEquals(1L, stack(database, id).coverMediaStoreId)
            assertEquals("My trip", stack(database, id).title)
            assertEquals(media(1), database.documentDao().get("external_primary", 1)!!.media)
            assertNull(database.documentDao().archivedAt("external_primary", 1))
            assertEquals(1L, repo.count().first())
        }
    }

    @Test
    fun invalidOrConflictingBatchRollsBackWithoutOrphanGroup(): Unit = runBlocking {
        db { database, repo ->
            seed(database)
            assertTrue(runCatching { repo.create(listOf(key(1), key(99))) }.isFailure)
            assertTrue(runCatching { repo.create(listOf(key(1), key(1))) }.isFailure)
            assertEquals(0L, repo.count().first())
            repo.create(listOf(key(1), key(2)))
            assertTrue(runCatching { repo.create(listOf(key(3), key(2))) }.isFailure)
            assertNull(database.photoStackDao().membership("external_primary", 3))
            assertEquals(1L, repo.count().first())
        }
    }

    @Test
    fun currentSimilarityFiltersAccessTrashTypeGenerationVersionsAndExclusions(): Unit =
        runBlocking {
            db { database, repo ->
                seed(database, 7)
                database
                    .libraryDao()
                    .upsertMedia(
                        listOf(
                            media(1, "secondary"),
                            media(4).copy(isAccessible = false),
                            media(5).copy(isTrashed = true),
                            media(6).copy(mediaType = 3),
                        )
                    )
                for (id in 1L..7) similarity(
                    database,
                    id,
                    version = if (id == 7L) "old-version" else "fixture",
                    generation = if (id == 3L) 0 else 1,
                )
                similarity(database, 1, "secondary")
                val group = rows(database.photoStackDao().suggestions()).single()
                assertEquals(3L, group.memberCount)
                assertEquals(
                    setOf(key(1), key(2), key(1, "secondary")),
                    repo.preview(group).photos.map { it.key() }.toSet(),
                )
                database
                    .libraryDao()
                    .excludeSimilarity(SimilarityExclusionEntity("external_primary", 1, 1))
                database
                    .photoStackDao()
                    .exclude(PhotoStackExclusionEntity("external_primary", 2, 1))
                assertTrue(rows(database.photoStackDao().suggestions()).isEmpty())
            }
        }

    @Test
    fun staleSuggestionCannotSaveAChangedGroup(): Unit = runBlocking {
        db { database, repo ->
            seed(database)
            (1L..3).forEach { similarity(database, it) }
            val preview = repo.preview(rows(database.photoStackDao().suggestions()).single())
            database.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 2)))
            assertTrue(
                runCatching { repo.save(preview, key(2)) }.exceptionOrNull() is PhotoStackChanged
            )
            assertEquals(0L, repo.count().first())
            val fresh = repo.preview(rows(database.photoStackDao().suggestions()).single())
            val id = repo.save(fresh, key(3))
            assertEquals(3L, stack(database, id).coverMediaStoreId)
            assertTrue(rows(database.photoStackDao().suggestions()).isEmpty())
        }
    }

    @Test
    fun separatingCoverFallsBackAndExplicitUnstackStaysSeparatedAfterReanalysis(): Unit =
        runBlocking {
            db { database, repo ->
                seed(database)
                (1L..3).forEach { similarity(database, it) }
                val id = repo.create(listOf(key(1), key(2), key(3)))
                repo.separate(id, stack(database, id).revision, key(1))
                assertEquals(2L, stack(database, id).coverMediaStoreId)
                assertEquals(listOf(2L, 3L), repo.members(id).first().map { it.media.mediaStoreId })
                repo.dissolve(id, stack(database, id).revision)
                database.libraryDao().purgeSimilarityFeatures()
                (1L..3).forEach { similarity(database, it) }
                assertEquals(0L, repo.count().first())
                assertTrue(rows(database.photoStackDao().suggestions()).isEmpty())
                assertEquals(3, rows(database.photoStackDao().separated()).size)
                repo.allowSuggestion(key(1))
                repo.allowSuggestion(key(2))
                assertEquals(2L, rows(database.photoStackDao().suggestions()).single().memberCount)
                assertEquals(media(1), database.documentDao().get("external_primary", 1)!!.media)
            }
        }

    @Test
    fun twoPhotoStackDissolvesWhenOneIsSeparatedWithoutDeletingEitherSource(): Unit = runBlocking {
        db { database, repo ->
            seed(database, 2)
            val id = repo.create(listOf(key(1), key(2)))
            repo.separate(id, stack(database, id).revision, key(1))
            assertNull(database.photoStackDao().get(id))
            assertEquals(2L, database.libraryDao().mediaCount())
            assertEquals(1, rows(database.photoStackDao().separated()).size)
        }
    }

    @Test
    fun unavailableCoverUsesDisplayFallbackWithoutOverwritingItsPersistentChoice(): Unit =
        runBlocking {
            db { database, repo ->
                seed(database, 2)
                val id = repo.create(listOf(key(1), key(2)), key(2))
                database.libraryDao().upsertMedia(listOf(media(2).copy(isAccessible = false)))
                var summary = rows(database.photoStackDao().pages()).single()
                assertEquals(1L, summary.availableCount)
                assertEquals(2L, summary.totalCount)
                assertEquals(1L, summary.displayCoverMediaStoreId)
                assertEquals(2L, stack(database, id).coverMediaStoreId)
                assertTrue(
                    runCatching { repo.setCover(id, stack(database, id).revision, key(2)) }
                        .isFailure
                )
                database.libraryDao().upsertMedia(listOf(media(1).copy(isTrashed = true)))
                summary = rows(database.photoStackDao().pages()).single()
                assertEquals(0L, summary.availableCount)
                assertNull(summary.displayCoverMediaStoreId)
                database.libraryDao().upsertMedia(listOf(media(1), media(2)))
                assertEquals(
                    2L,
                    rows(database.photoStackDao().pages()).single().displayCoverMediaStoreId,
                )
                database.libraryDao().upsertMedia(listOf(media(2).copy(isAccessible = false)))
                repo.setCover(id, stack(database, id).revision, key(1))
                assertEquals(1L, stack(database, id).coverMediaStoreId)
            }
        }

    @Test
    fun coverRevisionRejectsConcurrentAndStaleEdits(): Unit = runBlocking {
        db { database, repo ->
            seed(database)
            val id = repo.create(listOf(key(1), key(2), key(3)))
            val revision = stack(database, id).revision
            val results = coroutineScope {
                listOf(2L, 3L)
                    .map { n ->
                        async(Dispatchers.IO) {
                            runCatching { repo.setCover(id, revision, key(n)) }
                        }
                    }
                    .awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is PhotoStackChanged })
            assertTrue(
                runCatching { repo.rename(id, revision, "stale") }.exceptionOrNull()
                    is PhotoStackChanged
            )
            assertNull(stack(database, id).title)
        }
    }

    @Test
    fun compositeIdentityAndUserChoicesSurviveDatabaseReopen(): Unit = runBlocking {
        val name = "photo-stack-reopen-${java.util.UUID.randomUUID()}.db"
        try {
            var database = GalleryDatabaseFactory.open(context, name)
            var id = ""
            try {
                database.libraryDao().upsertMedia(listOf(media(1), media(1, "secondary")))
                val repo = GalleryPhotoStackRepository(database)
                id = repo.create(listOf(key(1), key(1, "secondary")), key(1, "secondary"))
                repo.rename(id, stack(database, id).revision, "Durable stack")
            } finally {
                database.close()
            }
            database = GalleryDatabaseFactory.open(context, name)
            try {
                assertEquals("secondary", stack(database, id).coverVolumeName)
                assertEquals("Durable stack", stack(database, id).title)
                assertEquals(2, GalleryPhotoStackRepository(database).members(id).first().size)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletingSourceCascadesOnlyItsReferenceAndNeverAdoptsReusedIdentity(): Unit = runBlocking {
        db { database, repo ->
            seed(database, 2)
            val id = repo.create(listOf(key(1), key(2)))
            database.libraryDao().deleteMedia("external_primary", 1)
            assertEquals(
                listOf(2L),
                database.photoStackDao().rawMembers(id).map { it.mediaStoreId },
            )
            assertEquals(
                2L,
                rows(database.photoStackDao().pages()).single().displayCoverMediaStoreId,
            )
            database.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 99)))
            assertNull(database.photoStackDao().membership("external_primary", 1))
            assertTrue(
                runCatching { repo.setCover(id, stack(database, id).revision, key(1)) }.isFailure
            )
            repo.dissolve(id, stack(database, id).revision)
            assertEquals(2L, database.libraryDao().mediaCount())
        }
    }

    @Test
    fun stackLimitAndInjectedFailureAreAtomic(): Unit = runBlocking {
        db { database, repo ->
            seed(database, 501)
            assertTrue(runCatching { repo.create((1L..501L).map(::key)) }.isFailure)
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_stack_member BEFORE INSERT ON photo_stack_members WHEN NEW.mediaStoreId=2 BEGIN SELECT RAISE(ABORT, 'fixture'); END"
            )
            assertTrue(runCatching { repo.create(listOf(key(1), key(2))) }.isFailure)
            assertEquals(0L, repo.count().first())
            assertNull(database.photoStackDao().membership("external_primary", 1))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_stack_member")
            val id = repo.create((1L..500L).map(::key))
            assertEquals(500, repo.members(id).first().size)
        }
    }
}
