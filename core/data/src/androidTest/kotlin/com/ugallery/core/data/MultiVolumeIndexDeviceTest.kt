package com.ugallery.core.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.mediastore.MediaStoreGenerationProbe
import com.ugallery.core.mediastore.MediaStoreReader
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.LibraryAccess
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiVolumeIndexDeviceTest {
    @Test
    fun indexesSameMediaStoreIdNamespaceWithoutCrossVolumeCollision() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantMediaStoreTestPermissions()
        val volumes = MediaStore.getExternalVolumeNames(context).sorted()
        assumeTrue("requires primary and removable volumes; found $volumes", volumes.size >= 2)

        val inserted = mutableListOf<Pair<String, Uri>>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            volumes.forEachIndexed { index, volume ->
                inserted += volume to insertImage(context.contentResolver, volume, index)
            }
            val snapshots = MediaStoreGenerationProbe(context).snapshot()
                .filter { snapshot -> volumes.contains(snapshot.volumeName) }
            val store = RoomMediaIndexStore(database.libraryDao())
            val scanner = InitialMediaScanner(
                pages = MediaStoreReader(context.contentResolver),
                store = store,
                currentAccess = { LibraryAccess(GrantLevel.Full, GrantLevel.Full, false) },
                pageSize = 64,
            )
            snapshots.forEach { snapshot -> scanner.scan(snapshot) }

            val rows = JSONArray()
            inserted.forEach { (volume, uri) ->
                val id = requireNotNull(uri.lastPathSegment).toLong()
                val indexed = database.libraryDao().media(volume, id)
                assertNotNull("missing indexed row for ($volume, $id)", indexed)
                assertEquals(volume, indexed?.volumeName)
                assertEquals(id, indexed?.mediaStoreId)
                rows.put(JSONObject().put("volume", volume).put("mediaStoreId", id))
            }
            val metrics = JSONObject()
                .put("device", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("indexedFixtures", rows)
                .put("totalIndexedRows", database.libraryDao().mediaCount())
            println("UGALLERY_MULTIVOLUME_INDEX_METRICS $metrics")
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("ugallery.multivolumeIndex.metrics", metrics.toString())
            })
        } finally {
            inserted.forEach { (_, uri) -> context.contentResolver.delete(uri, null, null) }
            database.close()
        }
    }

    private fun insertImage(
        resolver: android.content.ContentResolver,
        volume: String,
        index: Int,
    ): Uri {
        val uri = checkNotNull(
            resolver.insert(MediaStore.Images.Media.getContentUri(volume), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "ugallery-multivolume-${System.nanoTime()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryM1MultiVolume/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }),
        )
        resolver.openOutputStream(uri, "w")!!.use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(20 + index, 40, 60))
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
