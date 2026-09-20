package com.ugallery.core.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualMomentRepositoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val volume = "manual-fixture"
    private fun key(id: Long) = MediaKey(volume, id)
    private fun media(id: Long) = MediaItemEntity(
        volumeName = volume, mediaStoreId = id, mediaType = 1, mimeType = "image/jpeg",
        displayName = "photo-$id.jpg", sizeBytes = 100, width = 120, height = 80,
        durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli(),
        dateAddedSeconds = 1, dateModifiedSeconds = 1,
        timelineSortMillis = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli() + id,
        generationAdded = 7, generationModified = 9, bucketId = 1,
        bucketDisplayName = "Camera", relativePath = "DCIM/Camera/",
        isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )

    private fun fixture(block: suspend (GalleryDatabase, ManualMomentRepository) -> Unit) = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            database.libraryDao().upsertMedia(listOf(media(1), media(2)))
            block(database, ManualMomentRepository(database) { 123L })
        } finally { database.close() }
    }

    @Test
    fun explicitDocumentPermissionCreatesOnlyManualStoryAndRepeatPreservesLaterEdits() = fixture { db, repo ->
        db.documentDao().put(DocumentAnnotationEntity(volume, 1, "Receipt", 42))
        val originalClassification = db.documentDao().get(volume, 1)
        val draft = repo.prepare(listOf(key(1), key(2)))
        assertTrue(draft.sources.first().requiresSpecialMedia)
        assertFalse(draft.sources.last().requiresSpecialMedia)
        reject { repo.create(draft, listOf(key(2), key(1)), "Trip", false) }
        assertNull(db.momentDao().moment(draft.id))
        assertTrue(db.momentDao().allMembers(draft.id).isEmpty())

        val id = repo.create(draft, listOf(key(2), key(1)), "  Trip  ", true)
        val created = requireNotNull(db.momentDao().moment(id))
        assertEquals("MANUAL", created.origin)
        assertEquals("SAVED", created.state)
        assertEquals("Trip", created.title)
        assertEquals("USER", created.titleMode)
        assertTrue(created.isUserEdited && created.includeSpecialMedia)
        assertEquals(listOf(2L, 1L), db.momentDao().members(id).map { it.member.mediaStoreId })
        assertEquals(2L, db.momentDao().summarySnapshot(20).first { it.moment.momentId == id }.coverMediaStoreId)

        val automatic = UUID.randomUUID().toString()
        db.momentDao().upsertMoment(created.copy(momentId = automatic, origin = "AUTO",
            algorithmVersion = "fixture", includeSpecialMedia = false))
        db.momentDao().insertMembers(db.momentDao().allMembers(id).map { it.copy(momentId = automatic) })
        assertEquals(listOf(2L), db.momentDao().members(automatic).map { it.member.mediaStoreId })
        assertEquals(originalClassification, db.documentDao().get(volume, 1))

        db.momentDao().rename(id, "Later user title", 456)
        assertEquals(id, repo.create(draft, listOf(key(2), key(1)), "Trip", true))
        assertEquals("Later user title", db.momentDao().moment(id)?.title)
        assertEquals(2, db.momentDao().allMembers(id).size)
        reject { repo.create(draft, listOf(key(2), key(1)), "Different request", true) }
        reject { repo.create(draft, listOf(key(1), key(2)), "Trip", true) }
        reject { repo.create(draft, listOf(key(2), key(1)), "Trip", false) }
        assertEquals(originalClassification, db.documentDao().get(volume, 1))
    }

    @Test
    fun staleAndIneligibleSourceRejectWholeDraftWithoutDroppingGoodMember() = fixture { db, repo ->
        val original = media(2)
        for (variant in listOf("added", "modified", "trash", "access", "video", "archive", "date")) {
            val draft = repo.prepare(listOf(key(1), key(2)))
            var dateRule: String? = null
            when (variant) {
                "added" -> db.libraryDao().upsertMedia(listOf(original.copy(generationAdded = 8)))
                "modified" -> db.libraryDao().upsertMedia(listOf(original.copy(generationModified = 10)))
                "trash" -> db.libraryDao().upsertMedia(listOf(original.copy(isTrashed = true)))
                "access" -> db.libraryDao().upsertMedia(listOf(original.copy(isAccessible = false)))
                "video" -> db.libraryDao().upsertMedia(listOf(original.copy(mediaType = 3, mimeType = "video/mp4")))
                "archive" -> db.libraryDao().upsertArchived(ArchivedMediaEntity(volume, 2, 1))
                "date" -> {
                    val rule = MemoryExclusionRepository(db).addDate(MemoryDateRange(
                        LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), "UTC"))
                    dateRule = rule.rule.ruleId
                }
            }
            try {
                reject { repo.create(draft, listOf(key(1), key(2)), "Atomic", true) }
                assertNull("$variant must not create a partial story", db.momentDao().moment(draft.id))
                assertTrue(db.momentDao().allMembers(draft.id).isEmpty())
                if (variant !in listOf("added", "modified")) reject { repo.prepare(listOf(key(2))) }
            } finally {
                db.libraryDao().upsertMedia(listOf(original))
                db.libraryDao().deleteArchived(volume, 2)
                dateRule?.let { MemoryExclusionRepository(db).removeDate(it) }
            }
        }
    }

    @Test
    fun screenshotConsentSubsetOrderAndEmptyAutomaticTitleAreExplicit() = fixture { db, repo ->
        db.libraryDao().upsertMedia(listOf(media(1).copy(displayName = "Screenshot_2026.jpg")))
        val draft = repo.prepare(listOf(key(1), key(2)))
        assertTrue(draft.sources.first().requiresSpecialMedia)
        reject { repo.create(draft, listOf(key(1)), "", false) }
        reject { repo.create(draft, listOf(key(2), key(2)), "", false) }
        reject { repo.create(draft, listOf(key(3)), "", false) }
        reject { repo.create(draft, emptyList(), "", false) }
        reject { repo.create(draft, listOf(key(2)), "x".repeat(121), false) }
        reject { repo.create(draft.copy(id = "not-a-uuid"), listOf(key(2)), "", false) }
        reject { repo.prepare(listOf(key(1), key(1))) }
        reject { repo.prepare(emptyList()) }
        reject { repo.prepare((1L..121L).map(::key)) }
        assertNull(db.momentDao().moment(draft.id))
        val id = repo.create(draft, listOf(key(2)), "   ", false)
        val saved = requireNotNull(db.momentDao().moment(id))
        assertNull(saved.title)
        assertEquals("AUTO", saved.titleMode)
        assertFalse(saved.includeSpecialMedia)
        assertEquals(listOf(2L), db.momentDao().allMembers(id).map { it.mediaStoreId })
        assertEquals("Screenshot_2026.jpg", db.libraryDao().media(volume, 1)?.displayName)
    }

    @Test
    fun missingReceiptIsReadOnlyAndNeverCreatesEvenWithUnavailableSources() = fixture { db, repo ->
        val draft = repo.prepare(listOf(key(1), key(2)))
        db.libraryDao().deleteMedia(volume, 1)
        db.libraryDao().deleteMedia(volume, 2)
        readOnly(db) {
            assertEquals(ManualMomentCommitStatus.Missing,
                repo.committedStatus(draft, listOf(key(2), key(1)), "  Trip  ", false))
            assertNull(db.momentDao().moment(draft.id))
            assertTrue(db.momentDao().allMembers(draft.id).isEmpty())
            assertTrue(coverRows(db, draft.id).isEmpty())
        }
    }

    @Test
    fun committedReceiptPreservesLaterTitleMemberOrderAndCoverEdits() = fixture { db, repo ->
        val draft = repo.prepare(listOf(key(1), key(2)))
        val id = repo.create(draft, listOf(key(1), key(2)), "  Trip  ", false)
        val original = requireNotNull(db.momentDao().moment(id))
        db.momentDao().rename(id, "Later user title", 456)
        val members = db.momentDao().allMembers(id).reversed().mapIndexed { ordinal, row -> row.copy(ordinal = ordinal) }
        db.momentDao().deleteMembers(id)
        db.momentDao().insertMembers(members)
        db.momentDao().upsertCover(MomentCoverEntity(id, volume, 2, true))
        val edited = requireNotNull(db.momentDao().moment(id))
        val editedCover = coverRows(db, id)
        assertEquals(original.algorithmVersion, edited.algorithmVersion)
        readOnly(db) {
            assertEquals(ManualMomentCommitStatus.Committed,
                repo.committedStatus(draft, listOf(key(1), key(2)), " Trip ", false))
            assertEquals(edited, db.momentDao().moment(id))
            assertEquals(members, db.momentDao().allMembers(id))
            assertEquals(editedCover, coverRows(db, id))
        }
    }

    @Test
    fun differentValidRequestConflictsWithoutOverwritingAnyCommittedState() = fixture { db, repo ->
        val draft = repo.prepare(listOf(key(1), key(2)))
        val id = repo.create(draft, listOf(key(1), key(2)), "Trip", false)
        val saved = db.momentDao().moment(id)
        val members = db.momentDao().allMembers(id)
        val cover = coverRows(db, id)
        readOnly(db) {
            assertEquals(ManualMomentCommitStatus.Conflict,
                repo.committedStatus(draft, listOf(key(2), key(1)), "Trip", false))
            assertEquals(ManualMomentCommitStatus.Conflict,
                repo.committedStatus(draft, listOf(key(1), key(2)), "Different", false))
            assertEquals(ManualMomentCommitStatus.Conflict,
                repo.committedStatus(draft, listOf(key(1), key(2)), "Trip", true))
            assertEquals(ManualMomentCommitStatus.Conflict,
                repo.committedStatus(draft.copy(sources = draft.sources.reversed()), listOf(key(1), key(2)), "Trip", false))
            assertEquals(ManualMomentCommitStatus.Conflict,
                repo.committedStatus(draft.copy(sources = draft.sources.mapIndexed { index, source ->
                    if (index == 0) source.copy(generationAdded = source.generationAdded + 1) else source
                }), listOf(key(1), key(2)), "Trip", false))
            assertEquals(saved, db.momentDao().moment(id))
            assertEquals(members, db.momentDao().allMembers(id))
            assertEquals(cover, coverRows(db, id))
        }
    }

    @Test
    fun nonManualOriginConflictsEvenWhenFingerprintMatches() = fixture { db, repo ->
        val draft = repo.prepare(listOf(key(1), key(2)))
        val id = repo.create(draft, listOf(key(1)), "Trip", false)
        val saved = requireNotNull(db.momentDao().moment(id)).copy(origin = "AUTO")
        db.momentDao().upsertMoment(saved)
        readOnly(db) {
            assertEquals(ManualMomentCommitStatus.Conflict,
                repo.committedStatus(draft, listOf(key(1)), "Trip", false))
            assertEquals(saved, db.momentDao().moment(id))
        }
    }

    @Test
    fun committedReceiptSurvivesAllSourceDisappearanceAndDoesNotRebuildMembers() = fixture { db, repo ->
        val draft = repo.prepare(listOf(key(1), key(2)))
        val id = repo.create(draft, listOf(key(1), key(2)), "Trip", false)
        val saved = db.momentDao().moment(id)
        db.libraryDao().deleteMedia(volume, 1)
        db.libraryDao().deleteMedia(volume, 2)
        assertTrue(db.momentDao().allMembers(id).isEmpty())
        assertTrue(coverRows(db, id).isEmpty())
        readOnly(db) {
            assertEquals(ManualMomentCommitStatus.Committed,
                repo.committedStatus(draft, listOf(key(1), key(2)), "Trip", false))
            assertEquals(saved, db.momentDao().moment(id))
            assertTrue(db.momentDao().allMembers(id).isEmpty())
            assertTrue(coverRows(db, id).isEmpty())
        }
    }

    /** Keep this test on one transaction connection; any attempted write by lookup must fail. */
    private suspend fun readOnly(db: GalleryDatabase, block: suspend () -> Unit) {
        db.withTransaction {
            val connection = db.openHelper.writableDatabase
            fun changes() = connection.query("SELECT total_changes()").use { check(it.moveToFirst()); it.getLong(0) }
            val before = changes()
            connection.execSQL("PRAGMA query_only=ON")
            try {
                connection.query("PRAGMA query_only").use { check(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                block()
                assertEquals("Receipt lookup changed database state", before, changes())
            } finally { connection.execSQL("PRAGMA query_only=OFF") }
        }
    }

    private fun coverRows(db: GalleryDatabase, id: String): List<List<String>> =
        db.openHelper.writableDatabase.query(
            "SELECT momentId,volumeName,mediaStoreId,isUserSelected FROM moment_covers WHERE momentId=?",
            arrayOf<Any>(id),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(List(4) { cursor.getString(it) })
        } }

    private suspend fun reject(block: suspend () -> Any?) {
        try { block(); fail("Expected request rejection") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
}
