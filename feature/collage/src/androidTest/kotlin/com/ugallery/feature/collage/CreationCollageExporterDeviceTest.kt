package com.ugallery.feature.collage

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CreationCollageExporterDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private fun fixtures(): List<File> {
        val directory = File(context.cacheDir, "creation-collage-provider-${UUID.randomUUID()}").apply { check(mkdir()) }
        return colors.mapIndexed { index, color ->
            File(directory, "$index.png").also { file ->
                val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
    }
    private fun sources(files: List<File>) = files.map { CreationCollageSource(Uri.fromFile(it)) }
    private fun hashes(files: List<File>) = files.map { it.inputStream().use { stream -> CreationCollageExporter.digest(stream) } }
    private fun outputs(): Set<Long> = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID), android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Pictures/UGallery/Collage/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    @Test fun fourPhotoStripPublishesTheExactPreviewBytesWithReorderedPixels() = runBlocking {
        val files = fixtures(); val before = hashes(files)
        var prepared: CreationCollagePrepared? = null; var output: Uri? = null
        try {
            val engine = CreationCollageExporter(context)
            prepared = engine.prepare(sources(files))
            val layout = CreationCollageLayout(CreationCollageTemplate.Strip4, listOf(3, 0, 1, 2))
            val image = engine.render(prepared, layout)
            val preview = BitmapFactory.decodeFile(image.file.path)
            assertEquals(2048, preview.width); assertEquals(2048, preview.height)
            listOf(Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { i, color ->
                assertEquals(color, preview.getPixel(256 + i * 512, 1024))
            }
            preview.recycle()
            output = engine.publish(image)
            val written = resolver.openInputStream(output)!!.use { CreationCollageExporter.digest(it) }
            assertEquals(image.sha256, written)
            assertEquals(before, hashes(files))
            resolver.query(output, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("image/png", it.getString(1))
            }
        } finally { output?.let { resolver.delete(it, null, null) }; prepared?.close(); files.first().parentFile!!.deleteRecursively() }
    }

    @Test fun cropPositionActuallyChangesTheRenderedPhotoWithoutChangingOriginals() = runBlocking {
        val files = fixtures().take(2); var prepared: CreationCollagePrepared? = null
        try {
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(Color.BLUE); drawRect(0f, 0f, 60f, 80f, Paint().apply { color = Color.RED }) }
            files.first().outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val before = hashes(files); val engine = CreationCollageExporter(context)
            prepared = engine.prepare(sources(files))
            val original = CreationCollageLayout.initial(2)
            val left = engine.render(prepared, original.crop(0, CreationCollageCrop(3f, -1f, 0f)))
            val right = engine.render(prepared, original.crop(0, CreationCollageCrop(3f, 1f, 0f)))
            val a = BitmapFactory.decodeFile(left.file.path); val b = BitmapFactory.decodeFile(right.file.path)
            assertEquals(Color.RED, a.getPixel(512, 1024)); assertEquals(Color.BLUE, b.getPixel(512, 1024))
            assertEquals(a.getPixel(1536, 1024), b.getPixel(1536, 1024))
            a.recycle(); b.recycle(); assertEquals(before, hashes(files))
        } finally { prepared?.close(); files.first().parentFile!!.deleteRecursively() }
    }

    @Test fun accessRevokedAfterPendingInsertionRemovesOnlyTheOwnedUnpublishedCopy() = runBlocking {
        val files = fixtures().take(3); val uris = files.map { CreationCollageTestProvider.register(context, it) }
        val before = outputs(); val sourceHashes = hashes(files); var prepared: CreationCollagePrepared? = null
        try {
            val engine = CreationCollageExporter(context)
            prepared = engine.prepare(uris.map { CreationCollageSource(it) })
            val image = engine.render(prepared, CreationCollageLayout.initial(3))
            var sawPending = false; var rejected = false
            try {
                engine.publish(image, onProgress = { if (it == 90) {
                    sawPending = outputs() != before
                    CreationCollageTestProvider.revoke(uris.first())
                } })
            } catch (_: SecurityException) { rejected = true }
            assertTrue("Pending insertion observed", sawPending); assertTrue("Provider revocation rejected publication", rejected)
            assertEquals(before, outputs()); assertEquals(sourceHashes, hashes(files))
        } finally { uris.forEach(CreationCollageTestProvider::remove); prepared?.close(); files.first().parentFile!!.deleteRecursively() }
    }

    @Test fun cancelledPublicationCleansPendingAndRetainsOriginals() = runBlocking {
        val files = fixtures().take(3); val before = outputs(); var prepared: CreationCollagePrepared? = null
        try {
            val engine = CreationCollageExporter(context); prepared = engine.prepare(sources(files))
            val image = engine.render(prepared, CreationCollageLayout.initial(3))
            var pendingSeen = false
            val task = launch {
                engine.publish(image, onProgress = { if (it == 90) { pendingSeen = outputs() != before; throw CancellationException("Owned cancellation") } })
            }
            task.join(); assertTrue("Writer cancelled", task.isCancelled); assertTrue("Pending insertion observed", pendingSeen); assertEquals(before, outputs())
            assertTrue(files.all(File::exists))
        } finally { prepared?.close(); files.first().parentFile!!.deleteRecursively() }
    }

    @Test fun changedSourceAfterPreviewRejectsWholePublication() = runBlocking {
        val files = fixtures().take(3); val before = outputs(); var prepared: CreationCollagePrepared? = null
        try {
            val engine = CreationCollageExporter(context); prepared = engine.prepare(sources(files))
            val image = engine.render(prepared, CreationCollageLayout.initial(3))
            files[1].appendBytes(byteArrayOf(42))
            var rejected = false
            try { engine.publish(image) } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected); assertEquals(before, outputs())
        } finally { prepared?.close(); files.first().parentFile!!.deleteRecursively() }
    }

    @Test fun generationAddedMismatchRejectsAReusedSelectionWithoutPublishing() = runBlocking {
        val files = fixtures().take(3); var source: Uri? = null; val before = outputs()
        try {
            source = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "collage-fixture-${UUID.randomUUID()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryTest/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            resolver.openOutputStream(source)!!.use { out -> files[0].inputStream().use { it.copyTo(out) } }
            resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val generations = resolver.query(source, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.GENERATION_ADDED), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); it.getLong(0) to it.getLong(1)
            }
            var rejected = false
            try { CreationCollageExporter(context).prepare(listOf(CreationCollageSource(source, generations.first, generations.second + 1)) + sources(files.drop(1))) }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected); assertEquals(before, outputs())
        } finally { source?.let { resolver.delete(it, null, null) }; files.first().parentFile!!.deleteRecursively() }
    }

    @Test fun everyLegacyLayoutAndFourPhotoStripProducesACompletePreview() = runBlocking {
        val files = fixtures()
        try {
            val engine = CreationCollageExporter(context)
            CreationCollageTemplate.entries.forEach { template ->
                val prepared = engine.prepare(sources(files.take(template.sourceCount)))
                try {
                    val image = engine.render(prepared, CreationCollageLayout(template, (0 until template.sourceCount).toList()))
                    val bitmap = BitmapFactory.decodeFile(image.file.path)
                    assertEquals(2048, bitmap.width); assertEquals(2048, bitmap.height)
                    assertTrue(image.file.length() > 0); bitmap.recycle()
                } finally { prepared.close() }
            }
        } finally { files.first().parentFile!!.deleteRecursively() }
    }
}
