package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.editing.video.MemoryVideoExporter
import com.librestatic.lightforge.core.editing.video.MemoryVideoRequest
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.mediastore.MediaStoreRecord
import com.librestatic.lightforge.core.model.MediaKey
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelectionMemoryVideoPreparationDeviceTest {
    @Test fun realPhotosKeepOrderFreshIdentityAndRejectChangedSourceBeforeExport(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val owned = mutableListOf<Uri>()
        val reader = MediaStoreReader(resolver)
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        fun row(r: MediaStoreRecord) = MediaItemEntity(r.key.volumeName, r.key.mediaStoreId, 1, r.mimeType,
            r.displayName, r.sizeBytes, r.width, r.height, r.durationMillis, r.orientationDegrees,
            r.dateTakenMillis, r.dateAddedSeconds, r.dateModifiedSeconds, r.dateAddedSeconds * 1000,
            r.generationAdded, r.generationModified, r.bucketId, r.bucketDisplayName, r.relativePath,
            r.isFavorite, r.isTrashed, true, 1)
        try {
            repeat(2) { index ->
                val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "selection-video-${UUID.randomUUID()}.png")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgeAcceptance/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    })!!
                owned += uri
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) Color.RED else Color.BLUE)
                resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            }
            val hashes = owned.map(::hash)
            val keys = owned.map { MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(it)) }.reversed()
            val rows = keys.map { row(reader.readOne(it)!!) }
            db.libraryDao().upsertMedia(rows)
            suspend fun prepare() = SelectionMemoryVideoPreparation.prepare(keys,
                { db.libraryDao().media(it.volumeName, it.mediaStoreId) }, reader::readOne)
            val first = prepare()!!
            val second = prepare()!!
            assertNotEquals(first.id, second.id)
            assertEquals(owned.reversed(), first.sources.map { it.uri })
            assertEquals(rows.map { it.generationModified }, first.sources.map { it.expectedGeneration })
            assertEquals(rows.map { it.generationAdded }, first.sources.map { it.expectedGenerationAdded })
            assertNotNull(SelectionMemoryVideoPreparation.prepare(keys.take(1),
                { db.libraryDao().media(it.volumeName, it.mediaStoreId) }, reader::readOne))
            db.libraryDao().upsertMedia(listOf(rows.first().copy(generationAdded = rows.first().generationAdded + 1)))
            assertNull(prepare())
            db.libraryDao().upsertMedia(listOf(rows.first().copy(isAccessible = false)))
            assertNull(prepare())
            db.libraryDao().upsertMedia(listOf(rows.first()))
            assertNull(SelectionMemoryVideoPreparation.prepare(keys,
                { db.libraryDao().media(it.volumeName, it.mediaStoreId) }, { throw SecurityException("fixture revoked") }))
            val cacheBefore = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("memory-video-") }.map { it.name }.toSet()
            val phases = mutableListOf<com.librestatic.lightforge.core.editing.video.MemoryVideoPhase>()
            try {
                MemoryVideoExporter(context).export(MemoryVideoRequest(listOf(first.sources.first().copy(
                    expectedGenerationAdded = first.sources.first().expectedGenerationAdded!! + 1)))) { phases += it.phase }
                fail("A generationAdded mismatch must abort before encoding/publication")
            } catch (expected: IllegalStateException) {
                assertEquals("Photo changed or access was removed", expected.message)
            }
            assertEquals(listOf(com.librestatic.lightforge.core.editing.video.MemoryVideoPhase.Preparing), phases)
            assertEquals(cacheBefore, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("memory-video-") }.map { it.name }.toSet())
            assertEquals(hashes, owned.map(::hash))
            assertEquals(1, resolver.delete(owned.last(), null, null))
            owned.removeAt(owned.lastIndex)
            assertNull(prepare())
        } finally {
            owned.forEach { resolver.delete(it, null, null) }
            db.close()
        }
    }
}
