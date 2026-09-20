package com.ugallery.feature.collage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreationGifPublicationJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun folder() = File(temporary.root, UUID.randomUUID().toString())
    private fun receipt() = CreationGifPublicationReceipt(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        listOf("source-a@4/2", "source-b@8/3"), listOf("a".repeat(64), "b".repeat(64)),
        listOf(1, 0), 3, "c".repeat(64), 1234)
    private fun inserted(value: CreationGifPublicationReceipt) = value.copy(phase = CreationGifPublicationPhase.Inserted,
        destination = CreationGifPublicationDestination("content://media/external_primary/images/media/17", "com.ugallery.app",
            "UGallery-GIF-${value.token}.gif", "Pictures/UGallery/GIF/", "image/gif", 10, 10, 0, true, false))
    private fun ready(value: CreationGifPublicationReceipt) = value.copy(phase = CreationGifPublicationPhase.Ready,
        destination = value.destination!!.copy(generationModified = 11, sizeBytes = value.renderSizeBytes))
    private fun published(value: CreationGifPublicationReceipt) = value.copy(phase = CreationGifPublicationPhase.Published,
        destination = value.destination!!.copy(generationModified = 12, pending = false))
    private fun rejected(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }; fail("Expected rejection")
    }
    @Test fun phasesRoundTripWithoutLosingOrderDurationOrMetadata() {
        val directory = folder(); val journal = CreationGifPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); assertEquals(initial, journal.read(initial.sessionId))
        var previous = initial
        listOf(inserted(initial), ready(inserted(initial)), published(ready(inserted(initial)))).forEach {
            journal.advance(previous, it); assertEquals(it, CreationGifPublicationJournal(directory).read(it.sessionId)); previous = it
        }
    }
    @Test fun duplicateBeginIsIdempotentButDifferentTokenAndSourcesNeverOverwrite() {
        val directory = folder(); val journal = CreationGifPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val before = File(directory, "${initial.sessionId}.bin").readBytes(); journal.begin(initial)
        rejected { journal.begin(initial.copy(token = UUID.randomUUID().toString())) }
        rejected { journal.begin(initial.copy(sourceIdentities = listOf("other", "source-b@8/3"))) }
        assertArrayEquals(before, File(directory, "${initial.sessionId}.bin").readBytes())
    }
    @Test fun compareAndSetRejectsStalePhaseSkippedPhaseAndChangedDestination() {
        val journal = CreationGifPublicationJournal(folder()); val initial = receipt(); val first = inserted(initial)
        journal.begin(initial); rejected { journal.advance(initial, ready(first)) }; journal.advance(initial, first)
        rejected { journal.advance(initial, first) }
        rejected { journal.advance(first, ready(first).copy(destination = ready(first).destination!!.copy(generationAdded = 9))) }
        rejected { journal.advance(first, ready(first).copy(destination = ready(first).destination!!.copy(uri = "content://media/external/images/media/18"))) }
        assertEquals(first, journal.read(initial.sessionId))
    }
    @Test fun absentReadDoesNotCreateDirectoryAndExistingAbsentRequiresSuccessfulSync() {
        val directory = folder(); var syncs = 0
        val journal = CreationGifPublicationJournal(directory, null) { syncs++; throw IOException("sync") }
        assertNull(journal.read(receipt().sessionId)); assertFalse(directory.exists()); assertEquals(0, syncs)
        check(directory.mkdir()); rejected { journal.read(receipt().sessionId) }; rejected { journal.hasEntry(receipt().sessionId) }
        assertEquals(2, syncs)
    }
    @Test fun preRenameFailureLeavesNoIntentAndNeverAdoptsAnOrphan() {
        val directory = folder(); val initial = receipt()
        val journal = CreationGifPublicationJournal(directory, { throw IOException("before rename") }, null)
        rejected { journal.begin(initial) }; assertNull(journal.read(initial.sessionId))
        val orphan = File(directory, "publication-orphan.tmp").apply { writeText("not a receipt") }
        assertNull(journal.read(initial.sessionId)); assertTrue(orphan.exists())
    }
    @Test fun directorySyncFailureAfterRenameRetainsKnownIntentAndForbidsDifferentRequest() {
        val directory = folder(); val initial = receipt(); var failAfterRename = true
        val journal = CreationGifPublicationJournal(directory, null) { path ->
            if (failAfterRename && File(path.toFile(), "${initial.sessionId}.bin").exists()) throw IOException("fsync")
        }
        rejected { journal.begin(initial) }; assertTrue(journal.hasEntry(initial.sessionId)); assertEquals(initial, journal.read(initial.sessionId))
        failAfterRename = false; journal.begin(initial)
        rejected { journal.begin(initial.copy(token = UUID.randomUUID().toString())) }
    }
    @Test fun corruptTruncatedAndOversizedEntriesAreKnownButNeverMissing() {
        val directory = folder(); val journal = CreationGifPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val file = File(directory, "${initial.sessionId}.bin"); val original = file.readBytes()
        listOf(original.copyOf(19), original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }, ByteArray(65537)).forEach {
            file.writeBytes(it); assertTrue(journal.hasEntry(initial.sessionId)); rejected { journal.read(initial.sessionId) }
        }
    }
    @Test fun validChecksumStillRejectsUnsupportedSchemaAndTrailingPayload() {
        val directory = folder(); val journal = CreationGifPublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val file = File(directory, "${initial.sessionId}.bin"); val payload = file.readBytes().dropLast(32).toByteArray()
        listOf(payload.copyOf().also { it[7] = 2 }, payload + byteArrayOf(1)).forEach {
            file.writeBytes(it + MessageDigest.getInstance("SHA-256").digest(it)); rejected { journal.read(initial.sessionId) }
        }
    }
    @Test fun wrongSessionPathAndSymlinkAreRejectedWithoutTouchingTarget() {
        val directory = folder(); val journal = CreationGifPublicationJournal(directory); val initial = receipt(); journal.begin(initial)
        val other = receipt().sessionId; val original = File(directory, "${initial.sessionId}.bin")
        File(directory, "$other.bin").writeBytes(original.readBytes()); rejected { journal.read(other) }
        File(directory, "$other.bin").delete(); Files.createSymbolicLink(File(directory, "$other.bin").toPath(), original.toPath())
        assertTrue(journal.hasEntry(other)); rejected { journal.read(other) }; assertEquals(initial, journal.read(initial.sessionId))
    }
    @Test fun receiptsDetachCollectionsAndRejectMalformedIdentityOrDestination() {
        val value = receipt(); val identities = value.sourceIdentities.toMutableList(); val order = value.order.toMutableList()
        val detached = CreationGifPublicationReceipt.validatedCopy(value.copy(sourceIdentities = identities, order = order))
        identities[0] = "changed"; order.reverse(); assertEquals(value, detached)
        rejected { (detached.sourceIdentities as MutableList<String>)[0] = "mutation" }
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(sessionId = "../escape")) }
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(sourceSha256 = listOf("short", "b".repeat(64)))) }
        rejected { CreationGifPublicationReceipt.validatedCopy(inserted(value).copy(destination = inserted(value).destination!!.copy(displayName = "other.gif"))) }
    }
    @Test fun retirementIsExactAndIncompleteIsRetained() {
        val journal = CreationGifPublicationJournal(folder()); val initial = receipt(); val first = inserted(initial); val ready = ready(first); val published = published(ready)
        journal.begin(initial); rejected { journal.retire(initial) }; journal.advance(initial, first); journal.advance(first, ready); journal.advance(ready, published)
        assertFalse(journal.retire(published.copy(token = UUID.randomUUID().toString()))); assertEquals(published, journal.read(initial.sessionId))
        assertTrue(journal.retire(published)); assertNull(journal.read(initial.sessionId))
    }
    @Test fun unlinkSyncFailureRequiresSuccessfulAbsentSyncBeforeMissing() {
        val directory = folder(); var failSync = false
        val journal = CreationGifPublicationJournal(directory, null) { if (failSync) throw IOException("directory sync") }
        val initial = receipt(); val first = inserted(initial); val ready = ready(first)
        journal.begin(initial); journal.advance(initial, first); journal.advance(first, ready)
        failSync = true; rejected { journal.retire(ready) }; rejected { journal.read(initial.sessionId) }
        failSync = false; assertNull(journal.read(initial.sessionId))
    }
    @Test fun readyCanRecordPendingZeroSizeButPublishedRequiresVerifiedRenderSize() {
        val journal = CreationGifPublicationJournal(folder()); val initial = receipt(); val first = inserted(initial)
        val pending = ready(first).copy(destination = ready(first).destination!!.copy(sizeBytes = 0))
        journal.begin(initial); journal.advance(initial, first); journal.advance(first, pending)
        assertEquals(pending, journal.read(initial.sessionId))
        assertEquals(initial.renderSizeBytes, journal.read(initial.sessionId)!!.renderSizeBytes)
        rejected { journal.advance(pending, published(pending)) }
        rejected { CreationGifPublicationReceipt.validatedCopy(pending.copy(destination = pending.destination!!.copy(sizeBytes = 1))) }
        val completed = published(pending).copy(destination = published(pending).destination!!.copy(sizeBytes = initial.renderSizeBytes))
        journal.advance(pending, completed); assertEquals(completed, journal.read(initial.sessionId))
    }

    @Test fun orderedSubsetHashesBindOnlyUsedFramesButRetainAllDraftIdentities() {
        val value = receipt().copy(sourceIdentities = listOf("a@8/1", "removed@5/3", "c@7/2"), order = listOf(2, 0))
        val journal = CreationGifPublicationJournal(folder()); journal.begin(value)
        assertEquals(value, journal.read(value.sessionId))
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(order = listOf(2, 2))) }
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(order = listOf(3, 0))) }
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(sourceSha256 = listOf("a".repeat(64)))) }
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(secondsPerFrame = 0)) }
        rejected { CreationGifPublicationReceipt.validatedCopy(value.copy(secondsPerFrame = 6)) }
        assertFalse(value.sameRequest(value.copy(order = listOf(0, 2))))
        assertFalse(value.sameRequest(value.copy(secondsPerFrame = 2)))
    }
    @Test fun wholeReceiptHasJoint64KiBLimitBeforeAnyIntentIsWritten() {
        val directory = folder(); val journal = CreationGifPublicationJournal(directory)
        val value = receipt().copy(sourceIdentities = List(60) { "$it-" + "x".repeat(2000) }, order = listOf(59, 0))
        rejected { journal.begin(value) }; assertFalse(directory.exists())
    }

}
