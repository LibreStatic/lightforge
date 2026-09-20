package com.ugallery.core.data

import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.PortableOrganizationCodec
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MomentParticipantsRepositoryDeviceTest {
    @Test
    fun manualEmptyCasCurrentFacesPrivacyAndReopenRemainIndependent() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "participants-${UUID.randomUUID()}.db"
            val v = "participants:fixture"
            var db = GalleryDatabaseFactory.open(context, name)
            fun media(id: Long) =
                MediaItemEntity(
                    v,
                    id,
                    1,
                    "image/jpeg",
                    "IMG_$id.jpg",
                    1000,
                    4000,
                    3000,
                    0,
                    0,
                    id * 1000,
                    id,
                    id,
                    id * 1000,
                    1,
                    2,
                    1,
                    "Camera",
                    "DCIM/Camera/",
                    false,
                    false,
                    true,
                    1,
                )
            suspend fun person(id: Long, cluster: String, generation: Long = 2) {
                db.personDao()
                    .upsertCluster(
                        PersonClusterEntity(
                            cluster,
                            "fixture-v1",
                            ByteArray(128) { 7 },
                            1,
                            "Sensitive name $cluster",
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
                        listOf(
                            FaceEmbeddingEntity(v, id, 0, "face", "embedding", ByteArray(128), 1)
                        )
                    )
                db.personDao()
                    .upsertMembership(
                        PersonMembershipEntity(v, id, 0, cluster, "fixture-v1", "user", .9f, 1)
                    )
            }
            fun globalSnapshot(): List<List<String?>> =
                listOf(
                        "media_items",
                        "person_clusters",
                        "person_memberships",
                        "face_detection_runs",
                        "detected_faces",
                        "face_embeddings",
                    )
                    .flatMap { table ->
                        db.openHelper.readableDatabase
                            .query("SELECT * FROM $table ORDER BY rowid")
                            .use { c ->
                                buildList {
                                    while (c.moveToNext()) add(
                                        (0 until c.columnCount).map { col ->
                                            if (c.isNull(col)) null
                                            else if (
                                                c.getType(col) ==
                                                    android.database.Cursor.FIELD_TYPE_BLOB
                                            )
                                                c.getBlob(col).joinToString("") {
                                                    "%02x".format(it)
                                                }
                                            else c.getString(col)
                                        }
                                    )
                                }
                            }
                    }
            try {
                db.libraryDao().upsertMedia((1L..4L).map(::media))
                listOf("first", "other").forEach { id ->
                    db.momentDao()
                        .upsertMoment(
                            MomentEntity(
                                id,
                                "MANUAL",
                                "SAVED",
                                "fixture",
                                1,
                                5,
                                id,
                                "USER",
                                true,
                                1,
                                1,
                            )
                        )
                }
                db.momentDao()
                    .insertMembers(
                        listOf(
                            MomentMemberEntity("first", 0, v, 1, 2, "USER", 1f),
                            MomentMemberEntity("first", 1, v, 3, 2, "USER", 1f),
                            MomentMemberEntity("other", 0, v, 2, 2, "USER", 1f),
                        )
                    )
                person(1, "sensitive-a")
                person(2, "sensitive-b")
                person(3, "sensitive-stale", generation = 1)
                person(4, "sensitive-hidden")
                db.personDao().setHidden("sensitive-hidden", true, 2)
                var repo = MomentParticipantsRepository(db, "fixture-v1") { 99 }
                val first = repo.snapshot("first")!!
                assertFalse(first.manual)
                assertEquals(0L, first.revision)
                assertEquals(setOf("sensitive-a"), first.automaticIds)
                assertEquals(first.automaticIds, first.selectedIds)
                assertEquals(
                    setOf("sensitive-a", "sensitive-b"),
                    first.people.map { it.clusterId }.toSet(),
                )
                assertEquals(setOf("sensitive-b"), repo.snapshot("other")!!.selectedIds)
                assertEquals(
                    2L,
                    first.people.single { it.clusterId == "sensitive-a" }.coverGenerationModified,
                )
                assertEquals(
                    2L,
                    first.people.single { it.clusterId == "sensitive-b" }.coverGenerationModified,
                )
                val originalGlobal = globalSnapshot()
                val events = Channel<MomentParticipantsState?>(Channel.UNLIMITED)
                val watcher = launch { repo.observe("first").collect { events.send(it) } }
                try {
                    withTimeout(10000) { assertFalse(events.receive()!!.manual) }
                    assertEquals(
                        MomentParticipantsWrite.Saved,
                        repo.apply("first", 0, true, emptySet()),
                    )
                    withTimeout(10000) { while (events.receive()?.manual != true) {} }
                    val empty = repo.snapshot("first")!!
                    assertTrue(empty.manual)
                    assertTrue(empty.selectedIds.isEmpty())
                    assertEquals(setOf("sensitive-a"), empty.automaticIds)
                    assertEquals(
                        MomentParticipantsWrite.Conflict,
                        repo.apply("first", 0, true, setOf("sensitive-a")),
                    )
                    assertEquals(empty, repo.snapshot("first"))
                    assertEquals(
                        MomentParticipantsWrite.Saved,
                        repo.apply(
                            "first",
                            empty.revision,
                            true,
                            setOf("sensitive-a", "sensitive-b"),
                        ),
                    )
                    var state = repo.snapshot("first")!!
                    assertEquals(
                        MomentParticipantsWrite.Saved,
                        repo.apply("first", state.revision, true, setOf("sensitive-b")),
                    )
                    state = repo.snapshot("first")!!
                    assertEquals(setOf("sensitive-b"), state.selectedIds)
                    assertEquals(originalGlobal, globalSnapshot())
                    assertFalse(repo.snapshot("other")!!.manual)
                    assertEquals(0L, repo.snapshot("other")!!.revision)
                    val portable =
                        PortableOrganizationRepository(db)
                            .export(
                                (1L..4L).map { id ->
                                    PortableSourceBinding(
                                        UUID.randomUUID().toString(),
                                        MediaKey(v, id),
                                        2,
                                        "a".repeat(64),
                                        1000,
                                    )
                                }
                            )
                    val encoded =
                        PortableOrganizationCodec.encode(portable).toString(Charsets.ISO_8859_1)
                    listOf(
                            "sensitive-a",
                            "sensitive-b",
                            "sensitive-hidden",
                            "sensitive-stale",
                            "Sensitive name",
                        )
                        .forEach { assertFalse(encoded.contains(it)) }
                    assertEquals(state, repo.snapshot("first"))
                    assertEquals(
                        MomentParticipantsWrite.Unavailable,
                        repo.apply("first", state.revision, true, setOf("missing")),
                    )
                    db.personDao().setHidden("sensitive-b", true, 3)
                    val hidden = repo.snapshot("first")!!
                    assertEquals(setOf("sensitive-b"), hidden.selectedIds)
                    assertFalse(hidden.people.any { it.clusterId == "sensitive-b" })
                    assertEquals(
                        MomentParticipantsWrite.Unavailable,
                        repo.apply("first", hidden.revision, true, hidden.selectedIds),
                    )
                } finally {
                    watcher.cancel()
                    events.close()
                }
                val persisted = repo.snapshot("first")!!
                db.close()
                db = GalleryDatabaseFactory.open(context, name)
                repo = MomentParticipantsRepository(db, "fixture-v1") { 100 }
                assertEquals(persisted, repo.snapshot("first"))
                db.personDao().deleteCluster("sensitive-b")
                val deleted = repo.snapshot("first")!!
                assertTrue(deleted.manual)
                assertTrue(deleted.selectedIds.isEmpty())
                assertEquals(
                    MomentParticipantsWrite.Unavailable,
                    repo.apply("first", deleted.revision, true, setOf("sensitive-b")),
                )
                assertEquals(
                    MomentParticipantsWrite.Saved,
                    repo.apply("first", deleted.revision, false, emptySet()),
                )
                val automatic = repo.snapshot("first")!!
                assertFalse(automatic.manual)
                assertEquals(setOf("sensitive-a"), automatic.selectedIds)
                assertEquals(
                    MomentParticipantsWrite.Unavailable,
                    repo.apply("absent", 0, true, emptySet()),
                )
                assertEquals("first", db.momentDao().moment("first")!!.title)
                assertEquals(
                    listOf(1L, 3L),
                    db.momentDao().allMembers("first").map { it.mediaStoreId },
                )
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
}
