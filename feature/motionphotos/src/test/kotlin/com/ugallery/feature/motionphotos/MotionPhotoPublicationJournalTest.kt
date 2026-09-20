package com.ugallery.feature.motionphotos

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionPhotoPublicationJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun folder() = File(temporary.root, UUID.randomUUID().toString())
    private fun receipt() = MotionPhotoPublicationReceipt(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "content://media/external_primary/images/media/80", 8L, 2L,
        "content://media/external_primary/images/media/80@8/2", "a".repeat(64),
        MotionPhotoPublicationKind.Frame, 1500000L, 3000000L, "c".repeat(64), 1234)
    private fun inserted(value: MotionPhotoPublicationReceipt) = value.copy(phase = MotionPhotoPublicationPhase.Inserted,
        destination = MotionPhotoPublicationDestination("content://media/external_primary/images/media/17", "com.ugallery.app",
            "UGallery-Motion-${value.token}.jpg", "Pictures/UGallery/Motion/", "image/jpeg", 10, 10, 0, true, false))
    private fun ready(value: MotionPhotoPublicationReceipt) = value.copy(phase = MotionPhotoPublicationPhase.Ready,
        destination = value.destination!!.copy(generationModified = 11, sizeBytes = value.renderSizeBytes))
    private fun published(value: MotionPhotoPublicationReceipt) = value.copy(phase = MotionPhotoPublicationPhase.Published,
        destination = value.destination!!.copy(generationModified = 12, pending = false))
    private fun rejected(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }; fail("Expected rejection")
    }
    @Test fun phasesRoundTripWithoutLosingSourceSelectedTimeDurationOrMetadata() {
        val directory = folder(); val journal = MotionPhotoPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); assertEquals(initial, journal.read(initial.publicationId))
        var previous = initial
        listOf(inserted(initial), ready(inserted(initial)), published(ready(inserted(initial)))).forEach {
            journal.advance(previous, it); assertEquals(it, MotionPhotoPublicationJournal(directory).read(it.publicationId)); previous = it
        }
    }
    @Test fun duplicateBeginIsIdempotentButDifferentTokenAndSourcesNeverOverwrite() {
        val directory = folder(); val journal = MotionPhotoPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val before = File(directory, "${initial.publicationId}.bin").readBytes(); journal.begin(initial)
        rejected { journal.begin(initial.copy(token = UUID.randomUUID().toString())) }
        rejected { journal.begin(initial.copy(sourceSha256 = "d".repeat(64))) }
        assertArrayEquals(before, File(directory, "${initial.publicationId}.bin").readBytes())
    }
    @Test fun compareAndSetRejectsStalePhaseSkippedPhaseAndChangedDestination() {
        val journal = MotionPhotoPublicationJournal(folder()); val initial = receipt(); val first = inserted(initial)
        journal.begin(initial); rejected { journal.advance(initial, ready(first)) }; journal.advance(initial, first)
        rejected { journal.advance(initial, first) }
        rejected { journal.advance(first, ready(first).copy(destination = ready(first).destination!!.copy(generationAdded = 9))) }
        rejected { journal.advance(first, ready(first).copy(destination = ready(first).destination!!.copy(uri = "content://media/external/images/media/18"))) }
        assertEquals(first, journal.read(initial.publicationId))
    }
    @Test fun absentReadDoesNotCreateDirectoryAndExistingAbsentRequiresSuccessfulSync() {
        val directory = folder(); var syncs = 0
        val journal = MotionPhotoPublicationJournal(directory, null) { syncs++; throw IOException("sync") }
        assertNull(journal.read(receipt().publicationId)); assertFalse(directory.exists()); assertEquals(0, syncs)
        check(directory.mkdir()); rejected { journal.read(receipt().publicationId) }; rejected { journal.hasEntry(receipt().publicationId) }
        assertEquals(2, syncs)
    }
    @Test fun preRenameFailureLeavesNoIntentAndNeverAdoptsAnOrphan() {
        val directory = folder(); val initial = receipt()
        val journal = MotionPhotoPublicationJournal(directory, { throw IOException("before rename") }, null)
        rejected { journal.begin(initial) }; assertNull(journal.read(initial.publicationId))
        val orphan = File(directory, "publication-orphan.tmp").apply { writeText("not a receipt") }
        assertNull(journal.read(initial.publicationId)); assertTrue(orphan.exists())
    }
    @Test fun directorySyncFailureAfterRenameRetainsKnownIntentAndForbidsDifferentRequest() {
        val directory = folder(); val initial = receipt(); var failAfterRename = true
        val journal = MotionPhotoPublicationJournal(directory, null) { path ->
            if (failAfterRename && File(path.toFile(), "${initial.publicationId}.bin").exists()) throw IOException("fsync")
        }
        rejected { journal.begin(initial) }; assertTrue(journal.hasEntry(initial.publicationId)); assertEquals(initial, journal.read(initial.publicationId))
        failAfterRename = false; journal.begin(initial)
        rejected { journal.begin(initial.copy(token = UUID.randomUUID().toString())) }
    }
    @Test fun corruptTruncatedAndOversizedEntriesAreKnownButNeverMissing() {
        val directory = folder(); val journal = MotionPhotoPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val file = File(directory, "${initial.publicationId}.bin"); val original = file.readBytes()
        listOf(original.copyOf(19), original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }, ByteArray(65537)).forEach {
            file.writeBytes(it); assertTrue(journal.hasEntry(initial.publicationId)); rejected { journal.read(initial.publicationId) }
        }
    }
    @Test fun validChecksumStillRejectsUnsupportedSchemaAndTrailingPayload() {
        val directory = folder(); val journal = MotionPhotoPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val file = File(directory, "${initial.publicationId}.bin"); val payload = file.readBytes().dropLast(32).toByteArray()
        listOf(payload.copyOf().also { it[7] = 2 }, payload + byteArrayOf(1)).forEach {
            file.writeBytes(it + MessageDigest.getInstance("SHA-256").digest(it)); rejected { journal.read(initial.publicationId) }
        }
    }
    @Test fun wrongSessionPathAndSymlinkAreRejectedWithoutTouchingTarget() {
        val directory = folder(); val journal = MotionPhotoPublicationJournal(directory); val initial = receipt(); journal.begin(initial)
        val other = receipt().publicationId; val original = File(directory, "${initial.publicationId}.bin")
        File(directory, "$other.bin").writeBytes(original.readBytes()); rejected { journal.read(other) }
        File(directory, "$other.bin").delete(); Files.createSymbolicLink(File(directory, "$other.bin").toPath(), original.toPath())
        assertTrue(journal.hasEntry(other)); rejected { journal.read(other) }; assertEquals(initial, journal.read(initial.publicationId))
    }
    @Test fun malformedSourceGenerationsIdentityAndDestinationAreRejected() {
        val value = receipt()
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(publicationId = "../escape")) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(sourceSha256 = "short")) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(generationAdded = -1)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(generationModified = null)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(sourceIdentity = "other@8/2")) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(inserted(value).copy(destination = inserted(value).destination!!.copy(displayName = "other.jpg"))) }
    }
    @Test fun retirementIsExactAndIncompleteIsRetained() {
        val journal = MotionPhotoPublicationJournal(folder()); val initial = receipt(); val first = inserted(initial); val ready = ready(first); val published = published(ready)
        journal.begin(initial); rejected { journal.retire(initial) }; journal.advance(initial, first); journal.advance(first, ready); journal.advance(ready, published)
        assertFalse(journal.retire(published.copy(token = UUID.randomUUID().toString()))); assertEquals(published, journal.read(initial.publicationId))
        assertTrue(journal.retire(published)); assertNull(journal.read(initial.publicationId))
    }
    @Test fun unlinkSyncFailureRequiresSuccessfulAbsentSyncBeforeMissing() {
        val directory = folder(); var failSync = false
        val journal = MotionPhotoPublicationJournal(directory, null) { if (failSync) throw IOException("directory sync") }
        val initial = receipt(); val first = inserted(initial); val ready = ready(first)
        journal.begin(initial); journal.advance(initial, first); journal.advance(first, ready)
        failSync = true; rejected { journal.retire(ready) }; rejected { journal.read(initial.publicationId) }
        failSync = false; assertNull(journal.read(initial.publicationId))
    }
    @Test fun readyCanRecordPendingZeroSizeButPublishedRequiresVerifiedRenderSize() {
        val journal = MotionPhotoPublicationJournal(folder()); val initial = receipt(); val first = inserted(initial)
        val pending = ready(first).copy(destination = ready(first).destination!!.copy(sizeBytes = 0))
        journal.begin(initial); journal.advance(initial, first); journal.advance(first, pending)
        assertEquals(pending, journal.read(initial.publicationId))
        assertEquals(initial.renderSizeBytes, journal.read(initial.publicationId)!!.renderSizeBytes)
        rejected { journal.advance(pending, published(pending)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(pending.copy(destination = pending.destination!!.copy(sizeBytes = 1))) }
        val completed = published(pending).copy(destination = published(pending).destination!!.copy(sizeBytes = initial.renderSizeBytes))
        journal.advance(pending, completed); assertEquals(completed, journal.read(initial.publicationId))
    }

    @Test fun frameTimeAndClipDurationBindTheRequestWithoutArbitraryJpegByteLimit() {
        val value = receipt()
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(selectedTimeUs = null)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(selectedTimeUs = -1)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(selectedTimeUs = value.durationUs)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(value.copy(durationUs = 60_000_001)) }
        assertEquals(90_000_000L, MotionPhotoPublicationReceipt.validatedCopy(value.copy(renderSizeBytes = 90_000_000L)).renderSizeBytes)
        val clip = value.copy(kind = MotionPhotoPublicationKind.Clip, selectedTimeUs = null, renderSizeBytes = 512L * 1024 * 1024)
        val journal = MotionPhotoPublicationJournal(folder()); journal.begin(clip); assertEquals(clip, journal.read(clip.publicationId))
        rejected { MotionPhotoPublicationReceipt.validatedCopy(clip.copy(selectedTimeUs = 0)) }
        rejected { MotionPhotoPublicationReceipt.validatedCopy(clip.copy(renderSizeBytes = clip.renderSizeBytes + 1)) }
        assertFalse(value.sameRequest(value.copy(selectedTimeUs = 0)))
        assertFalse(value.sameRequest(clip))
    }
    @Test fun clipCannotBindImageCollectionOrJpegDestination() {
        val frame = receipt(); val clip = frame.copy(kind = MotionPhotoPublicationKind.Clip, selectedTimeUs = null)
        rejected { MotionPhotoPublicationReceipt.validatedCopy(inserted(clip)) }
        val correct = inserted(clip).copy(destination = inserted(clip).destination!!.copy(
            uri = "content://media/external_primary/video/media/17", displayName = "UGallery-Motion-${clip.token}.mp4",
            relativePath = "Movies/UGallery/Motion/", mimeType = "video/mp4"))
        assertEquals(correct, MotionPhotoPublicationReceipt.validatedCopy(correct))
    }
    @Test fun cancellationBeforeAtomicPublicationRetainsPreviousReceipt() {
        val directory = folder(); val initial = receipt(); val normal = MotionPhotoPublicationJournal(directory); normal.begin(initial)
        val interrupted = MotionPhotoPublicationJournal(directory, { throw java.util.concurrent.CancellationException("atomic gap") }, null)
        rejected { interrupted.advance(initial, inserted(initial)) }
        assertEquals(initial, normal.read(initial.publicationId))
    }

}
