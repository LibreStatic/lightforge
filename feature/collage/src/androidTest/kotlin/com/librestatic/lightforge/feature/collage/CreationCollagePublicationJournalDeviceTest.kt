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

class CreationCollagePublicationJournalDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private class Fixture(val root: File, val files: List<File>, val journal: CreationCollagePublicationJournal,
        val engine: CreationCollageExporter, val prepared: CreationCollagePrepared, val image: CreationCollageRender,
        val session: String = UUID.randomUUID().toString()) {
        var ownedDestination: CreationCollagePublicationDestination? = null
        var cleanupSha256: String? = null
        var allowedRenamedDisplayName: String? = null
    }
    private suspend fun fixture(): Fixture {
        val root = File(context.cacheDir.canonicalFile, "collage-publication-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = listOf(Color.RED, Color.BLUE).mapIndexed { index, color -> File(root, "$index.png").also { file ->
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } }
            finally { bitmap.recycle() }
        } }
        val journal = CreationCollagePublicationJournal(File(root, "journal")); val engine = CreationCollageExporter(context, journal)
        val prepared = engine.prepare(files.map { CreationCollageSource(Uri.fromFile(it)) })
        return Fixture(root, files, journal, engine, prepared, engine.render(prepared,
            CreationCollageLayout.initial(2).move(0, 1).crop(0, CreationCollageCrop(2f, -.5f, .5f))))
    }
    private fun metadata(uri: Uri): CreationCollagePublicationDestination = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), null, null, null)!!.use {
        check(it.moveToFirst()); val result = CreationCollagePublicationDestination(uri.toString(), it.getString(0), it.getString(1),
            it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0)
        check(!it.moveToNext()); result
    }
    private fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { CreationCollageExporter.digest(it) }
    private fun sourceHashes(f: Fixture) = f.files.map { it.inputStream().use { input -> CreationCollageExporter.digest(input) } }
    /** Only a destination captured from this test's insert/receipt establishes ownership. */
    private fun bindOwned(f: Fixture, receipt: CreationCollagePublicationReceipt) {
        assertEquals(f.session, receipt.sessionId)
        val destination = receipt.destination!!
        assertEquals("Lightforge-collage-${receipt.token}.png", destination.displayName)
        assertEquals(context.packageName, destination.ownerPackage)
        assertEquals("Pictures/Lightforge/Collage/", destination.relativePath)
        assertEquals("image/png", destination.mimeType)
        f.ownedDestination?.let { assertBase(it, destination, it.displayName) }
        if (f.ownedDestination == null) f.ownedDestination = destination
    }
    private fun assertBase(base: CreationCollagePublicationDestination, current: CreationCollagePublicationDestination, name: String) {
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
        assertEquals(if (expectedHash == EmptySha256) 0L else f.image.file.length(), current.sizeBytes)
        val actualHash = try { hash(uri) } catch (missing: FileNotFoundException) {
            // A newly inserted row may have no backing file until the first write. Do not call it empty bytes,
            // or create a file merely for cleanup. Only the original exact zero-size pending row may be removed.
            if (expectedHash != EmptySha256 || !base.pending || !current.pending || current.sizeBytes != 0L ||
                missing.message?.contains("ENOENT") != true) throw missing
            assertEquals(base, current); assertEquals(current, metadata(uri))
            val retained = f.journal.read(f.session)!!
            assertEquals(CreationCollagePublicationPhase.Inserted, retained.phase)
            assertEquals(base, retained.destination)
            null // Proven absent backing file is not an empty-file SHA.
        }
        if (actualHash != null) assertEquals(expectedHash, actualHash)
        assertEquals(current, metadata(uri))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Collage fixture cleanup proof: session=${f.session}, uri=${base.uri}, " +
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
        f.prepared.close(); assertTrue(f.root.deleteRecursively())
    }
    private companion object {
        const val EmptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
    @Test fun callbackFailureAndRepeatedPublishKeepOneExactResultWithoutOriginalsForReconcile() = runBlocking {
        val f = fixture(); val originals = sourceHashes(f); var output: Uri? = null; var complete = false
        try {
            var callbackCount = 0
            try { f.engine.publish(f.session, f.image, onPublished = { output = it; bindOwned(f, f.journal.read(f.session)!!); f.cleanupSha256 = f.image.sha256; callbackCount++; throw IllegalStateException("callback gap") })
                fail("Callback should fail") } catch (_: IllegalStateException) { }
            assertNotNull(output); assertEquals(1, callbackCount); assertEquals(originals, sourceHashes(f))
            val committed = f.engine.reconcile(f.session); assertEquals(CreationCollagePublicationStatus.Published, committed.status)
            val marker = File(f.root, "journal/${f.session}.bin").readBytes()
            val same = f.engine.publish(f.session, f.image, onPublished = { assertEquals(output, it); callbackCount++ })
            assertEquals(output, same); assertEquals(2, callbackCount)
            f.files.forEach { check(it.delete()) }
            assertEquals(committed, f.engine.reconcile(f.session)); assertArrayEquals(marker, File(f.root, "journal/${f.session}.bin").readBytes())
            complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
    @Test fun readyReceiptFindsPublishedBytesWithoutUpdatingMarkerOrReadingSources() = runBlocking {
        val f = fixture(); var output: Uri? = null; var complete = false
        try {
            val initial = CreationCollagePublicationReceipt(f.session, UUID.randomUUID().toString(), f.prepared.sources.map { it.identity },
                f.prepared.hashes, f.image.layout, f.image.sha256, f.image.file.length())
            f.journal.begin(initial)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-collage-${initial.token}.png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lightforge/Collage/")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!; output = uri
            val inserted = initial.copy(phase = CreationCollagePublicationPhase.Inserted, destination = metadata(uri)); bindOwned(f, inserted)
            f.cleanupSha256 = EmptySha256; f.journal.advance(initial, inserted)
            resolver.openFileDescriptor(uri, "w")!!.use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
                    f.image.file.inputStream().use { it.copyTo(stream) }; stream.fd.sync()
                }
            }
            val ready = inserted.copy(phase = CreationCollagePublicationPhase.Ready, destination = metadata(uri)); bindOwned(f, ready)
            f.cleanupSha256 = f.image.sha256; f.journal.advance(inserted, ready)
            val marker = File(f.root, "journal/${f.session}.bin").readBytes()
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            f.files.forEach { check(it.delete()) }
            val recovered = f.engine.reconcile(f.session)
            assertEquals(CreationCollagePublicationStatus.Published, recovered.status); assertEquals(uri.toString(), recovered.resultUri)
            assertEquals(ready.layout, recovered.receipt!!.layout); assertEquals(ready.renderSha256, hash(uri))
            assertEquals(ready, f.journal.read(f.session)); assertArrayEquals(marker, File(f.root, "journal/${f.session}.bin").readBytes())
            bindOwned(f, recovered.receipt!!); complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
    @Test fun cancellationAfterInsertionRetainsIncompleteIntentAndDoesNotRetryOrDeleteRow() = runBlocking {
        val f = fixture(); var output: Uri? = null; var complete = false
        try {
            val originals = sourceHashes(f)
            try { f.engine.publish(f.session, f.image, onProgress = { if (it == 90) { bindOwned(f, f.journal.read(f.session)!!); f.cleanupSha256 = EmptySha256; throw CancellationException("after insertion") } })
                fail("Cancellation expected") } catch (_: CancellationException) { }
            val receipt = f.journal.read(f.session)!!; output = Uri.parse(receipt.destination!!.uri)
            val before = metadata(output!!); assertTrue(before.pending)
            assertEquals(CreationCollagePublicationStatus.Incomplete, f.engine.reconcile(f.session).status)
            try { f.engine.publish(f.session, f.image); fail("An incomplete session must not reexport") } catch (_: IllegalStateException) { }
            assertEquals(receipt, f.journal.read(f.session)); assertEquals(before, metadata(output!!)); assertEquals(originals, sourceHashes(f)); complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
    @Test fun changedPublishedMetadataIsConflictAndResultIsNeverDeletedOrReexported() = runBlocking {
        val f = fixture(); var output: Uri? = null; var complete = false
        try {
            val uri = f.engine.publish(f.session, f.image); output = uri; val receipt = f.journal.read(f.session)!!
            bindOwned(f, receipt); f.cleanupSha256 = f.image.sha256
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "renamed-${receipt.token}.png") }, null, null))
            f.allowedRenamedDisplayName = "renamed-${receipt.token}.png"
            val changed = metadata(uri); assertBase(f.ownedDestination!!, changed, f.allowedRenamedDisplayName!!)
            assertEquals(CreationCollagePublicationStatus.Conflict, f.engine.reconcile(f.session).status)
            assertFalse(f.engine.retirePublication(receipt))
            try { f.engine.publish(f.session, f.image); fail("Conflict must not reexport") } catch (_: IllegalStateException) { }
            assertEquals(changed, metadata(uri)); assertEquals(f.image.sha256, hash(uri)); assertEquals(receipt, f.journal.read(f.session)); complete = true
        } finally { if (complete) cleanupSuccessfulFixture(f) }
    }
}
