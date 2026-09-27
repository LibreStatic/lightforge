package com.librestatic.lightforge.core.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.mediastore.MediaStoreGenerationProbe
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class IncrementalMediaSyncDeviceTest {
    @Test
    fun cameraBurstAndExternalDeleteApplyWithoutFullRescan() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantMediaStoreTestPermissions()
        val probe = MediaStoreGenerationProbe(context)
        val initialVolume = probe.snapshot().first { it.volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY }
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val store = RoomMediaIndexStore(database.libraryDao())
        val reader = MediaStoreReader(context.contentResolver)
        val access = { LibraryAccess(GrantLevel.Full, GrantLevel.Full, false) }
        val inserted = mutableListOf<Uri>()
        try {
            InitialMediaScanner(reader, store, access, pageSize = 256).scan(initialVolume)
            val beforeCount = database.libraryDao().mediaCount()
            repeat(20) { inserted += insertImage(context.contentResolver, it) }
            val target = probe.snapshot().first { it.volumeName == initialVolume.volumeName }
            lateinit var syncResult: IncrementalSyncResult
            val syncMillis = measureTimeMillis {
                syncResult = IncrementalMediaSynchronizer(reader, store, access, pageSize = 64)
                    .sync(target)
            }
            val complete = syncResult as IncrementalSyncResult.Complete
            val afterBurstCount = database.libraryDao().mediaCount()
            assertEquals(beforeCount + inserted.size, afterBurstCount)
            assertTrue(complete.changedItems >= inserted.size)

            val deletedUri = inserted.removeAt(0)
            val deletedId = requireNotNull(deletedUri.lastPathSegment).toLong()
            assertEquals(1, context.contentResolver.delete(deletedUri, null, null))
            val hintResult = IncrementalMediaSynchronizer(reader, store, access)
                .applyRowHint(MediaKey(initialVolume.volumeName, deletedId))
            assertEquals(RowHintResult.Deleted, hintResult)
            assertEquals(afterBurstCount - 1, database.libraryDao().mediaCount())

            val metrics = JSONObject()
                .put("device", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("baseCount", beforeCount)
                .put("burstItems", 20)
                .put("changedItems", complete.changedItems)
                .put("syncMillis", syncMillis)
                .put("deleteHintResult", hintResult.toString())
                .put("fullRescan", false)
            println("LIGHTFORGE_INCREMENTAL_SYNC_METRICS $metrics")
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("lightforge.incrementalSync.metrics", metrics.toString())
            })
        } finally {
            inserted.forEach { context.contentResolver.delete(it, null, null) }
            database.close()
        }
    }

    private fun insertImage(resolver: android.content.ContentResolver, index: Int): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "lightforge-burst-${System.nanoTime()}-$index.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgeM1Burst/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        resolver.openOutputStream(uri, "w")!!.use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(index, 20, 40))
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }
}
