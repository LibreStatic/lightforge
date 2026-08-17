package com.ugallery.core.mediastore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiVolumeGenerationDeviceTest {
    @Test
    fun detectsAddModifyAndDeleteAcrossPrimaryAndRemovableVolumes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val volumes = MediaStore.getExternalVolumeNames(context).sorted()
        assumeTrue("requires a removable MediaStore volume; found $volumes", volumes.size >= 2)
        assertTrue(volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY))

        val results = JSONArray()
        volumes.forEach { volume ->
            val before = MediaStore.getGeneration(context, volume)
            val collection = MediaStore.Files.getContentUri(volume)
            val displayName = "ugallery-generation-${System.nanoTime()}.txt"
            val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/UGalleryBenchmark/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })) { "insert failed for $volume" }

            try {
                resolver.openOutputStream(uri, "w")!!.use { output ->
                    output.write("volume=$volume".encodeToByteArray())
                }
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                val afterAdd = MediaStore.getGeneration(context, volume)
                assertTrue("generation did not advance after add on $volume", afterAdd > before)
                val addedRow = rowGeneration(context, uri)
                assertEquals(volume, addedRow.volumeName)
                assertTrue(addedRow.generationAdded > before)

                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "updated-$displayName")
                }, null, null)
                val afterModify = MediaStore.getGeneration(context, volume)
                val modifiedRow = rowGeneration(context, uri)
                assertTrue("generation did not advance after update on $volume", afterModify > afterAdd)
                assertTrue(modifiedRow.generationModified > addedRow.generationModified)

                val beforeDelete = afterModify
                assertEquals(1, resolver.delete(uri, null, null))
                val afterDelete = MediaStore.getGeneration(context, volume)
                assertTrue("generation did not advance after delete on $volume", afterDelete > beforeDelete)
                assertEquals(0, resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count })

                results.put(JSONObject()
                    .put("volume", volume)
                    .put("before", before)
                    .put("afterAdd", afterAdd)
                    .put("afterModify", afterModify)
                    .put("afterDelete", afterDelete)
                    .put("generationAdded", addedRow.generationAdded)
                    .put("generationModified", modifiedRow.generationModified))
            } finally {
                resolver.delete(uri, null, null)
            }
        }

        val output = JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("volumes", results)
        println("UGALLERY_MULTIVOLUME_METRICS $output")
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("ugallery.multivolume.metrics", output.toString())
        })
    }

    private fun rowGeneration(context: Context, uri: Uri): RowGeneration =
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.GENERATION_ADDED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
            ),
            null,
            null,
            null,
        )!!.use { cursor ->
            check(cursor.moveToFirst())
            RowGeneration(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))
        }

    private data class RowGeneration(
        val volumeName: String,
        val generationAdded: Long,
        val generationModified: Long,
    )
}

@RunWith(AndroidJUnit4::class)
class VolumePresenceDeviceTest {
    @Test
    fun reportsExpectedVolumePresence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val expectedVolume = arguments.getString("expectedVolume")
        assumeNotNull(expectedVolume)
        val expectedPresent = arguments.getString("expectedPresent").toBoolean()
        val volumes = MediaStore.getExternalVolumeNames(instrumentation.targetContext).sorted()
        assertEquals("volumes=$volumes", expectedPresent, volumes.contains(expectedVolume))
        println(
            "UGALLERY_VOLUME_PRESENCE expected=$expectedVolume present=$expectedPresent volumes=$volumes",
        )
    }
}
