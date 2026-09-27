package com.librestatic.lightforge.feature.collage

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CreationGifPublicationJournalDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private class Fixture(val root: File, val files: List<File>, val journal: CreationGifPublicationJournal,
        val engine: CreationGifExporter, val request: CreationGifRequest, val order: List<Int> = listOf(3, 0),
        val session: String = UUID.randomUUID().toString()) {
        var ownedDestination: CreationGifPublicationDestination? = null
        var cleanupSha256: String? = null
        var expectedSize: Long = 0
        var allowedRenamedDisplayName: String? = null
    }
    private fun fixture(): Fixture {
        val root = File(context.cacheDir.canonicalFile, "gif-publication-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW).mapIndexed { index, color ->
            File(root, "$index.png").also { file ->
                val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } }
                finally { bitmap.recycle() }
            }
        }
        val journal = CreationGifPublicationJournal(File(root, "journal")); val engine = CreationGifExporter(context, journal)
        return Fixture(root, files, journal, engine, CreationGifRequest(files.map { CreationGifSource(Uri.fromFile(it)) }, 1))
    }
    private fun bindPublished(f: Fixture, receipt: CreationGifPublicationReceipt) {
        bindOwned(f, receipt); f.expectedSize = receipt.renderSizeBytes; f.cleanupSha256 = receipt.renderSha256
    }
    private fun metadata(uri: Uri): CreationGifPublicationDestination = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), null, null, null)!!.use {
        check(it.moveToFirst()); val result = CreationGifPublicationDestination(uri.toString(), it.getString(0), it.getString(1),
            it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0)
        check(!it.moveToNext()); result
    }
    private fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { CreationGifExporter.sha256(it) }
    private fun sourceHashes(f: Fixture) = f.files.map { it.inputStream().use { input -> CreationGifExporter.sha256(input) } }
    /** Only a destination captured from this test's insert/receipt establishes ownership. */
    private fun bindOwned(f: Fixture, receipt: CreationGifPublicationReceipt) {
        assertEquals(f.session, receipt.sessionId)
        val destination = receipt.destination!!
        assertEquals("Lightforge-GIF-${receipt.token}.gif", destination.displayName)
        assertEquals(context.packageName, destination.ownerPackage)
        assertEquals("Pictures/Lightforge/GIF/", destination.relativePath)
        assertEquals("image/gif", destination.mimeType)
        f.ownedDestination?.let { assertBase(it, destination, it.displayName) }
        if (f.ownedDestination == null) f.ownedDestination = destination
    }
    private fun assertBase(base: CreationGifPublicationDestination, current: CreationGifPublicationDestination, name: String) {
        assertEquals(base.uri, current.uri); assertEquals(base.generationAdded, current.generationAdded)
        assertEquals(base.ownerPackage, current.ownerPackage); assertEquals(base.relativePath, current.relativePath)
        assertEquals(base.mimeType, current.mimeType); assertEquals(name, current.displayName)
        assertTrue(current.generationModified >= base.generationModified); assertFalse(current.trashed)
    }
    /** Failed tests retain the complete private fixture and journal. Cleanup never adopts current bytes as its expected hash. */
    private fun cleanupSuccessfulFixture(f: Fixture) {
        val base = checkNotNull(f.ownedDestination); val expectedHash = checkNotNull(f.cleanupSha256)
        val uri = Uri.parse(base.uri); val current = metadata(uri)
        assertBase(base, current, f.allowedRenamedDisplayName ?: base.displayName)
        assertEquals(if (expectedHash == EmptySha256) 0L else f.expectedSize, current.sizeBytes)
        val actualHash = try { hash(uri) } catch (missing: FileNotFoundException) {
            // A newly inserted row may have no backing file until the first write. Do not call it empty bytes,
            // or create a file merely for cleanup. Only the original exact zero-size pending row may be removed.
            if (expectedHash != EmptySha256 || !base.pending || !current.pending || current.sizeBytes != 0L ||
                missing.message?.contains("ENOENT") != true) throw missing
            assertEquals(base, current); assertEquals(current, metadata(uri))
            val retained = f.journal.read(f.session)!!
            assertEquals(CreationGifPublicationPhase.Inserted, retained.phase)
            assertEquals(base, retained.destination)
            null // Proven absent backing file is not an empty-file SHA.
        }
        if (actualHash != null) assertEquals(expectedHash, actualHash)
        assertEquals(current, metadata(uri))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "GIF fixture cleanup proof: session=${f.session}, uri=${base.uri}, " +
                "GA=${current.generationAdded}, GM=${current.generationModified}, size=${current.sizeBytes}, " +
                "backingFileAbsent=${actualHash == null}, sha256=${actualHash ?: "not-applicable"}\n")
        })
        assertEquals(1, resolver.delete(uri,
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
                "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND COALESCE(${MediaStore.MediaColumns.SIZE},0)=CAST(? AS INTEGER) AND " +
                "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.IS_PENDING}=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0",
            arrayOf(current.ownerPackage, current.displayName, current.relativePath, current.generationAdded.toString(),
                current.generationModified.toString(), current.sizeBytes.toString(), current.mimeType, if (current.pending) "1" else "0")))
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use { assertFalse(it.moveToFirst()) }
        assertTrue(f.root.deleteRecursively())
    }
    private companion object {
        const val EmptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @Test fun callbackFailureAndRepeatedExportKeepSameGifWithoutReadingRemovedOrMissingSources() = runBlocking {
        val f = fixture(); var complete = false
        try {
            val originals = sourceHashes(f)
            check(f.files[1].delete()); check(f.files[2].delete()) // Removed draft frames must never be read.
            var output: Uri? = null; var callbacks = 0
            try { f.engine.export(f.session, f.request, f.order, onPublished = {
                output = it; bindPublished(f, f.journal.read(f.session)!!); callbacks++; throw IllegalStateException("callback gap")
            }); fail("Callback must fail") } catch (_: IllegalStateException) { }
            assertNotNull(output); assertEquals(1, callbacks)
            val recovered = f.engine.reconcile(f.session); assertEquals(CreationGifPublicationStatus.Published, recovered.status)
            assertEquals(f.order, recovered.receipt!!.order); assertEquals(1, recovered.receipt!!.secondsPerFrame)
            assertEquals(f.request.sources.map { it.identity }, recovered.receipt!!.sourceIdentities)
            assertEquals(f.order.map { originals[it] }, recovered.receipt!!.sourceSha256)
            val marker = File(f.root, "journal/${f.session}.bin").readBytes()
            f.order.forEach { index -> assertEquals(originals[index], hash(Uri.fromFile(f.files[index]))); check(f.files[index].delete()) }
            val same = f.engine.export(f.session, f.request, f.order, onPublished = { assertEquals(output, it); callbacks++ })
            assertEquals(output, same); assertEquals(2, callbacks)
            assertEquals(recovered, f.engine.reconcile(f.session))
            assertArrayEquals(marker, File(f.root, "journal/${f.session}.bin").readBytes())
            complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
    @Test fun readyReceiptRecoversCommittedGifWithExactDurationAndDoesNotRewriteJournal() = runBlocking {
        val f = fixture(); var complete = false
        try {
            val file = File(f.root, "expected.gif")
            file.outputStream().use { out ->
                val bitmaps = f.order.map { CreationGifExporter.decodeFrame(f.files[it]) }
                try { GifEncoder.encode(512, 512, bitmaps.size, out, frameAt = { GifEncoder.GifFrame(bitmaps[it], 1000) }) }
                finally { bitmaps.forEach { it.recycle() } }
                out.fd.sync()
            }
            CreationGifExporter.validate(file, 2000)
            val initial = CreationGifPublicationReceipt(f.session, UUID.randomUUID().toString(), f.request.sources.map { it.identity },
                f.order.map { hash(Uri.fromFile(f.files[it])) }, f.order, 1, hash(Uri.fromFile(file)), file.length())
            f.journal.begin(initial)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-GIF-${initial.token}.gif")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lightforge/GIF/")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/gif"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            val inserted = initial.copy(phase = CreationGifPublicationPhase.Inserted, destination = metadata(uri))
            bindOwned(f, inserted); f.cleanupSha256 = EmptySha256; f.journal.advance(initial, inserted)
            resolver.openFileDescriptor(uri, "w")!!.use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out -> file.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            }
            val ready = inserted.copy(phase = CreationGifPublicationPhase.Ready, destination = metadata(uri)); bindPublished(f, ready)
            f.journal.advance(inserted, ready)
            assertEquals(CreationGifPublicationStatus.Incomplete, f.engine.reconcile(f.session).status)
            val marker = File(f.root, "journal/${f.session}.bin").readBytes()
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            f.files.forEach { check(it.delete()) }
            val recovered = f.engine.reconcile(f.session)
            assertEquals(CreationGifPublicationStatus.Published, recovered.status); assertEquals(uri.toString(), recovered.resultUri)
            assertEquals(ready.renderSha256, hash(uri)); assertEquals(ready, f.journal.read(f.session))
            assertArrayEquals(marker, File(f.root, "journal/${f.session}.bin").readBytes())
            bindPublished(f, recovered.receipt!!); complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
    @Test fun cancellationAfterInsertionRetainsExactPendingRowAndBlocksAnotherExport() = runBlocking {
        val f = fixture(); var complete = false
        try {
            val originals = sourceHashes(f)
            try { f.engine.export(f.session, f.request, f.order, onProgress = { if (it == 90) {
                bindOwned(f, f.journal.read(f.session)!!); f.cleanupSha256 = EmptySha256
                throw CancellationException("after insert")
            } }); fail("Cancellation expected") } catch (_: CancellationException) { }
            val receipt = f.journal.read(f.session)!!; val uri = Uri.parse(receipt.destination!!.uri); val before = metadata(uri)
            assertEquals(CreationGifPublicationStatus.Incomplete, f.engine.reconcile(f.session).status)
            try { f.engine.export(f.session, f.request, f.order); fail("Incomplete export must not run") } catch (_: IllegalStateException) { }
            assertEquals(receipt, f.journal.read(f.session)); assertEquals(before, metadata(uri)); assertEquals(originals, sourceHashes(f))
            complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
    @Test fun changedPublishedMetadataBlocksHandoffRetirementAndRepeatedExportWithoutDeletingGif() = runBlocking {
        val f = fixture(); var complete = false
        try {
            val uri = f.engine.export(f.session, f.request, f.order); val receipt = f.journal.read(f.session)!!; bindPublished(f, receipt)
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "renamed-${receipt.token}.gif") }, null, null))
            f.allowedRenamedDisplayName = "renamed-${receipt.token}.gif"
            val changed = metadata(uri); assertBase(f.ownedDestination!!, changed, f.allowedRenamedDisplayName!!)
            assertEquals(CreationGifPublicationStatus.Conflict, f.engine.reconcile(f.session).status)
            assertFalse(f.engine.retirePublication(receipt))
            try { f.engine.export(f.session, f.request, f.order); fail("Conflict must not reexport") } catch (_: IllegalStateException) { }
            assertEquals(changed, metadata(uri)); assertEquals(receipt.renderSha256, hash(uri)); assertEquals(receipt, f.journal.read(f.session))
            complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
}
