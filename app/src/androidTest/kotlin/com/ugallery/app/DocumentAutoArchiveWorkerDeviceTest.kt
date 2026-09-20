package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.ugallery.core.data.*
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DocumentAutoArchiveWorkerDeviceTest {
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

    @Test
    fun scheduledRunnerChecksRealSourceAndKeepsOnePersistentWorkRequest(): Unit =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.ugallery.app.pdfacceptance")
            val name = "auto-archive-worker-${UUID.randomUUID()}.db"
            val db = GalleryDatabaseFactory.open(context, name)
            val sources = mutableListOf<Uri>()
            val deleted = mutableSetOf<Uri>()
            fun hash(uri: Uri) =
                context.contentResolver.openInputStream(uri)!!.use {
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(it.readBytes())
                        .toList()
                }
            try {
                val repo = DocumentAutoArchiveRepository(db)
                repo.enable(repo.preview(DocumentCategory.Note, 0))
                repeat(3) { index ->
                    val uri =
                        context.contentResolver.insert(
                            MediaStore.Images.Media.getContentUri("external_primary"),
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name-$index.png")
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "Pictures/UGallery-AutoArchive-Fixture/",
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            },
                        )!!
                    sources.add(uri)
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    try {
                        context.contentResolver.openOutputStream(uri)!!.use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    val id = ContentUris.parseId(uri)
                    val generation =
                        context.contentResolver
                            .query(
                                uri,
                                arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED),
                                null,
                                null,
                                null,
                            )!!
                            .use {
                                check(it.moveToFirst())
                                it.getLong(0)
                            }
                    db.libraryDao()
                        .upsertMedia(
                            listOf(
                                media(id)
                                    .copy(
                                        generationModified =
                                            if (index == 2) generation - 1 else generation
                                    )
                            )
                        )
                    GalleryDocumentRepository(db)
                        .classify(listOf(MediaKey("external_primary", id)), DocumentCategory.Note)
                }
                val original = hash(sources[0])
                assertEquals(1, context.contentResolver.delete(sources[1], null, null))
                deleted.add(sources[1])
                assertEquals(1, DocumentAutoArchiveWorker.runOnce(context, db))
                assertEquals(0, DocumentAutoArchiveWorker.runOnce(context, db))
                assertNotNull(
                    db.documentDao().archivedAt("external_primary", ContentUris.parseId(sources[0]))
                )
                assertNull(
                    db.documentDao().archivedAt("external_primary", ContentUris.parseId(sources[1]))
                )
                assertNull(
                    db.documentDao().archivedAt("external_primary", ContentUris.parseId(sources[2]))
                )
                assertEquals(original, hash(sources[0]))
                DocumentAutoArchiveWorker.install(context)
                DocumentAutoArchiveWorker.install(context)
                val manager = WorkManager.getInstance(context)
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                var work =
                    manager
                        .getWorkInfosForUniqueWork(DocumentAutoArchiveWorker.WorkName)
                        .get(10, TimeUnit.SECONDS)
                while (work.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(100)
                    work =
                        manager
                            .getWorkInfosForUniqueWork(DocumentAutoArchiveWorker.WorkName)
                            .get(10, TimeUnit.SECONDS)
                }
                assertEquals(1, work.count { !it.state.isFinished })
                assertEquals(1, repo.undoLastRun())
                assertEquals(original, hash(sources[0]))
            } finally {
                sources
                    .filterNot { it in deleted }
                    .forEach { context.contentResolver.delete(it, null, null) }
                db.close()
                context.deleteDatabase(name)
            }
        }
}
