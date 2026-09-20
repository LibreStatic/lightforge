package com.ugallery.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GalleryDocumentRepositoryDeviceTest {
    private suspend fun <T> GalleryDatabase.useDatabase(block: suspend (GalleryDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun <T> database(
        block: suspend (GalleryDatabase, GalleryDocumentRepository) -> T
    ): T {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        return try {
            block(db, GalleryDocumentRepository(db))
        } finally {
            db.close()
        }
    }

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

    private fun ocr(id: Long, text: String = "Local receipt", generation: Long = 1) =
        MediaOcrEntity(
            "external_primary",
            id,
            generation,
            "fixture",
            text,
            text.lowercase(),
            "[]",
            1,
        )

    private suspend fun page(
        db: GalleryDatabase,
        category: String = "All",
        query: String = "",
    ): List<DocumentRow> {
        val result =
            db.documentDao()
                .page(category, GalleryDocumentRepository.searchPattern(query))
                .load(PagingSource.LoadParams.Refresh(null, 100, false))
        check(result is PagingSource.LoadResult.Page) { result.toString() }
        return result.data
    }

    @Test
    fun currentEvidenceSuggestsButNeverInventsCategoriesOrExposesUnavailableMedia() = runBlocking {
        database { db, repo ->
            db.libraryDao()
                .upsertMedia(
                    (1L..6L).map(::media) +
                        media(7).copy(isAccessible = false) +
                        media(8).copy(isTrashed = true) +
                        media(9).copy(mediaType = 3)
                )
            db.libraryDao().upsertOcr(ocr(1))
            db.libraryDao().upsertOcr(ocr(2, generation = 0))
            db.libraryDao().upsertOcr(ocr(3, text = "   "))
            for (id in listOf(4L, 5L, 6L)) {
                db.libraryDao()
                    .upsertLabelRun(
                        MediaLabelRunEntity(
                            "external_primary",
                            id,
                            if (id == 5L) 0 else 1,
                            "fixture",
                            1,
                        )
                    )
                db.libraryDao()
                    .upsertLabels(
                        listOf(
                            MediaLabelEntity(
                                "external_primary",
                                id,
                                "document",
                                "document",
                                if (id == 6L) 0.2f else 0.9f,
                                "fixture",
                            )
                        )
                    )
            }
            for (id in 7L..9L) db.libraryDao().upsertOcr(ocr(id))
            assertEquals(listOf(4L, 1L), page(db).map { it.media.mediaStoreId })
            assertTrue(page(db).all { it.category == null })
            assertEquals(2L, repo.count().first())
            assertNull(db.documentDao().get("external_primary", 2)!!.ocrText)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO label_suppressions VALUES ('document', 1)"
            )
            assertEquals(listOf(1L), page(db).map { it.media.mediaStoreId })
        }
    }

    @Test
    fun manualClassificationSurvivesDerivedDataPurgeAndHonorsVolumeAndExclusion() = runBlocking {
        database { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(1), media(1, "secondary")))
            db.libraryDao().upsertOcr(ocr(1))
            repo.classify(listOf(key(1)), DocumentCategory.Receipt)
            repo.classify(listOf(key(1, "secondary")), DocumentCategory.Note)
            db.libraryDao().purgeOcr()
            assertEquals(2, page(db).size)
            assertEquals("external_primary", page(db, "Receipt").single().media.volumeName)
            repo.classify(listOf(key(1)), DocumentCategory.Excluded)
            assertEquals("secondary", page(db).single().media.volumeName)
            assertEquals("Excluded", page(db, "Excluded").single().category)
            repo.classify(listOf(key(1)), DocumentCategory.Ticket)
            assertEquals("Ticket", page(db, "Ticket").single().category)
            repo.clearClassification(key(1))
            assertEquals(1, page(db).size)
            assertEquals(media(1), db.documentDao().get("external_primary", 1)!!.media)
        }
    }

    @Test
    fun batchClassificationRollsBackWhenAnyTargetIsUnavailable() = runBlocking {
        database { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(1)))
            assertTrue(
                runCatching { repo.classify(listOf(key(1), key(2)), DocumentCategory.Note) }
                    .isFailure
            )
            assertTrue(page(db).isEmpty())
            assertNull(db.documentDao().get("external_primary", 1)!!.category)
            assertTrue(
                runCatching { repo.classify(listOf(key(1)), DocumentCategory.All) }.isFailure
            )
        }
    }

    @Test
    fun searchEscapesWildcardCharactersAndOrdersDeterministically() = runBlocking {
        database { db, repo ->
            db.libraryDao().upsertMedia((1L..3L).map(::media))
            repo.classify((1L..3L).map(::key), DocumentCategory.Note)
            db.libraryDao().upsertOcr(ocr(1, "Total 10%_discount"))
            db.libraryDao().upsertOcr(ocr(2, "Total 10XYZdiscount"))
            assertEquals(listOf(3L, 2L, 1L), page(db, "Note").map { it.media.mediaStoreId })
            assertEquals(1L, page(db, query = "%_").single().media.mediaStoreId)
            assertEquals(2L, page(db, query = "photo-2").single().media.mediaStoreId)
            assertTrue(page(db, query = "' OR 1=1 --").isEmpty())
        }
    }

    @Test
    fun archiveUndoPreservesCategoriesAndRejectsConflictingOrChangedSourceState() = runBlocking {
        database { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(1)))
            repo.classify(listOf(key(1)), DocumentCategory.Receipt)
            val first = repo.archive(key(1), true)
            assertTrue(page(db).single().archived)
            assertEquals(
                GalleryActivityType.Archived,
                GalleryActivityRepository(db).latest().first().first().type,
            )
            assertTrue(repo.undo(first))
            assertFalse(page(db).single().archived)
            assertEquals(
                GalleryActivityType.Unarchived,
                GalleryActivityRepository(db).latest().first().first().type,
            )
            val second = repo.archive(key(1), true)
            repo.archive(key(1), false)
            assertFalse(repo.undo(second))
            val third = repo.archive(key(1), true)
            db.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 2)))
            assertFalse(repo.undo(third))
            assertEquals("Receipt", page(db).single().category)
            assertEquals(100L, page(db).single().media.sizeBytes)
        }
    }

    @Test
    fun pdfSelectionPreservesOrderAndRevalidatesAccessAndBounds() = runBlocking {
        database { db, repo ->
            db.libraryDao().upsertMedia((1L..25L).map(::media))
            assertEquals(listOf(key(3), key(1)), repo.pdfKeys(listOf(key(3), key(1))))
            assertTrue(runCatching { repo.pdfKeys((1L..25L).map(::key)) }.isFailure)
            assertTrue(runCatching { repo.pdfKeys(listOf(key(1), key(1))) }.isFailure)
            db.libraryDao().upsertMedia(listOf(media(1).copy(isAccessible = false)))
            assertTrue(runCatching { repo.pdfKeys(listOf(key(1))) }.isFailure)
        }
    }

    @Test
    fun migration18To19PreservesMediaOcrArchiveAndAddsDurableCategories() = runBlocking {
        val name = "document-migration-${java.util.UUID.randomUUID()}.db"
        try {
            val helper = androidx.room.testing.MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)
            helper.createDatabase(name, 18).use { legacy ->
                // Build the exported historical schema; never relabel a current database as v18.
                legacy.execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,displayName,sizeBytes,width,height,durationMillis,orientationDegrees,dateTakenMillis,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,bucketId,bucketDisplayName,relativePath,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('external_primary',1,1,'image/jpeg','photo-1.jpg',100,10,10,0,0,1000,1,1,1000,1,1,99,'Fixture','DCIM/Fixture/',0,0,1,1)")
                legacy.execSQL("INSERT INTO media_ocr VALUES('external_primary',1,1,'fixture','Local receipt','local receipt','[]',1)")
                legacy.execSQL("INSERT INTO archived_media VALUES('external_primary',1,777)")
                legacy.execSQL("INSERT INTO moments VALUES('legacy-memory','MANUAL','SAVED','fixture',1,1,'Legacy title','USER',1,1,1)")
                legacy.execSQL("INSERT INTO moment_members VALUES('legacy-memory',0,'external_primary',1,1,'MANUAL',1.0)")
                legacy.execSQL("INSERT INTO moment_covers VALUES('legacy-memory','external_primary',1,1)")
                legacy.query("PRAGMA table_info(moments)").use { cursor ->
                    while (cursor.moveToNext()) assertNotEquals("includeSpecialMedia", cursor.getString(1))
                }
            }
            GalleryDatabaseFactory.open(context, name).useDatabase { db ->
                assertEquals(30, db.openHelper.readableDatabase.version)
                val memory = requireNotNull(db.momentDao().moment("legacy-memory"))
                assertEquals("Legacy title", memory.title)
                assertFalse(memory.includeSpecialMedia)
                assertEquals(listOf(1L), db.momentDao().allMembers("legacy-memory").map { it.mediaStoreId })
                assertEquals("Local receipt", page(db).single().ocrText)
                assertTrue(page(db).single().archived)
                GalleryDocumentRepository(db).classify(listOf(key(1)), DocumentCategory.Receipt)
            }
            GalleryDatabaseFactory.open(context, name).useDatabase { db ->
                assertEquals("Receipt", page(db).single().category)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
