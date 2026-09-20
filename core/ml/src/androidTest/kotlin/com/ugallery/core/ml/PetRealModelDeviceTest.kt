package com.ugallery.core.ml

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.LabelSuppressionEntity
import com.ugallery.core.search.AppSearchMediaIndex
import com.ugallery.core.search.AppSearchMediaSearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PetRealModelDeviceTest {
    @Test fun realBundledPetInferenceIndexesTypesSuppressesAndPreservesOriginals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(InstrumentationRegistry.getArguments().getString("modelFixture") == "ugallery-local-models")
        val root = File(context.filesDir, "model-fixtures")
        val files = linkedMapOf(
            "cat.jpg" to "2533197401eebe9410ea4d063f86c43fbd2666f3e8165a38aca155c0d09c21be",
            "cats_and_dogs.jpg" to "a2eaa7ad3a1aae4e623dd362a5f737e8a88d122597ecd1a02b3e1444db56df9c",
            "burger.jpg" to "97c15bbbf3cf3615063b1031c85d669de55839f59262bbe145d15ca75b36ecbf",
        )
        files.forEach { (name, hash) -> assertEquals(hash, sha(File(root, name).readBytes())) }
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val resolver = context.contentResolver
        val sources = mutableListOf<Uri>()
        val digests = mutableListOf<String>()
        val namespace = "real-pets-${UUID.randomUUID()}"
        val index = AppSearchMediaIndex(context, namespace)
        try {
            files.keys.forEachIndexed { i, name ->
                val decoded = BitmapFactory.decodeFile(File(root, name).path)!!
                // Corgi crop is from the licensed original photo, not a generated vector/image.
                val bitmap = if (name == "cats_and_dogs.jpg") Bitmap.createBitmap(decoded, 600, 100, 320, 500) else decoded
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$namespace-$i.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$namespace/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                sources += uri
                resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                if (bitmap !== decoded) bitmap.recycle()
                decoded.recycle()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                digests += sha(resolver.openInputStream(uri)!!.use { it.readBytes() })
                database.libraryDao().upsertMedia(listOf(MediaItemEntity(
                    volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY, mediaStoreId = uri.lastPathSegment!!.toLong(),
                    mediaType = 1, mimeType = "image/png", displayName = "$i.png", sizeBytes = 1,
                    width = 600, height = 400, durationMillis = 0, orientationDegrees = 0,
                    dateTakenMillis = i.toLong(), dateAddedSeconds = 1, dateModifiedSeconds = 1,
                    timelineSortMillis = i.toLong(), generationAdded = 1, generationModified = 1,
                    bucketId = 1, bucketDisplayName = namespace, relativePath = "Pictures/$namespace/",
                    isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
                )))
            }
            val start = android.os.SystemClock.elapsedRealtime()
            ImageLabelMlEngine(resolver, database, index, { true }).use { engine ->
                assertEquals(MlChunkOutcome.Complete(3), engine.process(null, 50))
                assertEquals(MlChunkOutcome.Complete(0), engine.process(null, 50))
            }
            val rows = sources.map { database.libraryDao().labels(MediaStore.VOLUME_EXTERNAL_PRIMARY, it.lastPathSegment!!.toLong()) }
            assertTrue("Real cat must be positive", rows[0].any { it.canonicalLabel == "cat" && it.confidence >= .6f })
            assertTrue("Real dog must be positive", rows[1].any { it.canonicalLabel == "dog" && it.confidence >= .6f })
            assertFalse("Burger is not a pet", rows[2].any { it.canonicalLabel == "dog" || it.canonicalLabel == "cat" })
            val summary = PetCollectionRepository(database).summary().first()
            assertEquals(1L, summary.catCount); assertEquals(1L, summary.dogCount)
            AppSearchMediaSearchRepository(context, namespace).search("dog").use {
                assertEquals(listOf(sources[1].lastPathSegment!!.toLong()), it.nextPage().hits.map { hit -> hit.key.mediaStoreId })
            }
            database.libraryDao().suppressLabel(LabelSuppressionEntity("dog", 1))
            assertEquals(0L, PetCollectionRepository(database).summary().first().dogCount)
            sources.forEachIndexed { i, uri -> assertEquals(digests[i], sha(resolver.openInputStream(uri)!!.use { it.readBytes() })) }
            files.forEach { (name, hash) -> assertEquals(hash, sha(File(root, name).readBytes())) }
            val evidence = File(context.filesDir, "model-evidence").apply { mkdirs() }
            evidence.resolve("real-pets.json").writeText(JSONObject().put("status", "PASS")
                .put("model", ImageLabelMlEngine.ModelVersion).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                .put("labels", JSONArray(rows.map { labels -> JSONArray(labels.map { JSONObject().put("label", it.canonicalLabel).put("confidence", it.confidence) }) }))
                .put("individualIdentityClaimed", false).put("sourceHashes", JSONArray(digests)).toString(2))
        } finally {
            index.clear(); database.close()
            sources.forEach { resolver.delete(it, null, null) }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

