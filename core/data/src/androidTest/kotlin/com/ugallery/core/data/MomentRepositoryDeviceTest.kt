package com.ugallery.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MomentRepositoryDeviceTest {
    @Test
    fun generationPreservesCompositeIdentityAndUserEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val rows =
                (0L until 8L).flatMap { id ->
                    listOf(
                        media("external_primary", id, id * 1_000),
                        media("1234-5678", id, id * 1_000 + 1),
                    )
                }
            database.libraryDao().upsertMedia(rows)
            val repository = MomentRepository(database) { 10_000L }
            val progress = repository.generate(pageSize = 64)
            assertTrue(progress.complete)
            val summary = repository.summaries().first().single()
            val members = repository.members(summary.moment.momentId)
            assertEquals(16, members.size)
            assertEquals(
                16,
                members.map { it.media.volumeName to it.media.mediaStoreId }.toSet().size,
            )

            assertTrue(repository.rename(summary.moment.momentId, "My trip"))
            assertTrue(repository.setCover(summary.moment.momentId, MediaKey("1234-5678", 2)))
            repository.restartGeneration()
            repository.generate(pageSize = 64)
            val edited = database.momentDao().moment(summary.moment.momentId)
            assertEquals("My trip", edited?.title)
            assertTrue(edited?.isUserEdited == true)
        } finally {
            database.close()
        }
    }

    @Test
    fun inaccessibleMemberIsHiddenWithoutLosingReferenceAndCannotBeDroppedByReorder() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database =
                Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
            try {
                database
                    .libraryDao()
                    .upsertMedia((0L until 8L).map { media("external_primary", it, it * 1_000) })
                val repository = MomentRepository(database)
                repository.generate(pageSize = 64)
                val id = repository.summaries().first().single().moment.momentId
                val all = database.momentDao().allMembers(id)
                val hidden = all.first()
                database
                    .libraryDao()
                    .upsertMedia(
                        listOf(media(hidden.volumeName, hidden.mediaStoreId, 0, accessible = false))
                    )
                assertEquals(all.size - 1, repository.members(id).size)
                assertEquals(all.size, database.momentDao().allMembers(id).size)
                assertFalse(
                    repository.reorder(
                        id,
                        repository.members(id).map {
                            MediaKey(it.media.volumeName, it.media.mediaStoreId)
                        },
                    )
                )
                assertEquals(all.size, database.momentDao().allMembers(id).size)
            } finally {
                database.close()
            }
        }

    @Test
    fun deletingMediaCascadesGeneratedReferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            database
                .libraryDao()
                .upsertMedia((0L until 8L).map { media("external_primary", it, it * 1_000) })
            val repository = MomentRepository(database)
            repository.generate(pageSize = 64)
            val id = repository.summaries().first().single().moment.momentId
            val before = database.momentDao().allMembers(id)
            val victim = before.first()
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?",
                arrayOf<Any>(victim.volumeName, victim.mediaStoreId),
            )
            assertEquals(before.size - 1, database.momentDao().allMembers(id).size)
            assertNotNull(database.momentDao().moment(id))
        } finally {
            database.close()
        }
    }

    @Test
    fun liveObserversReportRenameSaveReorderVisibilityAndDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val rows = (0L until 8L).map { media("external_primary", it, it * 1_000) }
        try {
            db.libraryDao().upsertMedia(rows)
            val repo = MomentRepository(db)
            repo.generate(64)
            val id = repo.summaries().first().single().moment.momentId
            val moments = Channel<com.ugallery.core.database.MomentEntity?>(Channel.UNLIMITED)
            val members =
                Channel<List<com.ugallery.core.database.MomentMemberRow>>(Channel.UNLIMITED)
            val a = launch { repo.observeMoment(id).collect { moments.send(it) } }
            val b = launch { repo.observeMembers(id).collect { members.send(it) } }
            suspend fun nextMoment(
                predicate: (com.ugallery.core.database.MomentEntity?) -> Boolean
            ) =
                withTimeout(10_000) {
                    var value = moments.receive()
                    while (!predicate(value)) value = moments.receive()
                    value
                }
            suspend fun nextMembers(
                predicate: (List<com.ugallery.core.database.MomentMemberRow>) -> Boolean
            ) =
                withTimeout(10_000) {
                    var value = members.receive()
                    while (!predicate(value)) value = members.receive()
                    value
                }
            try {
                assertNotNull(nextMoment { it != null })
                assertEquals(8, nextMembers { it.size == 8 }.size)
                assertTrue(repo.rename(id, "Updated live"))
                nextMoment { it?.title == "Updated live" }
                assertTrue(repo.save(id))
                nextMoment { it?.state == "SAVED" }
                assertTrue(
                    repo.reorderVisible(
                        id,
                        rows.reversed().map { MediaKey(it.volumeName, it.mediaStoreId) },
                    )
                )
                assertEquals(
                    7L,
                    nextMembers { it.firstOrNull()?.media?.mediaStoreId == 7L }
                        .first()
                        .media
                        .mediaStoreId,
                )
                db.libraryDao().upsertMedia(listOf(rows.last().copy(isAccessible = false)))
                assertEquals(7, nextMembers { it.size == 7 }.size)
                db.libraryDao().upsertMedia(rows.map { it.copy(isAccessible = false) })
                assertTrue(nextMembers { it.isEmpty() }.isEmpty())
                assertEquals(8, db.momentDao().allMembers(id).size)
                db.libraryDao().upsertMedia(rows)
                assertEquals(8, nextMembers { it.size == 8 }.size)
                assertTrue(repo.delete(id))
                assertEquals(null, nextMoment { it == null })
                assertTrue(nextMembers { it.isEmpty() }.isEmpty())
            } finally {
                a.cancel()
                b.cancel()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun visibleReorderPreservesHiddenSlotsCoverAndRejectsStaleOrDuplicateLists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val rows = (0L until 8L).map { media("volume:with:colon", it, it * 1_000) }
            db.libraryDao().upsertMedia(rows)
            val repo = MomentRepository(db)
            repo.generate(64)
            val id = repo.summaries().first().single().moment.momentId
            assertTrue(repo.setCover(id, MediaKey(rows[3].volumeName, 3)))
            db.libraryDao()
                .upsertMedia(
                    listOf(rows[2].copy(isAccessible = false), rows[5].copy(isTrashed = true))
                )
            val before = db.momentDao().allMembers(id)
            val keys = repo.members(id).map { MediaKey(it.media.volumeName, it.media.mediaStoreId) }
            assertFalse(repo.reorderVisible(id, keys.dropLast(1)))
            assertFalse(repo.reorderVisible(id, keys.dropLast(1) + keys.first()))
            assertEquals(before, db.momentDao().allMembers(id))
            assertTrue(repo.reorderVisible(id, keys.reversed()))
            val after = db.momentDao().allMembers(id)
            assertEquals(before[2], after[2])
            assertEquals(before[5], after[5])
            assertEquals(
                keys.reversed(),
                repo.members(id).map { MediaKey(it.media.volumeName, it.media.mediaStoreId) },
            )
            assertEquals(3L, repo.summaries().first().single().coverMediaStoreId)
            db.libraryDao().upsertMedia(rows)
            assertFalse(
                repo.reorderVisible(id, keys)
            ) // Old visible snapshot must not drop restored sources.
            assertEquals(8, repo.members(id).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun namedDatabaseObserversSeeOtherConnectionAndPreserveStateAfterReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "memory-live-${java.util.UUID.randomUUID()}.db"
        fun open() =
            Room.databaseBuilder(context, GalleryDatabase::class.java, name)
                .enableMultiInstanceInvalidation()
                .build()
        val db = open()
        val writer = open()
        try {
            db.libraryDao()
                .upsertMedia((0L until 8L).map { media("external_primary", it, it * 1_000) })
            val repo = MomentRepository(db)
            repo.generate(64)
            val id = repo.summaries().first().single().moment.momentId
            val events = Channel<String?>(Channel.UNLIMITED)
            val watcher = launch { repo.observeMoment(id).collect { events.send(it?.title) } }
            try {
                withTimeout(10_000) { events.receive() }
                assertTrue(MomentRepository(writer).rename(id, "Other connection"))
                withTimeout(10_000) { while (events.receive() != "Other connection") {} }
            } finally {
                watcher.cancel()
            }
            writer.close()
            db.close()
            val reopened = open()
            try {
                assertEquals(
                    "Other connection",
                    MomentRepository(reopened).observeMoment(id).first()?.title,
                )
            } finally {
                reopened.close()
            }
        } finally {
            writer.close()
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun media(volume: String, id: Long, time: Long, accessible: Boolean = true) =
        MediaItemEntity(
            volumeName = volume,
            mediaStoreId = id,
            mediaType = 1,
            mimeType = "image/jpeg",
            displayName = "IMG_$id.jpg",
            sizeBytes = 1_000,
            width = 4_000,
            height = 3_000,
            durationMillis = 0,
            orientationDegrees = 0,
            dateTakenMillis = time,
            dateAddedSeconds = time / 1_000,
            dateModifiedSeconds = time / 1_000,
            timelineSortMillis = time,
            generationAdded = 1,
            generationModified = 1,
            bucketId = 1,
            bucketDisplayName = "Camera",
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
            isAccessible = accessible,
            lastSeenScanId = 1,
        )
}
