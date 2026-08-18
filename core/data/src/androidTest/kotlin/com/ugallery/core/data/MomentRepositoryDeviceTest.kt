package com.ugallery.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MomentRepositoryDeviceTest {
    @Test fun generationPreservesCompositeIdentityAndUserEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val rows = (0L until 8L).flatMap { id ->
                listOf(media("external_primary", id, id * 1_000), media("1234-5678", id, id * 1_000 + 1))
            }
            database.libraryDao().upsertMedia(rows)
            val repository = MomentRepository(database) { 10_000L }
            val progress = repository.generate(pageSize = 64)
            assertTrue(progress.complete)
            val summary = repository.summaries().first().single()
            val members = repository.members(summary.moment.momentId)
            assertEquals(16, members.size)
            assertEquals(16, members.map { it.media.volumeName to it.media.mediaStoreId }.toSet().size)

            assertTrue(repository.rename(summary.moment.momentId, "My trip"))
            assertTrue(repository.setCover(summary.moment.momentId, MediaKey("1234-5678", 2)))
            repository.restartGeneration()
            repository.generate(pageSize = 64)
            val edited = database.momentDao().moment(summary.moment.momentId)
            assertEquals("My trip", edited?.title)
            assertTrue(edited?.isUserEdited == true)
        } finally { database.close() }
    }

    @Test fun inaccessibleMemberIsHiddenWithoutLosingReferenceAndCannotBeDroppedByReorder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            database.libraryDao().upsertMedia((0L until 8L).map { media("external_primary", it, it * 1_000) })
            val repository = MomentRepository(database)
            repository.generate(pageSize = 64)
            val id = repository.summaries().first().single().moment.momentId
            val all = database.momentDao().allMembers(id)
            val hidden = all.first()
            database.libraryDao().upsertMedia(listOf(media(hidden.volumeName, hidden.mediaStoreId, 0, accessible = false)))
            assertEquals(all.size - 1, repository.members(id).size)
            assertEquals(all.size, database.momentDao().allMembers(id).size)
            assertFalse(repository.reorder(id, repository.members(id).map { MediaKey(it.media.volumeName, it.media.mediaStoreId) }))
            assertEquals(all.size, database.momentDao().allMembers(id).size)
        } finally { database.close() }
    }

    @Test fun deletingMediaCascadesGeneratedReferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            database.libraryDao().upsertMedia((0L until 8L).map { media("external_primary", it, it * 1_000) })
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
        } finally { database.close() }
    }

    private fun media(volume: String, id: Long, time: Long, accessible: Boolean = true) = MediaItemEntity(
        volumeName = volume, mediaStoreId = id, mediaType = 1, mimeType = "image/jpeg",
        displayName = "IMG_$id.jpg", sizeBytes = 1_000, width = 4_000, height = 3_000,
        durationMillis = 0, orientationDegrees = 0, dateTakenMillis = time,
        dateAddedSeconds = time / 1_000, dateModifiedSeconds = time / 1_000,
        timelineSortMillis = time, generationAdded = 1, generationModified = 1,
        bucketId = 1, bucketDisplayName = "Camera", relativePath = "DCIM/Camera/",
        isFavorite = false, isTrashed = false, isAccessible = accessible, lastSeenScanId = 1,
    )
}
