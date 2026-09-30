package com.librestatic.lightforge.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DocumentAutoArchiveRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val time = 200 * DocumentAutoArchiveRepository.DayMillis

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

    private suspend fun database(
        block: suspend (GalleryDatabase, DocumentAutoArchiveRepository) -> Unit
    ) {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            block(db, DocumentAutoArchiveRepository(db) { time })
        } finally {
            db.close()
        }
    }

    private suspend fun seed(db: GalleryDatabase, count: Long = 3) {
        db.libraryDao().upsertMedia((1L..count).map(::media))
        GalleryDocumentRepository(db)
            .classify((1L..minOf(count, 100)).map(::key), DocumentCategory.Receipt)
        if (count > 100)
            GalleryDocumentRepository(db)
                .classify((101L..count).map(::key), DocumentCategory.Receipt)
    }

    private suspend fun archived(db: GalleryDatabase, id: Long) =
        db.documentDao().archivedAt("external_primary", id) != null

    @Test
    fun defaultIsOffAndPreviewNeverWritesAndFiltersCurrentEligiblePhotos(): Unit = runBlocking {
        database { db, repo ->
            seed(db, 10)
            db.libraryDao()
                .upsertMedia(
                    listOf(
                        media(2).copy(isFavorite = true),
                        media(3).copy(isTrashed = true),
                        media(4).copy(isAccessible = false),
                        media(5).copy(mediaType = 3),
                        media(6).copy(timelineSortMillis = time),
                        media(7).copy(timelineSortMillis = 0),
                        media(8).copy(timelineSortMillis = time + 1),
                    )
                )
            GalleryDocumentRepository(db).classify(listOf(key(9)), DocumentCategory.Excluded)
            db.libraryDao().upsertArchived(ArchivedMediaEntity("external_primary", 10, 11))
            assertFalse(repo.state().first().enabled)
            assertEquals(0, repo.runScheduled())
            val preview = repo.preview(DocumentCategory.All, 30)
            assertEquals(listOf(key(1)), preview.items.map { it.key() })
            assertFalse(archived(db, 1))
            assertNull(db.documentArchiveDao().rule())
            assertTrue(runCatching { repo.preview(DocumentCategory.Excluded, 30) }.isFailure)
            assertTrue(runCatching { repo.preview(DocumentCategory.All, -1) }.isFailure)
        }
    }

    @Test
    fun confirmKeepsOptedOutPhotosAndArchivesOnlyReviewedBatchWithActivity(): Unit = runBlocking {
        database { db, repo ->
            seed(db)
            val preview = repo.preview(DocumentCategory.Receipt, 0)
            assertEquals(2, repo.enable(preview, setOf(key(2))))
            assertTrue(archived(db, 1))
            assertFalse(archived(db, 2))
            assertTrue(archived(db, 3))
            assertTrue(repo.state().first().enabled)
            assertEquals(0, repo.runScheduled())
            assertEquals(2L, GalleryActivityRepository(db).latest().first().single().itemCount)
            assertEquals(media(1), db.documentDao().get("external_primary", 1)!!.media)
            assertEquals("Receipt", db.documentDao().get("external_primary", 1)!!.category)
            assertTrue(
                runCatching { repo.enable(preview) }.exceptionOrNull()
                    is DocumentArchivePreviewChanged
            )
        }
    }

    @Test
    fun stalePreviewRejectsSourceAndRuleChangesWithoutPartialWrites(): Unit = runBlocking {
        database { db, repo ->
            seed(db)
            val preview = repo.preview(DocumentCategory.All, 0)
            db.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 2)))
            assertTrue(
                runCatching { repo.enable(preview) }.exceptionOrNull()
                    is DocumentArchivePreviewChanged
            )
            assertFalse(archived(db, 2))
            assertFalse(repo.state().first().enabled)
            val second = repo.preview(DocumentCategory.All, 0)
            repo.pause()
            assertTrue(
                runCatching { repo.enable(second) }.exceptionOrNull()
                    is DocumentArchivePreviewChanged
            )
            assertFalse(archived(db, 1))
        }
    }

    @Test
    fun scheduledBatchesAreBoundedIdempotentAndHonorPauseDuringSourceValidation(): Unit =
        runBlocking {
            database { db, repo ->
                seed(db, 121)
                val first = repo.preview(DocumentCategory.Receipt, 0)
                assertEquals(100, first.items.size)
                assertTrue(first.hasMore)
                assertEquals(100, repo.enable(first))
                assertEquals(21, repo.runScheduled())
                assertEquals(0, repo.runScheduled())
                assertEquals(121L, db.libraryDao().observeArchiveCount().first())
                db.libraryDao().upsertMedia(listOf(media(122)))
                GalleryDocumentRepository(db).classify(listOf(key(122)), DocumentCategory.Receipt)
                assertEquals(
                    0,
                    repo.runScheduled {
                        repo.pause()
                        true
                    },
                )
                assertFalse(archived(db, 122))
            }
        }

    @Test
    fun unavailableFirstWindowDoesNotStarveLaterPhotosAndCursorSurvivesReopen(): Unit =
        runBlocking {
            val name = "auto-archive-cursor-${java.util.UUID.randomUUID()}.db"
            try {
                var db = GalleryDatabaseFactory.open(context, name)
                try {
                    seed(db, 121)
                    val repo = DocumentAutoArchiveRepository(db) { time }
                    repo.enable(repo.preview(DocumentCategory.Note, 0))
                    GalleryDocumentRepository(db)
                        .classify((1L..100L).map(::key), DocumentCategory.Note)
                    GalleryDocumentRepository(db)
                        .classify((101L..121L).map(::key), DocumentCategory.Note)
                    assertEquals(0, repo.runScheduled { it.mediaStoreId > 100 })
                    assertEquals(100L, repo.state().first().afterMediaStoreId)
                } finally {
                    db.close()
                }
                db = GalleryDatabaseFactory.open(context, name)
                try {
                    val repo = DocumentAutoArchiveRepository(db) { time }
                    assertEquals(21, repo.runScheduled { it.mediaStoreId > 100 })
                    assertNull(repo.state().first().afterMediaStoreId)
                    assertEquals(100, repo.runScheduled { true })
                    assertEquals(121L, db.libraryDao().observeArchiveCount().first())
                } finally {
                    db.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test
    fun manualActionsInBothRepositoriesRelinquishUndoEvenAtIdenticalTimestamp(): Unit =
        runBlocking {
            database { db, repo ->
                seed(db)
                repo.enable(repo.preview(DocumentCategory.Receipt, 0))
                GalleryArchiveRepository(db) { time }.setArchived(listOf(key(1)), true)
                GalleryDocumentRepository(db) { time }.archive(key(2), true)
                assertEquals(1, repo.undoLastRun())
                assertTrue(archived(db, 1))
                assertTrue(archived(db, 2))
                assertFalse(archived(db, 3))
                assertFalse(repo.state().first().enabled)
                GalleryArchiveRepository(db).setArchived(listOf(key(1), key(2)), false)
                assertTrue(repo.preview(DocumentCategory.All, 0).items.isEmpty())
            }
        }

    @Test
    fun cancellationAndTransactionFailureNeverLeavePartialArchiveOrRule(): Unit = runBlocking {
        database { db, repo ->
            seed(db)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_second_archive BEFORE INSERT ON archived_media WHEN NEW.mediaStoreId=2 BEGIN SELECT RAISE(ABORT, 'fixture failure'); END"
            )
            assertTrue(runCatching { repo.enable(repo.preview(DocumentCategory.All, 0)) }.isFailure)
            assertFalse(archived(db, 1))
            assertNull(db.documentArchiveDao().rule())
            assertEquals(3, repo.preview(DocumentCategory.All, 0).items.size)
            assertTrue(GalleryActivityRepository(db).latest().first().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_second_archive")
            repo.enable(repo.preview(DocumentCategory.Note, 0))
            GalleryDocumentRepository(db).classify(listOf(key(1)), DocumentCategory.Note)
            assertTrue(
                runCatching { repo.runScheduled { throw CancellationException("fixture") } }
                    .exceptionOrNull() is CancellationException
            )
            assertFalse(archived(db, 1))
            assertEquals(1, repo.runScheduled())
        }
    }

    @Test
    fun durableUndoSurvivesDatabaseReopenAndLeavesChangedSourceUntouched(): Unit = runBlocking {
        val name = "auto-archive-reopen-${java.util.UUID.randomUUID()}.db"
        try {
            var db = GalleryDatabaseFactory.open(context, name)
            try {
                seed(db)
                DocumentAutoArchiveRepository(db) { time }
                    .enable(
                        DocumentAutoArchiveRepository(db) { time }.preview(DocumentCategory.All, 0)
                    )
            } finally {
                db.close()
            }
            db = GalleryDatabaseFactory.open(context, name)
            try {
                val repo = DocumentAutoArchiveRepository(db) { time }
                assertTrue(repo.state().first().enabled)
                assertEquals(3, repo.state().first().lastRunCount)
                db.libraryDao().upsertMedia(listOf(media(2).copy(generationModified = 2)))
                assertEquals(2, repo.undoLastRun())
                assertTrue(archived(db, 2))
                assertFalse(archived(db, 1))
                assertEquals(0, repo.undoLastRun())
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun suggestionsRequireCurrentEvidenceAndCompositeKeysRemainDistinct(): Unit = runBlocking {
        database { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(1), media(1, "secondary"), media(2)))
            db.libraryDao()
                .upsertOcr(
                    MediaOcrEntity(
                        "external_primary",
                        1,
                        1,
                        "fixture",
                        "receipt",
                        "receipt",
                        "[]",
                        1,
                    )
                )
            db.libraryDao()
                .upsertOcr(
                    MediaOcrEntity("external_primary", 2, 0, "fixture", "stale", "stale", "[]", 1)
                )
            GalleryDocumentRepository(db)
                .classify(listOf(key(1, "secondary")), DocumentCategory.Note)
            assertEquals(
                listOf(key(1)),
                repo.preview(DocumentCategory.Suggested, 0).items.map { it.key() },
            )
            repo.enable(repo.preview(DocumentCategory.All, 0), setOf(key(1, "secondary")))
            assertTrue(archived(db, 1))
            assertNull(db.documentDao().archivedAt("secondary", 1))
            assertEquals(0, repo.runScheduled())
        }
    }
}
