package com.ugallery.feature.collage

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CreationGifPublicationResolutionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private class Fixture(val root: File, val files: List<File>, val engine: CreationGifExporter,
        val journal: CreationGifPublicationJournal, val expectedFile: File, val sources: List<String>, val sourceHashes: List<String>,
        val id: String = UUID.randomUUID().toString()) {
        var base: CreationGifPublicationDestination? = null
        var cleanupHash: String? = null
        var renamed: String? = null
        var removed: Boolean = false
        val originals = files.map { file -> file.readBytes().toList() }
    }
    private suspend fun fixture(): Fixture {
        val root = File(context.cacheDir.canonicalFile, "Gif-resolution-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = listOf(Color.RED, Color.BLUE).mapIndexed { index, color -> File(root, "$index.png").also { file ->
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } }
            finally { bitmap.recycle() }
        } }
        val journal = CreationGifPublicationJournal(File(root, "journal")); val engine = CreationGifExporter(context, journal)
        val file = File(root, "render.gif")
        file.outputStream().use { out ->
            val bitmaps = listOf(1, 0).map { CreationGifExporter.decodeFrame(files[it]) }
            try { GifEncoder.encode(512, 512, 2, out, frameAt = { GifEncoder.GifFrame(bitmaps[it], 1000) }) }
            finally { bitmaps.forEach { it.recycle() } }
            out.fd.sync()
        }
        return Fixture(root, files, engine, journal, file, sources = files.map { CreationGifSource(Uri.fromFile(it)).identity },
            sourceHashes = listOf(1, 0).map { hash(Uri.fromFile(files[it])) })
    }
    private fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun byteHash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun metadata(uri: Uri): CreationGifPublicationDestination = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), null, null, null)!!.use {
        check(it.moveToFirst()); val result = CreationGifPublicationDestination(uri.toString(), it.getString(0), it.getString(1), it.getString(2), it.getString(3),
            it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0)
        check(!it.moveToNext()); result
    }
    private fun assertBase(f: Fixture, current: CreationGifPublicationDestination) {
        val base = f.base!!
        assertEquals(base.uri, current.uri); assertEquals(base.generationAdded, current.generationAdded)
        assertEquals(base.ownerPackage, current.ownerPackage); assertEquals(context.packageName, current.ownerPackage)
        assertEquals(base.relativePath, current.relativePath); assertEquals(base.mimeType, current.mimeType)
        assertEquals(f.renamed ?: base.displayName, current.displayName); assertFalse(current.trashed)
        assertTrue(current.generationModified >= base.generationModified)
    }
    private fun write(uri: Uri, bytes: ByteArray) {
        resolver.openFileDescriptor(uri, "wt")!!.use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out -> out.write(bytes); out.fd.sync() }
        }
    }
    private fun initial(f: Fixture) = CreationGifPublicationReceipt(f.id, UUID.randomUUID().toString(), f.sources,
        f.sourceHashes, listOf(1, 0), 1, hash(Uri.fromFile(f.expectedFile)), f.expectedFile.length())
    private fun stage(f: Fixture, full: Boolean, ready: Boolean = false, retainIntent: Boolean = false): CreationGifPublicationReceipt {
        val initial = initial(f); f.journal.begin(initial)
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "UGallery-GIF-${initial.token}.gif")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGallery/GIF/")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        f.base = metadata(uri)
        val inserted = initial.copy(destination = f.base, phase = CreationGifPublicationPhase.Inserted)
        if (!retainIntent) f.journal.advance(initial, inserted)
        val bytes = if (full) f.expectedFile.readBytes() else byteArrayOf(1, 2, 3, 4)
        write(uri, bytes); f.cleanupHash = byteHash(bytes)
        return if (ready) inserted.copy(destination = metadata(uri), phase = CreationGifPublicationPhase.Ready).also { f.journal.advance(inserted, it) }
        else if (retainIntent) initial else inserted
    }
    private suspend fun inspect(f: Fixture) = f.engine.inspectResolution(f.engine.listPublications().single { it.id == f.id })
    private fun assertAbsent(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use { assertFalse(it.moveToFirst()) }
    /** Passing fixtures alone are cleaned. A mismatch preserves the private evidence and every remaining output. */
    private fun cleanup(f: Fixture) {
        val base = f.base!!; val uri = Uri.parse(base.uri)
        if (f.removed) assertAbsent(uri)
        else {
            val current = metadata(uri); assertBase(f, current); assertEquals(f.cleanupHash, hash(uri)); assertEquals(current, metadata(uri))
            assertEquals(1, resolver.delete(uri,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
                    "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND COALESCE(${MediaStore.MediaColumns.SIZE},0)=CAST(? AS INTEGER) AND " +
                    "${MediaStore.MediaColumns.IS_PENDING}=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0",
                arrayOf(current.ownerPackage, current.displayName, current.relativePath, current.mimeType, current.generationAdded.toString(),
                    current.generationModified.toString(), current.sizeBytes.toString(), if (current.pending) "1" else "0")))
            assertAbsent(uri)
        }
        assertNull(f.journal.readEntry(f.id).receipt)
        f.files.forEachIndexed { i, file -> if (file.exists()) assertEquals(f.originals[i], file.readBytes().toList()) }
        assertTrue(f.root.deleteRecursively())
    }

    @Test fun completeReadyWithoutOriginalsPublishesSameBytesAndForgetKeepsPublishedResult() = runBlocking {
        val f = fixture(); var pass = false
        try {
            stage(f, full = true, ready = true)
            f.files.forEachIndexed { i, file -> assertEquals(f.originals[i], file.readBytes().toList()); check(file.delete()) }
            val proof = inspect(f); assertTrue(proof.canComplete); assertTrue(proof.canRemovePending)
            assertTrue(f.engine.completePublication(proof)); assertFalse(f.engine.completePublication(proof))
            val published = inspect(f); assertEquals(CreationGifPublicationStatus.Published, published.recovery!!.status)
            assertFalse(published.canRemovePending); assertEquals(f.base!!.uri, published.recovery!!.resultUri)
            val preview = f.engine.loadVerifiedResultPreview(published.recovery!!.receipt!!)!!
            try { assertTrue(preview.width > 0 && preview.height > 0) } finally { preview.recycle() }
            assertTrue(f.engine.forgetPublication(published)); assertEquals(f.cleanupHash, hash(Uri.parse(f.base!!.uri)))
            pass = true
        } finally { if (pass) cleanup(f) }
    }
    @Test fun nonemptyPartialStreamIsNotAnEmptySizeColumnAndCanBeExplicitlyRemoved() = runBlocking {
        val f = fixture(); var pass = false
        try {
            stage(f, full = false)
            val proof = inspect(f)
            assertEquals(4L, proof.pendingBytesSize); assertEquals(byteHash(byteArrayOf(1, 2, 3, 4)), proof.pendingBytesSha256)
            assertFalse(proof.backingFileMissing); assertFalse(proof.canComplete); assertTrue(proof.canRemovePending)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Gif partial proof: metadataSize=${proof.pendingDestination!!.sizeBytes}, actualBytes=${proof.pendingBytesSize}, backingFileMissing=${proof.backingFileMissing}\n")
            })
            assertTrue(f.engine.removePendingPublication(proof)); f.removed = true; pass = true
        } finally { if (pass) cleanup(f) }
    }
    @Test fun changedPartialBytesRejectStaleRemovalEvenWhenProviderSizeRemainsZero() = runBlocking {
        val f = fixture(); var pass = false
        try {
            stage(f, full = false); val old = inspect(f); val uri = Uri.parse(f.base!!.uri)
            val replacement = byteArrayOf(5, 6, 7, 8); write(uri, replacement); f.cleanupHash = byteHash(replacement)
            assertFalse(f.engine.removePendingPublication(old)); assertEquals(f.cleanupHash, hash(uri))
            val fresh = inspect(f); assertNotEquals(old.pendingBytesSha256, fresh.pendingBytesSha256)
            assertTrue(f.engine.removePendingPublication(fresh)); f.removed = true; pass = true
        } finally { if (pass) cleanup(f) }
    }
    @Test fun unknownIntentDoesNotAdoptMatchingNameAndForgetPreservesDecoyOutput() = runBlocking {
        val f = fixture(); var pass = false
        try {
            stage(f, full = true, retainIntent = true)
            val proof = inspect(f); assertNull(proof.pendingDestination); assertTrue(proof.canForget)
            assertFalse(proof.canComplete); assertFalse(proof.canRemovePending)
            assertFalse(f.engine.completePublication(proof)); assertFalse(f.engine.removePendingPublication(proof))
            assertTrue(f.engine.forgetPublication(proof)); assertEquals(f.cleanupHash, hash(Uri.parse(f.base!!.uri))); pass = true
        } finally { if (pass) cleanup(f) }
    }
    @Test fun changedDestinationAnchorOnlyAllowsForgettingExactTracking() = runBlocking {
        val f = fixture(); var pass = false
        try {
            val receipt = stage(f, full = true, ready = true); val uri = Uri.parse(f.base!!.uri)
            val name = "renamed-${receipt.token}.gif"
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)); f.renamed = name
            val proof = inspect(f); assertEquals(CreationGifPublicationStatus.Conflict, proof.recovery!!.status)
            assertTrue(proof.canForget); assertFalse(proof.canComplete); assertFalse(proof.canRemovePending)
            assertFalse(f.engine.removePendingPublication(proof)); assertTrue(f.engine.forgetPublication(proof))
            assertEquals(f.cleanupHash, hash(uri)); pass = true
        } finally { if (pass) cleanup(f) }
    }
    @Test fun activeDurableWriterMakesInspectBusyAndPreventsMarkerDeletionWithoutWaiting() = runBlocking {
        val f = fixture(); var pass = false; val started = CountDownLatch(1); val release = CountDownLatch(1)
        val writer = async(Dispatchers.IO) {
            runCatching {
                val progress: (Int) -> Unit = { if (it == 90) { started.countDown(); check(release.await(10, TimeUnit.SECONDS)); throw CancellationException("controlled pending writer") } }
                f.engine.export(f.id, CreationGifRequest(f.files.map { CreationGifSource(Uri.fromFile(it)) }, 1), listOf(1, 0), onProgress = progress)
            }
        }
        try {
            withContext(Dispatchers.IO) { check(started.await(10, TimeUnit.SECONDS)) }
            val entry = f.engine.listPublications().single { it.id == f.id }; f.base = entry.receipt!!.destination
            val busy = withTimeout(1000) { f.engine.inspectResolution(entry) }; assertTrue(busy.busy)
            assertFalse(withTimeout(1000) { f.engine.forgetPublication(busy.copy(busy = false, canForget = true)) })
            assertEquals(entry, f.journal.readEntry(f.id))
            release.countDown(); writer.await()
            val stopped = inspect(f); assertTrue(stopped.canRemovePending); assertFalse(stopped.canComplete)
            assertTrue(f.engine.removePendingPublication(stopped)); f.removed = true; pass = true
        } finally { release.countDown(); writer.await(); if (pass) cleanup(f) }
    }
}
