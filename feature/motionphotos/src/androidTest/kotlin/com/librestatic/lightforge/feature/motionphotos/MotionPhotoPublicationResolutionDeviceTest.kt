package com.librestatic.lightforge.feature.motionphotos

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Small owned fixtures; failed proofs preserve outputs, journals and original bytes for diagnosis. */
class MotionPhotoPublicationResolutionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private data class Fixture(val root: File, val source: File, val input: MotionPhotoInput,
        val kind: MotionPhotoPublicationKind, val selected: Long?, val duration: Long,
        var render: ByteArray, val journal: MotionPhotoPublicationJournal, val publisher: MotionPhotoPublication,
        val id: String = UUID.randomUUID().toString(), val token: String = UUID.randomUUID().toString(),
        var anchor: MotionPhotoPublicationDestination? = null, var expectedBytes: ByteArray? = null,
        var renamed: String? = null, var removed: Boolean = false)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun fixture(kind: MotionPhotoPublicationKind): Fixture {
        val root = File(context.cacheDir.canonicalFile, "motion-resolution-${UUID.randomUUID()}").apply { check(mkdir()) }
        val generated = MotionPhotoFixtures.create(context); val generatedBytes = generated.readBytes()
        val source = generated.copyTo(File(root, "source.jpg")); check(source.readBytes().contentEquals(generatedBytes))
        check(generated.readBytes().contentEquals(generatedBytes) && generated.delete())
        val input = MotionPhotoFixtures.input(source); val session = MotionPhotoSession.open(context, input)
        val duration = session.durationUs; val selected = if (kind == MotionPhotoPublicationKind.Frame) session.frameTimesUs[4] else null
        val render = try {
            if (selected == null) session.clip.readBytes() else {
                var frame: ByteArray? = null
                session.withFrameFile(selected) { frame = it.readBytes(); true }; checkNotNull(frame)
            }
        } finally { session.close() }
        val journal = MotionPhotoPublicationJournal(File(root, "journal"))
        return Fixture(root, source, input, kind, selected, duration, render, journal, MotionPhotoPublication(context, journal))
    }
    private fun metadata(uri: Uri): MotionPhotoPublicationDestination? = resolver.query(uri, Columns, null, null, null)!!.use {
        if (!it.moveToFirst()) return@use null
        val d = MotionPhotoPublicationDestination(uri.toString(), it.getString(0), it.getString(1), it.getString(2), it.getString(3),
            it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0)
        check(!it.moveToNext()); d
    }
    private fun initial(f: Fixture) = MotionPhotoPublicationReceipt(f.id, f.token, f.input.uri.toString(), null, null,
        f.input.identity, hash(f.source.readBytes()), f.kind, f.selected, f.duration, hash(f.render), f.render.size.toLong())
    private fun seedPending(f: Fixture, ready: Boolean, bytes: ByteArray = f.render) {
        val r = initial(f); f.journal.begin(r)
        val clip = f.kind == MotionPhotoPublicationKind.Clip
        val uri = checkNotNull(resolver.insert(if (clip) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-Motion-${f.token}.${if (clip) "mp4" else "jpg"}")
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (clip) "Movies/Lightforge/Motion/" else "Pictures/Lightforge/Motion/")
                put(MediaStore.MediaColumns.MIME_TYPE, if (clip) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val first = checkNotNull(metadata(uri)); f.anchor = first
        assertEquals(context.packageName, first.ownerPackage)
        val inserted = r.copy(phase = MotionPhotoPublicationPhase.Inserted, destination = first); f.journal.advance(r, inserted)
        writeOwned(f, bytes)
        if (ready) f.journal.advance(inserted, inserted.copy(phase = MotionPhotoPublicationPhase.Ready, destination = metadata(uri)))
    }
    private fun writeOwned(f: Fixture, bytes: ByteArray) {
        val uri = Uri.parse(checkNotNull(f.anchor).uri)
        resolver.openFileDescriptor(uri, "w")!!.use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes); it.fd.sync() }
        }
        f.expectedBytes = bytes.copyOf()
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }
    private suspend fun inspect(f: Fixture) = f.publisher.inspectResolution(f.journal.readEntry(f.id))

    private fun cleanupSuccessful(f: Fixture) {
        val base = f.anchor!!; val uri = Uri.parse(base.uri)
        if (f.removed) assertNull(metadata(uri)) else {
            val current = checkNotNull(metadata(uri))
            assertEquals(base.ownerPackage, current.ownerPackage); assertEquals(base.generationAdded, current.generationAdded)
            assertEquals(base.relativePath, current.relativePath); assertEquals(base.mimeType, current.mimeType)
            assertEquals(f.renamed ?: base.displayName, current.displayName); assertFalse(current.trashed)
            assertArrayEquals(f.expectedBytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(current, metadata(uri))
            assertEquals(1, resolver.delete(uri, Where, args(current))); assertNull(metadata(uri))
        }
        val entry = f.journal.readEntry(f.id)
        if (entry.receipt != null) {
            assertEquals(base.uri, entry.receipt.destination!!.uri)
            assertEquals(base.generationAdded, entry.receipt.destination!!.generationAdded)
            assertTrue(f.journal.forget(entry))
        }
        assertFalse(f.journal.hasEntry(f.id))
        check(f.root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        assertTrue(f.root.deleteRecursively())
    }

    @Test fun completeReadyJpegWithoutOriginalAndForgetLeavesSamePublicBytes() = runBlocking {
        readyWithoutOriginal(MotionPhotoPublicationKind.Frame)
    }
    @Test fun completeReadyMp4WithoutOriginalKeepsExactClipAndDuration() = runBlocking {
        readyWithoutOriginal(MotionPhotoPublicationKind.Clip)
    }
    private suspend fun readyWithoutOriginal(kind: MotionPhotoPublicationKind) {
        val f = fixture(kind); var success = false
        try {
            seedPending(f, ready = true); check(f.source.delete())
            val proof = inspect(f)
            assertTrue(proof.canComplete && proof.canRemovePending && proof.canForget)
            assertEquals(hash(f.render), proof.pendingBytesSha256); assertEquals(f.render.size.toLong(), proof.pendingBytesSize)
            assertFalse(proof.backingFileMissing)
            assertTrue(f.publisher.completePublication(proof)); assertFalse(f.publisher.completePublication(proof))
            val after = inspect(f)
            assertEquals(MotionPhotoPublicationStatus.Published, after.recovery!!.status)
            assertEquals(MotionPhotoPublicationPhase.Published, f.journal.read(f.id)!!.phase)
            assertFalse(after.canComplete || after.canRemovePending); assertTrue(after.canForget)
            assertEquals(f.duration, after.recovery.receipt!!.durationUs)
            val preview = checkNotNull(f.publisher.loadVerifiedPreview(after.recovery.receipt!!, 320)); preview.recycle()
            assertTrue(f.publisher.forgetPublication(after)); assertFalse(f.journal.hasEntry(f.id))
            assertArrayEquals(f.render, resolver.openInputStream(Uri.parse(f.anchor!!.uri))!!.use { it.readBytes() })
            success = true
        } finally { if (success) cleanupSuccessful(f) }
    }

    @Test fun partialAndEmptyStreamsRequireTheirActualHashAndStaleProofCannotDelete() = runBlocking {
        for (bytesCount in listOf(0, 97)) {
            val f = fixture(MotionPhotoPublicationKind.Frame); var success = false
            try {
                val partial = f.render.take(bytesCount).toByteArray(); seedPending(f, ready = false, bytes = partial)
                val proof = inspect(f)
                assertEquals(partial.size.toLong(), proof.pendingBytesSize); assertEquals(hash(partial), proof.pendingBytesSha256)
                assertFalse(proof.backingFileMissing || proof.canComplete); assertTrue(proof.canRemovePending)
                val changed = partial + byteArrayOf(7); writeOwned(f, changed)
                assertFalse(f.publisher.removePendingPublication(proof)); assertNotNull(metadata(Uri.parse(f.anchor!!.uri)))
                val refreshed = inspect(f); assertEquals(hash(changed), refreshed.pendingBytesSha256)
                assertTrue(f.publisher.removePendingPublication(refreshed)); f.removed = true
                assertNull(metadata(Uri.parse(f.anchor!!.uri))); assertFalse(f.journal.hasEntry(f.id)); success = true
            } finally { if (success) cleanupSuccessful(f) }
        }
    }

    @Test fun changedAnchorAllowsOnlyExactMarkerForgetAndLeavesOutputUntouched() = runBlocking {
        val f = fixture(MotionPhotoPublicationKind.Frame); var success = false
        try {
            seedPending(f, ready = true); val proof = inspect(f); val uri = Uri.parse(f.anchor!!.uri)
            f.renamed = "renamed-${f.token}.jpg"
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, f.renamed) }, null, null))
            assertFalse(f.publisher.completePublication(proof)); assertFalse(f.publisher.removePendingPublication(proof))
            val conflict = inspect(f)
            assertEquals(MotionPhotoPublicationStatus.Conflict, conflict.recovery!!.status)
            assertFalse(conflict.canComplete || conflict.canRemovePending); assertTrue(conflict.canForget)
            assertTrue(f.publisher.forgetPublication(conflict)); assertArrayEquals(f.render, resolver.openInputStream(uri)!!.use { it.readBytes() })
            success = true
        } finally { if (success) cleanupSuccessful(f) }
    }

    @Test fun readyHashWithoutJpegFormatNeverCompletesButReviewedPendingCanBeRemoved() = runBlocking {
        val f = fixture(MotionPhotoPublicationKind.Frame); var success = false
        try {
            f.render = "not a JPEG, despite the exact saved hash".toByteArray(); seedPending(f, ready = true)
            val proof = inspect(f)
            assertFalse(proof.canComplete); assertTrue(proof.canRemovePending)
            assertFalse(f.publisher.completePublication(proof)); assertTrue(f.publisher.removePendingPublication(proof)); f.removed = true
            success = true
        } finally { if (success) cleanupSuccessful(f) }
    }

    @Test fun actualWriterCallbackKeepsResolutionAndRetirementFastBusyWithoutSelfDeadlock() = runBlocking {
        val f = fixture(MotionPhotoPublicationKind.Frame); var success = false
        val entered = CompletableDeferred<MotionPhotoPublicationReceipt>(); val release = CountDownLatch(1)
        val file = File(f.root, "render.jpg").apply { writeBytes(f.render) }
        val writer = async(Dispatchers.IO) {
            f.publisher.publish(f.id, f.input, hash(f.source.readBytes()), f.kind, f.selected, f.duration, file) { uri ->
                val receipt = f.journal.read(f.id)!!; f.anchor = receipt.destination; f.expectedBytes = f.render.copyOf()
                check(receipt.destination!!.uri == uri.toString()); entered.complete(receipt)
                check(release.await(15, TimeUnit.SECONDS)) { "Bounded callback release timed out" }
            }
        }
        try {
            val receipt = withTimeout(10_000) { entered.await() }
            val busy = withTimeout(2_000) { inspect(f) }
            assertTrue(busy.busy); assertFalse(busy.canComplete || busy.canRemovePending || busy.canForget)
            assertFalse(withTimeout(2_000) { f.publisher.retire(receipt) })
            assertFalse(withTimeout(2_000) { f.publisher.forgetPublication(busy.copy(canForget = true)) })
            assertFalse(withTimeout(2_000) { f.publisher.removePendingPublication(busy.copy(canRemovePending = true)) })
            assertFalse(withTimeout(2_000) { f.publisher.completePublication(busy.copy(canComplete = true)) })
            release.countDown(); writer.await()
            assertTrue(inspect(f).canForget); success = true
        } finally { release.countDown(); if (!writer.isCompleted) writer.await(); if (success) cleanupSuccessful(f) }
    }

    private fun args(d: MotionPhotoPublicationDestination) = arrayOf(d.ownerPackage, d.displayName, d.relativePath, d.mimeType,
        d.generationAdded.toString(), d.generationModified.toString(), d.sizeBytes.toString(), if (d.pending) "1" else "0", if (d.trashed) "1" else "0")
    private companion object {
        val Columns = arrayOf("owner_package_name", "_display_name", "relative_path", "mime_type", "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
        const val Where = "owner_package_name=? AND _display_name=? AND relative_path=? AND mime_type=? AND " +
            "generation_added=CAST(? AS INTEGER) AND generation_modified=CAST(? AS INTEGER) AND " +
            "COALESCE(_size,0)=CAST(? AS INTEGER) AND is_pending=CAST(? AS INTEGER) AND is_trashed=CAST(? AS INTEGER)"
    }
}
