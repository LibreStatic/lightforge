package com.ugallery.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Isolated Room fixtures only: no MediaStore mutations, real files or application database. */
class MomentDocumentEligibilityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val volume = "document-memory:fixture"
    private val base = 1_262_347_200_000L
    private val categories =
        listOf(
            DocumentCategory.Receipt,
            DocumentCategory.Ticket,
            DocumentCategory.Note,
            DocumentCategory.Other,
        )

    private fun open() =
        Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .addCallback(MomentDiscoverySchema.Callback)
            .build()

    private fun key(id: Long, sourceVolume: String = volume) = MediaKey(sourceVolume, id)

    private fun media(id: Long, sourceVolume: String = volume, time: Long = base + id * 1000) =
        MediaItemEntity(
            volumeName = sourceVolume,
            mediaStoreId = id,
            mediaType = 1,
            mimeType = "image/jpeg",
            displayName = "IMG_$id.jpg",
            sizeBytes = 1000,
            width = 4000,
            height = 3000,
            durationMillis = 0,
            orientationDegrees = 0,
            dateTakenMillis = time,
            dateAddedSeconds = time / 1000,
            dateModifiedSeconds = time / 1000,
            timelineSortMillis = time,
            generationAdded = 1,
            generationModified = 1,
            bucketId = 1,
            bucketDisplayName = "Camera",
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
            isAccessible = true,
            lastSeenScanId = 1,
        )

    private suspend fun candidates(db: GalleryDatabase) =
        db.momentDao()
            .candidatePage(null, null, null, 500)
            .map { key(it.media.mediaStoreId, it.media.volumeName) }
            .toSet()

    private suspend fun browser(
        db: GalleryDatabase,
        state: String? = null,
    ): List<MomentSummaryRow> {
        val result =
            db.momentDao().browser(state).load(PagingSource.LoadParams.Refresh(null, 40, false))
        assertTrue(result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page<Int, MomentSummaryRow>).data
    }

    private fun cover(db: GalleryDatabase, id: String): List<Any> =
        db.openHelper.readableDatabase
            .query(
                "SELECT volumeName,mediaStoreId,isUserSelected FROM moment_covers WHERE momentId=?",
                arrayOf<Any>(id),
            )
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(cursor.getString(0), cursor.getLong(1), cursor.getInt(2))
            }

    @Test
    fun confirmedCategoriesExcludeCandidatesWhileExcludedNullAndOtherVolumeRemainEligible() =
        runBlocking<Unit> {
            val db = open()
            try {
                db.libraryDao().upsertMedia((1L..6L).map { media(it) } + media(1, "other-volume"))
                val documents = GalleryDocumentRepository(db)
                categories.forEachIndexed { index, category ->
                    documents.classify(listOf(key(index + 1L)), category)
                }
                documents.classify(listOf(key(5)), DocumentCategory.Excluded)
                assertEquals(setOf(key(5), key(6), key(1, "other-volume")), candidates(db))
                categories.indices.forEach { documents.clearClassification(key(it + 1L)) }
                assertEquals(7, candidates(db).size)
            } finally {
                db.close()
            }
        }

    @Test
    fun annotationAndClearUpdateLiveMembersAndPagingWithoutRegeneration() =
        runBlocking<Unit> {
            val db = open()
            try {
                db.libraryDao().upsertMedia((1L..8L).map { media(it) })
                val repo = MomentRepository(db)
                repo.generateIfNeeded(64)
                val id = repo.summaries().first().single().moment.momentId
                val raw = db.momentDao().allMembers(id)
                val events = Channel<List<MomentMemberRow>>(Channel.UNLIMITED)
                val watcher = launch { repo.observeMembers(id).collect { events.send(it) } }
                suspend fun awaitSize(size: Int) =
                    withTimeout(10000) {
                        var rows = events.receive()
                        while (rows.size != size) rows = events.receive()
                        rows
                    }
                try {
                    awaitSize(8)
                    val paging = db.momentDao().browser(null)
                    paging.load(PagingSource.LoadParams.Refresh(null, 40, false))
                    GalleryDocumentRepository(db).classify(listOf(key(1)), DocumentCategory.Receipt)
                    assertFalse(awaitSize(7).any { it.media.mediaStoreId == 1L })
                    withTimeout(10000) { while (!paging.invalid) kotlinx.coroutines.delay(10) }
                    assertEquals(7L, browser(db).single().memberCount)
                    assertFalse(db.momentDao().isVisibleMember(id, volume, 1))
                    assertFalse(repo.setCover(id, key(1)))
                    GalleryDocumentRepository(db).clearClassification(key(1))
                    awaitSize(8)
                    assertEquals(8L, browser(db).single().memberCount)
                    assertEquals(raw, db.momentDao().allMembers(id))
                } finally {
                    watcher.cancel()
                    events.close()
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun savedTitleOrderAndChosenCoverSurviveFallbackAndRediscovery() =
        runBlocking<Unit> {
            val db = open()
            try {
                db.libraryDao().upsertMedia((1L..8L).map { media(it) })
                val repo = MomentRepository(db)
                repo.generateIfNeeded(64)
                val id = repo.summaries().first().single().moment.momentId
                assertTrue(repo.save(id))
                assertTrue(repo.rename(id, "Owned remembered title"))
                val order = (1L..8L).map { key(it) }.reversed()
                assertTrue(repo.reorder(id, order))
                assertTrue(repo.setCover(id, key(1)))
                val before = db.momentDao().moment(id)
                val raw = db.momentDao().allMembers(id)
                val storedCover = cover(db, id)
                GalleryDocumentRepository(db).classify(listOf(key(1)), DocumentCategory.Ticket)
                val filtered = browser(db, "SAVED").single()
                assertEquals(7L, filtered.memberCount)
                assertEquals(8L, filtered.coverMediaStoreId)
                assertEquals(storedCover, cover(db, id))
                assertNotNull(repo.generateIfNeeded(64))
                assertEquals(before, db.momentDao().moment(id))
                assertEquals(raw, db.momentDao().allMembers(id))
                GalleryDocumentRepository(db).clearClassification(key(1))
                assertNotNull(repo.generateIfNeeded(64))
                assertEquals(1L, browser(db, "SAVED").single().coverMediaStoreId)
                assertEquals(before, db.momentDao().moment(id))
                assertEquals(raw, db.momentDao().allMembers(id))
                assertEquals(storedCover, cover(db, id))
            } finally {
                db.close()
            }
        }

    @Test
    fun allCategorizedSavedRemainsRecoverableAndDismissedNeverResurrects() =
        runBlocking<Unit> {
            val db = open()
            try {
                val rows =
                    (1L..8L).map { media(it) } +
                        (101L..108L).map { media(it, time = base + 864000000 + it * 1000) }
                db.libraryDao().upsertMedia(rows)
                val repo = MomentRepository(db)
                repo.generateIfNeeded(64)
                val summaries = repo.summaries().first().sortedBy { it.moment.startMillis }
                assertEquals(2, summaries.size)
                val saved = summaries[0].moment.momentId
                val dismissed = summaries[1].moment.momentId
                assertTrue(repo.save(saved))
                assertTrue(repo.dismiss(dismissed))
                val savedRaw = db.momentDao().allMembers(saved)
                val dismissedRaw = db.momentDao().allMembers(dismissed)
                val documents = GalleryDocumentRepository(db)
                documents.classify(rows.map { key(it.mediaStoreId) }, DocumentCategory.Other)
                assertTrue(repo.members(saved).isEmpty())
                assertTrue(repo.summaries().first().isEmpty())
                val kept = browser(db, "SAVED").single()
                assertEquals(saved, kept.moment.momentId)
                assertEquals(0L, kept.memberCount)
                assertNull(kept.coverMediaStoreId)
                assertNull(kept.coverVolumeName)
                assertNotNull(repo.generateIfNeeded(64))
                rows.forEach { documents.clearClassification(key(it.mediaStoreId)) }
                assertNotNull(repo.generateIfNeeded(64))
                assertEquals(listOf(saved), repo.summaries().first().map { it.moment.momentId })
                assertEquals("DISMISSED", db.momentDao().moment(dismissed)?.state)
                assertEquals(savedRaw, db.momentDao().allMembers(saved))
                assertEquals(dismissedRaw, db.momentDao().allMembers(dismissed))
            } finally {
                db.close()
            }
        }

    @Test
    fun discoveryRevisionChangesOnlyAcrossConfirmedDocumentEligibilityBoundary() =
        runBlocking<Unit> {
            val db = open()
            try {
                db.libraryDao().upsertMedia(listOf(media(1)))
                val dao = db.documentDao()
                val revision = db.momentDiscoveryDao()
                var expected = revision.discoveryRevision()
                dao.put(DocumentAnnotationEntity(volume, 1, "Excluded", 1))
                assertEquals(expected, revision.discoveryRevision())
                dao.put(DocumentAnnotationEntity(volume, 1, "Receipt", 2))
                assertEquals(++expected, revision.discoveryRevision())
                dao.put(DocumentAnnotationEntity(volume, 1, "Receipt", 3))
                assertEquals(expected, revision.discoveryRevision())
                dao.put(DocumentAnnotationEntity(volume, 1, "Ticket", 4))
                assertEquals(expected, revision.discoveryRevision())
                dao.put(DocumentAnnotationEntity(volume, 1, "Excluded", 5))
                assertEquals(++expected, revision.discoveryRevision())
                dao.clear(volume, 1)
                assertEquals(expected, revision.discoveryRevision())
                dao.put(DocumentAnnotationEntity(volume, 1, "Note", 6))
                assertEquals(++expected, revision.discoveryRevision())
                dao.clear(volume, 1)
                assertEquals(++expected, revision.discoveryRevision())
            } finally {
                db.close()
            }
        }

    @Test
    fun generateIfNeededReconcilesAfterClassificationAndClearButSkipsTimestampOnlyChange() =
        runBlocking<Unit> {
            val db = open()
            try {
                db.libraryDao().upsertMedia((1L..8L).map { media(it) })
                val repo = MomentRepository(db)
                assertNotNull(repo.generateIfNeeded(64))
                assertNull(repo.generateIfNeeded(64))
                val documents = GalleryDocumentRepository(db)
                documents.classify(listOf(key(1)), DocumentCategory.Note)
                assertNotNull(repo.generateIfNeeded(64))
                assertNull(repo.generateIfNeeded(64))
                assertFalse(
                    repo
                        .summaries()
                        .first()
                        .flatMap { repo.members(it.moment.momentId) }
                        .any { it.media.mediaStoreId == 1L }
                )
                db.documentDao().put(DocumentAnnotationEntity(volume, 1, "Note", 99999))
                assertNull(repo.generateIfNeeded(64))
                documents.clearClassification(key(1))
                assertNotNull(repo.generateIfNeeded(64))
                assertTrue(
                    repo
                        .summaries()
                        .first()
                        .flatMap { repo.members(it.moment.momentId) }
                        .any { it.media.mediaStoreId == 1L }
                )
                assertNull(repo.generateIfNeeded(64))
            } finally {
                db.close()
            }
        }
}
