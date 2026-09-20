package com.ugallery.feature.motionphotos

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoPublicationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private class Fixture(val root: File, val source: File, val session: MotionPhotoSession,
        val publisher: MotionPhotoPublication, val journal: MotionPhotoPublicationJournal,
        val publicationId: String = UUID.randomUUID().toString()) {
        var base: MotionPhotoPublicationDestination? = null
        var expectedHash: String? = null
        var expectedSize: Long = 0
        var renamed: String? = null
    }
    private suspend fun fixture(): Fixture {
        val root = File(context.cacheDir.canonicalFile, "motion-publication-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val created = MotionPhotoFixtures.create(context)
        val source = created.copyTo(File(root, "source.jpg")); check(created.delete())
        val journal = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
        return Fixture(root, source, MotionPhotoSession.open(context, MotionPhotoFixtures.input(source)), MotionPhotoPublication(context), journal)
    }
    private fun journalFile(f: Fixture) = File(context.noBackupFilesDir.canonicalFile, "motion-publications/${f.publicationId}.bin")
    private fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { stream ->
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun metadata(uri: Uri): MotionPhotoPublicationDestination = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), null, null, null)!!.use {
        check(it.moveToFirst()); val value = MotionPhotoPublicationDestination(uri.toString(), it.getString(0), it.getString(1), it.getString(2),
            it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0)
        check(!it.moveToNext()); value
    }
    private fun bind(f: Fixture, receipt: MotionPhotoPublicationReceipt) {
        assertEquals(f.publicationId, receipt.publicationId); val destination = receipt.destination!!
        assertEquals(context.packageName, destination.ownerPackage)
        val video = receipt.kind == MotionPhotoPublicationKind.Clip
        assertEquals("UGallery-Motion-${receipt.token}.${if (video) "mp4" else "jpg"}", destination.displayName)
        assertEquals(if (video) "Movies/UGallery/Motion/" else "Pictures/UGallery/Motion/", destination.relativePath)
        f.base?.let { assertBase(it, destination, it.displayName) }
        if (f.base == null) f.base = destination
        f.expectedHash = receipt.renderSha256; f.expectedSize = receipt.renderSizeBytes
    }
    private fun assertBase(base: MotionPhotoPublicationDestination, current: MotionPhotoPublicationDestination, name: String) {
        assertEquals(base.uri, current.uri); assertEquals(base.generationAdded, current.generationAdded)
        assertEquals(base.ownerPackage, current.ownerPackage); assertEquals(base.relativePath, current.relativePath)
        assertEquals(base.mimeType, current.mimeType); assertEquals(name, current.displayName)
        assertTrue(current.generationModified >= base.generationModified); assertFalse(current.trashed)
    }
    /** Evidence and fixture survive every failure. No current URI/hash is adopted as ownership. */
    private fun cleanupSuccessful(f: Fixture) {
        val base = f.base!!; val uri = Uri.parse(base.uri); val current = metadata(uri)
        assertBase(base, current, f.renamed ?: base.displayName)
        assertEquals(f.expectedSize, current.sizeBytes); assertEquals(f.expectedHash, hash(uri)); assertEquals(current, metadata(uri))
        val marker = journalFile(f).takeIf(File::exists)?.readBytes()
        assertEquals(1, resolver.delete(uri,
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
                "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND COALESCE(${MediaStore.MediaColumns.SIZE},0)=CAST(? AS INTEGER) AND " +
                "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.IS_PENDING}=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0",
            arrayOf(current.ownerPackage, current.displayName, current.relativePath, current.generationAdded.toString(), current.generationModified.toString(),
                current.sizeBytes.toString(), current.mimeType, if (current.pending) "1" else "0")))
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use { assertFalse(it.moveToFirst()) }
        if (marker != null) {
            val receipt = f.journal.read(f.publicationId)!!
            assertEquals(base.uri, receipt.destination!!.uri); assertEquals(base.generationAdded, receipt.destination!!.generationAdded)
            assertArrayEquals(marker, journalFile(f).readBytes())
            // Only this test's exact receipt is retired from private fixture storage after its output absence proof.
            assertTrue(f.journal.retire(receipt))
        }
        assertFalse(f.journal.hasEntry(f.publicationId))
        f.session.close(); assertTrue(f.root.deleteRecursively())
    }
    private suspend fun export(f: Fixture, kind: MotionPhotoPublicationKind, callback: (Uri) -> Unit = {}): Uri =
        if (kind == MotionPhotoPublicationKind.Frame) f.session.exportFrame(f.publicationId, f.session.frameTimesUs[4], callback)
        else f.session.exportClip(f.publicationId, callback)

    @Test fun jpegCallbackGapRecoversSameFrameWithoutOriginalsAndExplicitRetirementKeepsPublicBytes() = runBlocking {
        callbackGap(MotionPhotoPublicationKind.Frame)
    }
    @Test fun mp4CancellationAfterCommitRecoversSameClipWithoutOriginalsAndExplicitRetirementKeepsPublicBytes() = runBlocking {
        callbackGap(MotionPhotoPublicationKind.Clip)
    }
    private suspend fun callbackGap(kind: MotionPhotoPublicationKind) {
        val f = fixture(); var complete = false
        try {
            val original = MotionPhotoSession.hash(f.source); val clip = MotionPhotoSession.hash(f.session.clip)
            var output: Uri? = null; var callbacks = 0
            try { export(f, kind) {
                output = it; bind(f, f.journal.read(f.publicationId)!!); callbacks++
                if (kind == MotionPhotoPublicationKind.Clip) throw CancellationException("commit callback")
                else throw IllegalStateException("commit callback")
            }; fail("Callback gap expected") } catch (_: Exception) { }
            assertNotNull(output); assertEquals(1, callbacks); assertEquals(original, MotionPhotoSession.hash(f.source))
            val recovery = f.publisher.reconcile(f.publicationId); assertEquals(MotionPhotoPublicationStatus.Published, recovery.status)
            val receipt = recovery.receipt!!; assertEquals(original, receipt.sourceSha256); assertEquals(kind, receipt.kind)
            if (kind == MotionPhotoPublicationKind.Clip) assertEquals(clip, receipt.renderSha256)
            else assertEquals(f.session.frameTimesUs[4], receipt.selectedTimeUs!!)
            assertEquals(f.session.durationUs, receipt.durationUs)
            val marker = journalFile(f).readBytes()
            check(f.source.delete()); f.session.close()
            assertEquals(output, export(f, kind) { assertEquals(output, it); callbacks++ }); assertEquals(2, callbacks)
            assertEquals(recovery, f.publisher.reconcile(f.publicationId)); assertArrayEquals(marker, journalFile(f).readBytes())
            val preview = f.publisher.loadVerifiedPreview(receipt, 320)!!
            try { assertTrue(preview.width in 1..320 && preview.height in 1..320) } finally { preview.recycle() }
            File(f.root, "retired-receipt.bin").outputStream().use { it.write(marker); it.fd.sync() }
            assertTrue(f.publisher.retire(receipt)); assertFalse(f.publisher.hasPublication(f.publicationId))
            assertEquals(receipt.renderSha256, hash(output!!)); assertNull(f.publisher.loadVerifiedPreview(receipt, 320))
            complete = true
        } finally { if (complete) cleanupSuccessful(f) }
    }

    @Test fun jpegReadyReceiptRecognizesCommitAndStaticPreviewWithoutRewritingMarker() = runBlocking { readyGap(MotionPhotoPublicationKind.Frame) }
    @Test fun mp4ReadyReceiptRecognizesCommitAndStaticPreviewWithoutRewritingMarker() = runBlocking { readyGap(MotionPhotoPublicationKind.Clip) }
    private suspend fun readyGap(kind: MotionPhotoPublicationKind) {
        val f = fixture(); var complete = false
        try {
            val file = File(f.root, if (kind == MotionPhotoPublicationKind.Frame) "expected.jpg" else "expected.mp4")
            val time = f.session.frameTimesUs[4]
            if (kind == MotionPhotoPublicationKind.Frame) f.session.withFrameFile(time) { it.copyTo(file); true }
            else f.session.clip.copyTo(file)
            val initial = MotionPhotoPublicationReceipt(f.publicationId, UUID.randomUUID().toString(), f.session.input.uri.toString(),
                null, null, f.session.input.identity, f.session.originalSha256, kind,
                if (kind == MotionPhotoPublicationKind.Frame) time else null, f.session.durationUs, MotionPhotoSession.hash(file), file.length())
            f.journal.begin(initial); val video = kind == MotionPhotoPublicationKind.Clip
            val uri = resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "UGallery-Motion-${initial.token}.${if (video) "mp4" else "jpg"}")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/UGallery/Motion/" else "Pictures/UGallery/Motion/")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
            val inserted = initial.copy(destination = metadata(uri), phase = MotionPhotoPublicationPhase.Inserted); bind(f, inserted); f.journal.advance(initial, inserted)
            resolver.openFileDescriptor(uri, "w")!!.use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                file.inputStream().use { it.copyTo(out) }; out.fd.sync()
            } }
            val ready = inserted.copy(destination = metadata(uri), phase = MotionPhotoPublicationPhase.Ready); bind(f, ready); f.journal.advance(inserted, ready)
            assertEquals(MotionPhotoPublicationStatus.Incomplete, f.publisher.reconcile(f.publicationId).status)
            val marker = journalFile(f).readBytes()
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            check(f.source.delete()); f.session.close()
            val recovery = f.publisher.reconcile(f.publicationId); assertEquals(MotionPhotoPublicationStatus.Published, recovery.status)
            assertEquals(uri.toString(), recovery.resultUri); assertEquals(ready, f.journal.read(f.publicationId))
            val preview = f.publisher.loadVerifiedPreview(recovery.receipt!!, 320)!!
            try { assertTrue(preview.width in 1..320 && preview.height in 1..320) } finally { preview.recycle() }
            assertArrayEquals(marker, journalFile(f).readBytes()); bind(f, recovery.receipt!!); complete = true
        } finally { if (complete) cleanupSuccessful(f) }
    }

    @Test fun jpegConflictBlocksHandoffPreviewRetirementAndReexport() = runBlocking { conflict(MotionPhotoPublicationKind.Frame) }
    @Test fun mp4ConflictBlocksHandoffPreviewRetirementAndReexport() = runBlocking { conflict(MotionPhotoPublicationKind.Clip) }
    private suspend fun conflict(kind: MotionPhotoPublicationKind) {
        val f = fixture(); var complete = false
        try {
            val uri = export(f, kind); val receipt = f.journal.read(f.publicationId)!!; bind(f, receipt)
            val name = "renamed-${receipt.token}.${if (kind == MotionPhotoPublicationKind.Frame) "jpg" else "mp4"}"
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null))
            f.renamed = name; val current = metadata(uri); assertBase(f.base!!, current, name)
            assertEquals(MotionPhotoPublicationStatus.Conflict, f.publisher.reconcile(f.publicationId).status)
            assertNull(f.publisher.loadVerifiedPreview(receipt)); assertFalse(f.publisher.retire(receipt))
            try { export(f, kind); fail("Conflict must not reexport") } catch (_: IllegalStateException) { }
            assertEquals(current, metadata(uri)); assertEquals(receipt.renderSha256, hash(uri)); assertEquals(receipt, f.journal.read(f.publicationId))
            complete = true
        } finally { if (complete) cleanupSuccessful(f) }
    }
}
