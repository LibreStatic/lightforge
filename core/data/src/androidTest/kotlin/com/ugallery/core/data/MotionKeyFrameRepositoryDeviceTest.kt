package com.ugallery.core.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class MotionKeyFrameRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = MediaKey("motion-fixture", 999_001)
    private fun media() = MediaItemEntity(key.volumeName,key.mediaStoreId,1,"image/jpeg","source.jpg",100,64,48,0,0,1000,1,1,1000,1,2,null,null,null,false,false,true,1)
    private fun fixture(block: suspend (GalleryDatabase,MotionKeyFrameRepository,File) -> Unit) = runBlocking {
        val name = "motion-cover-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
        val frame = File(context.cacheDir,"${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        frame.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }; bitmap.recycle()
        try {
            db.libraryDao().upsertMedia(listOf(media()))
            block(db, MotionKeyFrameRepository(context, db, sourceGeneration = { 2 }), frame)
        } finally {
            withContext(NonCancellable) {
                db.motionKeyFrameDao().get(key.volumeName,key.mediaStoreId)?.let { row ->
                    File(context.filesDir,"motion-key-frames/${row.fileName}").delete()
                }
            }
            frame.delete(); db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun savedChoiceSurvivesRepositoryReopenAndResetLeavesInputIntact() = fixture { db, repo, frame ->
        val original = frame.readBytes()
        val value = repo.set(key,2,500_000,frame,null)
        assertEquals(value, repo.observe(key).first())
        val reopened = MotionKeyFrameRepository(context, db, sourceGeneration = { 2 })
        val uri = withContext(Dispatchers.IO) { reopened.displayUri(key,2) }
        assertNotNull(uri)
        assertArrayEquals(original, File(uri!!.path!!).readBytes())
        assertTrue(reopened.reset(key,value.revision))
        assertNull(withContext(Dispatchers.IO) { reopened.displayUri(key,2) })
        assertArrayEquals(original, frame.readBytes())
    }

    @Test fun staleRevisionCannotOverwriteOrResetCurrentCover() = fixture { db, repo, frame ->
        val first = repo.set(key,2,1,frame,null)
        val second = repo.set(key,2,2,frame,first.revision)
        assertTrue(runCatching { repo.set(key,2,3,frame,first.revision) }.isFailure)
        assertFalse(repo.reset(key,first.revision))
        assertEquals(second,db.motionKeyFrameDao().get(key.volumeName,key.mediaStoreId))
        assertNotNull(withContext(Dispatchers.IO) { repo.displayUri(key,2) })
    }

    @Test fun changedSourceTrashAndLostAccessNeverProjectOldCover() = fixture { db, repo, frame ->
        repo.set(key,2,1,frame,null)
        for (changed in listOf(media().copy(generationModified=3),media().copy(isTrashed=true),media().copy(isAccessible=false))) {
            db.libraryDao().upsertMedia(listOf(changed))
            assertNull(withContext(Dispatchers.IO) { repo.displayUri(key,changed.generationModified) })
        }
        assertTrue(runCatching { repo.set(key,2,2,frame,null) }.isFailure)
    }

    @Test fun invalidFrameAndConcurrentProviderMutationPreservePreviousChoice() = fixture { db, repo, frame ->
        val first = repo.set(key,2,1,frame,null)
        val invalid = File(context.cacheDir,"${UUID.randomUUID()}.bin").apply { writeText("not a JPEG") }
        try { assertTrue(runCatching { repo.set(key,2,2,invalid,first.revision) }.isFailure) } finally { invalid.delete() }
        var queries = 0
        val changing = MotionKeyFrameRepository(context, db, sourceGeneration = { if (++queries == 1) 2 else 3 })
        assertTrue(runCatching { changing.set(key,2,2,frame,first.revision) }.isFailure)
        assertEquals(first,db.motionKeyFrameDao().get(key.volumeName,key.mediaStoreId))
    }

    @Test fun corruptedPrivateFrameFallsBackWithoutTouchingOriginal() = fixture { _, repo, frame ->
        val original = frame.readBytes()
        val value = repo.set(key,2,1,frame,null)
        File(context.filesDir,"motion-key-frames/${value.fileName}").writeBytes(ByteArray(10))
        assertNull(withContext(Dispatchers.IO) { repo.displayUri(key,2) })
        assertArrayEquals(original,frame.readBytes())
    }

    @Test fun restoredTimelineSurvivesRescanButNeverReusedIdentityOrExplicitReset() = fixture { db, _, _ ->
        val dao = db.libraryDao()
        db.portableTimelineOverrideDao().put(PortableTimelineOverrideEntity(key.volumeName,key.mediaStoreId,1,123,99))
        dao.upsertMedia(listOf(media().copy(generationModified=8,isFavorite=true)))
        var row = dao.media(key.volumeName,key.mediaStoreId)!!
        assertEquals(123L,row.timelineSortMillis); assertEquals(99L,row.dateTakenMillis);assertTrue(row.isFavorite)
        dao.upsertMedia(listOf(media().copy(generationAdded=9,generationModified=10)))
        assertEquals(1000L,dao.media(key.volumeName,key.mediaStoreId)!!.timelineSortMillis)
        db.portableTimelineOverrideDao().remove(key.volumeName,key.mediaStoreId)
        dao.upsertMedia(listOf(media().copy(timelineSortMillis=456)))
        assertEquals(456L,dao.media(key.volumeName,key.mediaStoreId)!!.timelineSortMillis)
    }
}
