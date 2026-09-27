package com.librestatic.lightforge.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class MemoryExclusionRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val volume = "volume:with:colon"
    private val momentId = "saved-memory"

    private fun media(id: Long, v: String = volume, day: Int = id.toInt()) =
        MediaItemEntity(
            v,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            120,
            80,
            0,
            0,
            Instant.parse("2026-06-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli(),
            1,
            1,
            Instant.parse("2026-06-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli(),
            1,
            1,
            1,
            "Camera",
            "DCIM/Camera/",
            false,
            false,
            true,
            1,
        )

    private suspend fun seed(db: GalleryDatabase) {
        db.libraryDao().upsertMedia((1L..3L).map { media(it) })
        db.momentDao()
            .upsertMoment(
                MomentEntity(
                    momentId,
                    "USER",
                    "SAVED",
                    "fixture",
                    1,
                    2,
                    "Keep title",
                    "USER",
                    true,
                    1,
                    2,
                )
            )
        db.momentDao()
            .insertMembers(
                (1L..3L).map {
                    MomentMemberEntity(momentId, it.toInt() - 1, volume, it, 1, "USER", 1f)
                }
            )
        db.momentDao().upsertCover(MomentCoverEntity(momentId, volume, 1, true))
    }

    private fun fixture(
        block: suspend (GalleryDatabase, MemoryExclusionRepository, MomentRepository) -> Unit
    ) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            seed(db)
            block(db, MemoryExclusionRepository(db), MomentRepository(db))
        } finally {
            db.close()
        }
    }

    private fun date(start: String = "2026-06-01", end: String = start) =
        MemoryDateRange(LocalDate.parse(start), LocalDate.parse(end), "UTC")

    private suspend fun person(
        db: GalleryDatabase,
        id: Long,
        cluster: String = "person",
        version: String = "v1",
        v: String = volume,
        generation: Long = 1,
    ) {
        db.personDao()
            .upsertCluster(
                PersonClusterEntity(
                    cluster,
                    version,
                    ByteArray(128),
                    1,
                    "Known person",
                    false,
                    true,
                    1,
                    1,
                )
            )
        db.libraryDao()
            .replaceFaceDetection(
                FaceDetectionRunEntity(v, id, generation, "face", 1, 1),
                listOf(
                    DetectedFaceEntity(
                        v,
                        id,
                        0,
                        "face",
                        100,
                        100,
                        500,
                        500,
                        50,
                        50,
                        550,
                        550,
                        0f,
                        0f,
                        0f,
                        .9f,
                        "[]",
                    )
                ),
            )
        db.libraryDao()
            .upsertFaceEmbeddings(
                listOf(FaceEmbeddingEntity(v, id, 0, "face", "embedding", ByteArray(128), 1))
            )
        db.personDao()
            .upsertMembership(PersonMembershipEntity(v, id, 0, cluster, version, "user", .9f, 1))
    }

    @Test
    fun dateRuleFiltersMembersSummaryAndCoverWithoutDeletingReferences() = fixture { db, r, m ->
        val before = (1L..3L).map { db.libraryDao().media(volume, it) }
        val rule = r.addDate(date()).rule
        assertEquals(listOf(2L, 3L), m.members(momentId).map { it.media.mediaStoreId })
        val s = m.summaries().first().single()
        assertEquals(2L, s.memberCount)
        assertEquals(2L, s.coverMediaStoreId)
        assertFalse(m.setCover(momentId, MediaKey(volume, 1)))
        assertEquals(3, db.momentDao().allMembers(momentId).size)
        assertEquals(before, (1L..3L).map { db.libraryDao().media(volume, it) })
        assertTrue(r.removeDate(rule.ruleId))
        assertEquals(3, m.members(momentId).size)
        assertEquals(1L, m.summaries().first().single().coverMediaStoreId)
    }

    @Test
    fun singleRemainingPhotoStaysDiscoverableAndAllHiddenStoryIsReversible() = fixture { db, r, m ->
        val partial = r.addDate(date("2026-06-01", "2026-06-02")).rule
        assertEquals(1L, m.summaries().first().single().memberCount)
        val all = r.addDate(date("2026-06-03")).rule
        assertTrue(m.summaries().first().isEmpty())
        assertTrue(m.members(momentId).isEmpty())
        assertEquals(3, db.momentDao().allMembers(momentId).size)
        assertEquals("Keep title", db.momentDao().moment(momentId)?.title)
        r.removeDate(partial.ruleId)
        r.removeDate(all.ruleId)
        assertEquals(3, m.members(momentId).size)
    }

    @Test
    fun overlappingDatesAndPersonRulesCombineWithoutCrossRuleUndo() = fixture { db, r, m ->
        person(db, 2)
        val personRule = r.addPerson("person", "v1").rule
        val d1 = r.addDate(date("2026-06-01", "2026-06-02")).rule
        val d2 = r.addDate(date("2026-06-02", "2026-06-03")).rule
        assertTrue(m.members(momentId).isEmpty())
        r.removeDate(d1.ruleId)
        assertEquals(listOf(1L), m.members(momentId).map { it.media.mediaStoreId })
        r.removeDate(d2.ruleId)
        assertEquals(listOf(1L, 3L), m.members(momentId).map { it.media.mediaStoreId })
        r.removePerson(personRule.ruleId)
        assertEquals(3, m.members(momentId).size)
    }

    @Test
    fun duplicateCreationAndOldReceiptCannotRemoveLaterDecision() = fixture { db, r, m ->
        val first = r.addDate(date())
        val duplicate = r.addDate(date())
        assertTrue(first.created)
        assertFalse(duplicate.created)
        assertEquals(first.rule, duplicate.rule)
        r.removeDate(first.rule.ruleId)
        val second = r.addDate(date()).rule
        assertNotEquals(first.rule.ruleId, second.ruleId)
        assertFalse(r.removeDate(first.rule.ruleId))
        assertEquals(2, m.members(momentId).size)
        person(db, 1)
        val a = r.addPerson("person", "v1")
        assertFalse(r.addPerson("person", "v1").created)
        r.removePerson(a.rule.ruleId)
        val b = r.addPerson("person", "v1").rule
        assertNotEquals(a.rule.ruleId, b.ruleId)
        assertFalse(r.removePerson(a.rule.ruleId))
    }

    @Test
    fun currentPersonMatchesAreCapturedAndSurviveAnalysisDeletion() = fixture { db, r, m ->
        person(db, 1)
        val rule = r.addPerson("person", "v1").rule
        assertEquals(1L, r.knownSourceCount(rule.ruleId).first())
        assertEquals(2, m.members(momentId).size)
        db.openHelper.writableDatabase.execSQL("DELETE FROM person_clusters")
        db.openHelper.writableDatabase.execSQL("DELETE FROM face_detection_runs")
        assertEquals(1, r.people().first().size)
        assertEquals(listOf(2L, 3L), m.members(momentId).map { it.media.mediaStoreId })
        r.removePerson(rule.ruleId)
        assertEquals(3, m.members(momentId).size)
    }

    @Test
    fun incomingCurrentMatchIsHiddenAndRememberedBeforeDelivery() = fixture { db, r, m ->
        person(db, 1)
        val rule = r.addPerson("person", "v1").rule
        person(db, 2)
        assertEquals(listOf(3L), m.observeMembers(momentId).first().map { it.media.mediaStoreId })
        assertEquals(2L, r.knownSourceCount(rule.ruleId).first())
        db.openHelper.writableDatabase.execSQL("DELETE FROM person_clusters")
        assertEquals(listOf(3L), m.members(momentId).map { it.media.mediaStoreId })
    }

    @Test
    fun staleDetectionAndDifferentClusterAlgorithmDoNotHideUnmatchedPhotos() = fixture { db, r, m ->
        person(db, 1, generation = 0)
        r.addPerson("person", "v1")
        assertEquals(3, m.members(momentId).size)
        person(db, 2, version = "v2")
        assertEquals(3, m.members(momentId).size)
        person(db, 1)
        assertEquals(listOf(2L, 3L), m.members(momentId).map { it.media.mediaStoreId })
    }

    @Test
    fun sourceDeletionCascadesKnownMatchAndReusedIdIsNotHidden() = fixture { db, r, m ->
        db.libraryDao().upsertMedia(listOf(media(1, "other-volume")))
        person(db, 1)
        val rule = r.addPerson("person", "v1").rule
        assertEquals(1L, r.knownSourceCount(rule.ruleId).first())
        db.libraryDao().deleteMedia(volume, 1)
        db.libraryDao().upsertMedia(listOf(media(1)))
        assertEquals(0L, r.knownSourceCount(rule.ruleId).first())
        db.momentDao()
            .insertMembers(listOf(MomentMemberEntity(momentId, 0, volume, 1, 1, "USER", 1f)))
        assertEquals(3, m.members(momentId).size)
        assertNotNull(db.libraryDao().media("other-volume", 1))
    }

    @Test
    fun reorderedVisiblePhotosKeepDateAndPersonExcludedSlots() = fixture { db, r, m ->
        r.addDate(date("2026-06-02"))
        val before = db.momentDao().allMembers(momentId)
        assertTrue(m.reorderVisible(momentId, listOf(MediaKey(volume, 3), MediaKey(volume, 1))))
        assertEquals(before[1], db.momentDao().allMembers(momentId)[1])
        assertEquals(listOf(3L, 1L), m.members(momentId).map { it.media.mediaStoreId })
    }

    @Test
    fun archivesAndSourceEligibilityApplyAlongsideRules() = fixture { db, r, m ->
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO archived_media (volumeName,mediaStoreId,archivedAtMillis) VALUES (?,1,1)",
            arrayOf(volume),
        )
        db.libraryDao()
            .upsertMedia(
                listOf(media(2).copy(isAccessible = false), media(3).copy(isTrashed = true))
            )
        assertTrue(m.members(momentId).isEmpty())
        assertTrue(m.summaries().first().isEmpty())
        assertEquals(3, db.momentDao().allMembers(momentId).size)
    }

    @Test
    fun missingPersonAndSharedRuleLimitRejectAtomically() = fixture { db, r, m ->
        var missing = false
        try {
            r.addPerson("missing", "v1")
        } catch (_: MemoryPersonUnavailable) {
            missing = true
        }
        assertTrue(missing)
        repeat(200) { n ->
            val day = LocalDate.of(2020, 1, 1).plusDays(n.toLong())
            r.addDate(MemoryDateRange(day, day, "UTC"))
        }
        assertEquals(200, db.memoryExclusionDao().ruleCount())
        assertFalse(
            r.addDate(MemoryDateRange(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1), "UTC"))
                .created
        )
        person(db, 1)
        var limit = false
        try {
            r.addPerson("person", "v1")
        } catch (_: MemoryRuleLimitReached) {
            limit = true
        }
        assertTrue(limit)
        assertTrue(r.people().first().isEmpty())
        assertEquals(3, m.members(momentId).size)
    }

    @Test
    fun rulesInvalidateOtherConnectionAndSurviveReopen() = runBlocking {
        val name = "memory-rules-${UUID.randomUUID()}.db"
        val db = GalleryDatabaseFactory.open(context, name)
        val writer = GalleryDatabaseFactory.open(context, name)
        try {
            seed(db)
            val r = MemoryExclusionRepository(writer)
            val m = MomentRepository(db)
            val events = Channel<List<MomentMemberRow>>(Channel.UNLIMITED)
            val job = launch { m.observeMembers(momentId).collect { events.send(it) } }
            try {
                assertEquals(3, withTimeout(10000) { events.receive() }.size)
                r.addDate(date())
                withTimeout(10000) { while (events.receive().size != 2) {} }
                person(writer, 2)
                r.addPerson("person", "v1")
                withTimeout(10000) { while (events.receive().size != 1) {} }
            } finally {
                job.cancelAndJoin()
            }
            writer.close()
            db.close()
            val reopened = GalleryDatabaseFactory.open(context, name)
            try {
                assertEquals(
                    listOf(3L),
                    MomentRepository(reopened).members(momentId).map { it.media.mediaStoreId },
                )
                assertEquals(2, reopened.memoryExclusionDao().ruleCount())
            } finally {
                reopened.close()
            }
        } finally {
            writer.close()
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun generationKeepsHiddenReferencesSoRemovingRulesRestoresNewStories() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val start = media(1).timelineSortMillis
            db.libraryDao()
                .upsertMedia(
                    (1L..8L).map { media(it, day = 1).copy(timelineSortMillis = start + it * 1000) }
                )
            val r = MemoryExclusionRepository(db)
            val rule = r.addDate(date()).rule
            val m = MomentRepository(db)
            assertTrue(m.generate(64).complete)
            assertTrue(m.summaries().first().isEmpty())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM moment_members").use {
                assertTrue(it.moveToFirst())
                assertEquals(8, it.getInt(0))
            }
            r.removeDate(rule.ruleId)
            assertEquals(8L, m.summaries().first().single().memberCount)
            assertNull(
                m.generateIfNeeded(64)
            ) // No forced regeneration needed to recover references.
        } finally {
            db.close()
        }
    }

    @Test
    fun persistedDateFilterUsesHalfOpenBoundariesExactly() = fixture { db, r, m ->
        val bounds = date().bounds()
        val times = listOf(bounds.first - 1, bounds.first, bounds.second - 1, bounds.second)
        db.libraryDao()
            .upsertMedia(
                times.mapIndexed { i, time ->
                    media(i.toLong() + 1).copy(timelineSortMillis = time)
                }
            )
        db.momentDao()
            .insertMembers(listOf(MomentMemberEntity(momentId, 3, volume, 4, 1, "USER", 1f)))
        r.addDate(date())
        assertEquals(listOf(1L, 4L), m.members(momentId).map { it.media.mediaStoreId })
    }

    @Test
    fun incompatibleDetectionAndEmbeddingModelsDoNotCreateKnownMatches() = fixture { db, r, m ->
        person(db, 1)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE face_embeddings SET detectionModelVersion='incompatible'"
        )
        val rule = r.addPerson("person", "v1").rule
        assertEquals(3, m.members(momentId).size)
        assertEquals(0L, r.knownSourceCount(rule.ruleId).first())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE face_embeddings SET detectionModelVersion='face'"
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE detected_faces SET modelVersion='incompatible'"
        )
        assertEquals(3, m.members(momentId).size)
        assertEquals(0L, r.knownSourceCount(rule.ruleId).first())
        db.openHelper.writableDatabase.execSQL("UPDATE detected_faces SET modelVersion='face'")
        assertEquals(2, m.members(momentId).size)
        assertEquals(1L, r.knownSourceCount(rule.ruleId).first())
    }
}
