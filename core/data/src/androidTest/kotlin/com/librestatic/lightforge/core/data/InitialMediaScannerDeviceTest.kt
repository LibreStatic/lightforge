package com.librestatic.lightforge.core.data

import android.os.Debug
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.mediastore.MediaStoreGenerationProbe
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class InitialMediaScannerDeviceTest {
    @Test
    fun scansPhysicalHundredThousandLibraryWithBoundedPages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantMediaStoreTestPermissions()
        val volume = MediaStoreGenerationProbe(context).snapshot().first {
            it.volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val pssBeforeKb = Debug.getPss()
        lateinit var result: InitialScanResult
        val elapsedMillis = try {
            measureTimeMillis {
                result = InitialMediaScanner(
                    pages = MediaStoreReader(context.contentResolver),
                    store = RoomMediaIndexStore(database.libraryDao()),
                    currentAccess = {
                        LibraryAccess(GrantLevel.Full, GrantLevel.Full, false)
                    },
                    pageSize = 256,
                ).scan(volume)
            }
        } finally {
            // Database remains open until assertions read the final durable state.
        }
        val complete = result as InitialScanResult.Complete
        val indexedCount = database.libraryDao().mediaCount()
        val checkpoint = database.libraryDao().checkpoint(volume.volumeName)
        val peakPssKb = Debug.getPss()
        try {
            assertTrue("expected at least 100k visible media, indexed=$indexedCount", indexedCount >= 100_000)
            assertEquals(indexedCount, complete.indexedItems)
            assertEquals(ScanState.Complete.name, checkpoint?.scanState)
            assertEquals(null, checkpoint?.activeScanId)
            val metrics = JSONObject()
                .put("device", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("volume", volume.volumeName)
                .put("indexedCount", indexedCount)
                .put("elapsedMillis", elapsedMillis)
                .put("itemsPerSecond", indexedCount * 1_000.0 / elapsedMillis)
                .put("pssBeforeKb", pssBeforeKb)
                .put("pssAfterKb", peakPssKb)
                .put("pageSize", 256)
            println("LIGHTFORGE_INITIAL_SCAN_METRICS $metrics")
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("lightforge.initialScan.metrics", metrics.toString())
            })
        } finally {
            database.close()
        }
    }
}
