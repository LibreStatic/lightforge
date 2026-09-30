package com.librestatic.lightforge.feature.collage

import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class CreationGifExporterDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun independentDecoderValidatesFramesTimingLoopPaletteAndDimensions() = runBlocking {
        val files = fixtures()
        var output: Uri? = null
        try {
            val hashes = files.map { it.inputStream().use { CreationGifExporter.sha256(it) } }
            output = CreationGifExporter(context).export(CreationGifRequest(files.reversed().map { CreationGifSource(Uri.fromFile(it)) }, 2))
            val bytes = context.contentResolver.openInputStream(output)!!.use { it.readBytes() }
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(512, movie.width()); assertEquals(512, movie.height()); assertEquals(6000, movie.duration())
            listOf(Color.BLUE, Color.GREEN, Color.RED).forEachIndexed { i, color ->
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                movie.setTime(i * 2000 + 500); movie.draw(Canvas(bitmap), 0f, 0f)
                assertEquals(color, bitmap.getPixel(256, 256)); bitmap.recycle()
            }
            val loop = "NETSCAPE2.0".toByteArray()
            val offset = bytes.indices.first { it + loop.size < bytes.size && bytes.copyOfRange(it, it + loop.size).contentEquals(loop) }
            assertArrayEquals(byteArrayOf(3, 1, 0, 0, 0), bytes.copyOfRange(offset + loop.size, offset + loop.size + 5))
            context.contentResolver.query(output, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("image/gif", it.getString(1))
            }
            assertEquals(hashes, files.map { it.inputStream().use { CreationGifExporter.sha256(it) } })
            File(context.filesDir, "creation-gif-independent.gif").writeBytes(bytes)
            assertNoStaging()
        } finally { output?.let { context.contentResolver.delete(it, null, null) }; files.forEach(File::delete) }
    }
    @Test fun cancelDuringEncodingLeavesNoPublishedOrPendingOutput() = runBlocking {
        val files = fixtures(); val before = outputs()
        try {
            val task = launch {
                CreationGifExporter(context).export(CreationGifRequest(files.map { CreationGifSource(Uri.fromFile(it)) }), onProgress = {
                    if (it > 0) throw CancellationException("Owned test cancellation")
                })
            }
            task.join(); assertTrue(task.isCancelled); assertNoStaging(); assertEquals(before, outputs())
        } finally { files.forEach(File::delete) }
    }
    @Test fun changedSourceHashRejectsEntireExport() = runBlocking {
        val files = fixtures(); val before = outputs()
        try {
            var failed = false
            try {
                CreationGifExporter(context).export(CreationGifRequest(files.map { CreationGifSource(Uri.fromFile(it)) }), onProgress = {
                    if (it == 50) files.first().appendBytes(byteArrayOf(1))
                })
            } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed); assertNoStaging(); assertEquals(before, outputs())
        } finally { files.forEach(File::delete) }
    }
    @Test fun cancelAfterPendingInsertRemovesOnlyOwnedDestination() = runBlocking {
        val files = fixtures(); val before = outputs()
        var sawPending = false
        try {
            val task = launch {
                CreationGifExporter(context).export(CreationGifRequest(files.map { CreationGifSource(Uri.fromFile(it)) }), onProgress = {
                    if (it == 90) {
                        sawPending = outputs() != before
                        throw CancellationException("Cancel after pending insertion")
                    }
                })
            }
            task.join(); assertTrue(sawPending); assertTrue(task.isCancelled)
            assertNoStaging(); assertEquals(before, outputs())
        } finally { files.forEach(File::delete) }
    }
    @Test fun exifRotationIsRenderedBeforeGifEncoding() = runBlocking {
        val files = fixtures(); var output: Uri? = null
        val rotated = File.createTempFile("creation-rotation-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.BLUE)
                drawRect(0f, 0f, 40f, 40f, Paint().apply { color = Color.RED })
            }
            rotated.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
            androidx.exifinterface.media.ExifInterface(rotated).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
            }
            val hash = rotated.inputStream().use { CreationGifExporter.sha256(it) }
            output = CreationGifExporter(context).export(CreationGifRequest(listOf(CreationGifSource(Uri.fromFile(rotated)), CreationGifSource(Uri.fromFile(files[1]))), 1))
            val bytes = context.contentResolver.openInputStream(output)!!.use { it.readBytes() }
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size)!!
            val frame = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            movie.setTime(500); movie.draw(Canvas(frame), 0f, 0f)
            File(context.filesDir, "creation-gif-rotation.gif").writeBytes(bytes)
            File(context.filesDir, "creation-gif-rotation.txt").writeText("top=${frame.getPixel(256,160)} bottom=${frame.getPixel(256,352)}")
            assertTrue(Color.red(frame.getPixel(256, 160)) > 180 && Color.blue(frame.getPixel(256, 160)) < 80)
            assertTrue(Color.blue(frame.getPixel(256, 352)) > 180 && Color.red(frame.getPixel(256, 352)) < 80)
            frame.recycle(); assertEquals(hash, rotated.inputStream().use { CreationGifExporter.sha256(it) })
        } finally { output?.let { context.contentResolver.delete(it, null, null) }; rotated.delete(); files.forEach(File::delete) }
    }
    @Test fun staleMediaStoreGenerationRejectsAndSourceRemainsIntact() = runBlocking {
        val files = fixtures(); val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "creation-gif-generation-${System.nanoTime()}.png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgeTest/")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { out -> files[0].inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            var failed = false
            try { CreationGifExporter(context).export(CreationGifRequest(listOf(CreationGifSource(uri, -1), CreationGifSource(Uri.fromFile(files[1]))))) }
            catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertEquals(files[0].inputStream().use { CreationGifExporter.sha256(it) }, resolver.openInputStream(uri)!!.use { CreationGifExporter.sha256(it) })
            assertNoStaging()
        } finally { resolver.delete(uri, null, null); files.forEach(File::delete) }
    }
    @Test fun corruptSourceNeverProducesSuccess() = runBlocking {
        val files = fixtures(); val before = outputs()
        try {
            files[1].writeText("not an image")
            var failed = false
            try { CreationGifExporter(context).export(CreationGifRequest(files.map { CreationGifSource(Uri.fromFile(it)) })) }
            catch (_: Exception) { failed = true }
            assertTrue(failed); assertEquals(before, outputs()); assertNoStaging()
        } finally { files.forEach(File::delete) }
    }
    private fun fixtures(): List<File> = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
        File.createTempFile("creation-gif-fixture-", ".png", context.cacheDir).also { file ->
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun assertNoStaging() = assertTrue(context.cacheDir.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("creation-gif-") })
    private fun outputs(): Set<Long> = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
        android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Pictures/Lightforge/GIF/", context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
}
