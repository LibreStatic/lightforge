package com.ugallery.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualMomentParticipantsDeviceTest {
    @Test
    fun consentedDocumentParticipantIsScopedAndPersonExclusionStillRejectsCreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val volume = "manual-participant-fixture"
        val key = MediaKey(volume, 1)
        val cluster = "document-only-person"
        val version = "fixture-v1"
        try {
            val media = MediaItemEntity(
                volume, 1, 1, "image/jpeg", "Receipt.jpg", 100, 120, 80, 0, 0,
                1_000, 1, 1, 1_000, 1, 2, 1, "Camera", "DCIM/Camera/",
                false, false, true, 1,
            )
            db.libraryDao().upsertMedia(listOf(media))
            db.documentDao().put(DocumentAnnotationEntity(volume, 1, "Receipt", 42))
            db.personDao().upsertCluster(PersonClusterEntity(cluster, version, ByteArray(128),
                1, "Document participant", false, true, 1, 1))
            db.libraryDao().replaceFaceDetection(FaceDetectionRunEntity(volume, 1, 2, "face", 1, 1),
                listOf(DetectedFaceEntity(volume, 1, 0, "face", 100, 100, 500, 500,
                    50, 50, 550, 550, 0f, 0f, 0f, .9f, "[]")))
            db.libraryDao().upsertFaceEmbeddings(listOf(
                FaceEmbeddingEntity(volume, 1, 0, "face", "embedding", ByteArray(128), 1)))
            db.personDao().upsertMembership(
                PersonMembershipEntity(volume, 1, 0, cluster, version, "user", .9f, 1))
            val classification = db.documentDao().get(volume, 1)

            val manual = ManualMomentRepository(db) { 100L }
            val draft = manual.prepare(listOf(key))
            val id = manual.create(draft, listOf(key), "Explicit document memory", true)
            val automaticId = UUID.randomUUID().toString()
            db.momentDao().upsertMoment(requireNotNull(db.momentDao().moment(id)).copy(
                momentId = automaticId, origin = "AUTO", algorithmVersion = "fixture",
                includeSpecialMedia = false))
            db.momentDao().insertMembers(db.momentDao().allMembers(id).map {
                it.copy(momentId = automaticId, origin = "AUTO")
            })

            val dao = db.momentParticipantsDao()
            assertTrue(dao.people(version).isEmpty()) // Global selector stays document-filtered.
            assertTrue(dao.people(version, automaticId).isEmpty())
            assertTrue(dao.automatic(automaticId, version).isEmpty())
            assertEquals(listOf(cluster), dao.automatic(id, version))
            val scopedPerson = dao.people(version, id).single()
            assertEquals(cluster, scopedPerson.clusterId)
            assertEquals(1, scopedPerson.memberCount)
            assertEquals(volume, scopedPerson.coverVolumeName)
            assertEquals(1L, scopedPerson.coverMediaStoreId)
            assertEquals(2L, scopedPerson.coverGenerationModified)
            assertEquals(1, db.momentDao().members(id).size)
            assertTrue(db.momentDao().members(automaticId).isEmpty())
            assertEquals(1L, db.momentDao().summarySnapshot(20).single().memberCount)

            val participants = MomentParticipantsRepository(db, version) { 101L }
            val initial = requireNotNull(participants.snapshot(id))
            assertEquals(setOf(cluster), initial.automaticIds)
            assertEquals(initial.automaticIds, initial.selectedIds)
            assertTrue(initial.people.map { it.clusterId }.containsAll(initial.selectedIds))
            assertEquals(MomentParticipantsWrite.Saved,
                participants.apply(id, initial.revision, true, initial.automaticIds))
            val selected = requireNotNull(participants.snapshot(id))
            assertTrue(selected.manual)
            assertEquals(setOf(cluster), selected.selectedIds)
            assertTrue(selected.people.map { it.clusterId }.containsAll(selected.selectedIds))
            // Reapplying the current reviewed selection must not report unavailable or lose it.
            assertEquals(MomentParticipantsWrite.Saved,
                participants.apply(id, selected.revision, true, selected.selectedIds))
            assertEquals(setOf(cluster), participants.snapshot(id)?.selectedIds)
            assertTrue(requireNotNull(participants.snapshot(automaticId)).people.isEmpty())

            val nextDraft = manual.prepare(listOf(key))
            MemoryExclusionRepository(db).addPerson(cluster, version)
            try {
                manual.create(nextDraft, listOf(key), "Must stay excluded", true)
                fail("Explicit documents never override a person exclusion")
            } catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            assertNull(db.momentDao().moment(nextDraft.id))
            assertTrue(db.momentDao().allMembers(nextDraft.id).isEmpty())
            assertTrue(db.momentDao().members(id).isEmpty())
            assertTrue(dao.automatic(id, version).isEmpty())
            assertTrue(dao.people(version, id).isEmpty())
            assertEquals(classification, db.documentDao().get(volume, 1))
            assertEquals(media, db.libraryDao().media(volume, 1))
        } finally { db.close() }
    }
}
