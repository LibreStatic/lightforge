package com.librestatic.lightforge.feature.collage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreationCollagePublicationJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun folder() = File(temporary.root, UUID.randomUUID().toString())
    private fun receipt() = CreationCollagePublicationReceipt(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        listOf("source-a@4/2", "source-b@8/3"), listOf("a".repeat(64), "b".repeat(64)),
        CreationCollageLayout.initial(2).move(0, 1).crop(0, CreationCollageCrop(2f, -.5f, .5f)), "c".repeat(64), 1234)
    private fun inserted(value: CreationCollagePublicationReceipt) = value.copy(phase = CreationCollagePublicationPhase.Inserted,
        destination = CreationCollagePublicationDestination("content://media/external_primary/images/media/17", "com.librestatic.lightforge",
            "Lightforge-collage-${value.token}.png", "Pictures/Lightforge/Collage/", "image/png", 10, 10, 0, true, false))
    private fun ready(value: CreationCollagePublicationReceipt) = value.copy(phase = CreationCollagePublicationPhase.Ready,
        destination = value.destination!!.copy(generationModified = 11, sizeBytes = value.renderSizeBytes))
    private fun published(value: CreationCollagePublicationReceipt) = value.copy(phase = CreationCollagePublicationPhase.Published,
        destination = value.destination!!.copy(generationModified = 12, pending = false))
    private fun rejected(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }; fail("Expected rejection")
    }
    @Test fun phasesRoundTripWithoutLosingOrderCropOrMetadata() {
        val directory = folder(); val journal = CreationCollagePublicationJournal(directory); val initial = receipt()
        journal.begin(initial); assertEquals(initial, journal.read(initial.sessionId))
        var previous = initial
        listOf(inserted(initial), ready(inserted(initial)), published(ready(inserted(initial)))).forEach {
            journal.advance(previous, it); assertEquals(it, CreationCollagePublicationJournal(directory).read(it.sessionId)); previous = it
        }
    }
    @Test fun duplicateBeginIsIdempotentButDifferentTokenAndSourcesNeverOverwrite() {
        val directory = folder(); val journal = CreationCollagePublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val before = File(directory, "${initial.sessionId}.bin").readBytes(); journal.begin(initial)
        rejected { journal.begin(initial.copy(token = UUID.randomUUID().toString())) }
        rejected { journal.begin(initial.copy(sourceIdentities = listOf("other", "source-b@8/3"))) }
        assertArrayEquals(before, File(directory, "${initial.sessionId}.bin").readBytes())
    }
    @Test fun compareAndSetRejectsStalePhaseSkippedPhaseAndChangedDestination() {
        val journal = CreationCollagePublicationJournal(folder()); val initial = receipt(); val first = inserted(initial)
        journal.begin(initial); rejected { journal.advance(initial, ready(first)) }; journal.advance(initial, first)
        rejected { journal.advance(initial, first) }
        rejected { journal.advance(first, ready(first).copy(destination = ready(first).destination!!.copy(generationAdded = 9))) }
        rejected { journal.advance(first, ready(first).copy(destination = ready(first).destination!!.copy(uri = "content://media/external/images/media/18"))) }
        assertEquals(first, journal.read(initial.sessionId))
    }
    @Test fun absentReadDoesNotCreateDirectoryAndExistingAbsentRequiresSuccessfulSync() {
        val directory = folder(); var syncs = 0
        val journal = CreationCollagePublicationJournal(directory, null) { syncs++; throw IOException("sync") }
        assertNull(journal.read(receipt().sessionId)); assertFalse(directory.exists()); assertEquals(0, syncs)
        check(directory.mkdir()); rejected { journal.read(receipt().sessionId) }; rejected { journal.hasEntry(receipt().sessionId) }
        assertEquals(2, syncs)
    }
    @Test fun preRenameFailureLeavesNoIntentAndNeverAdoptsAnOrphan() {
        val directory = folder(); val initial = receipt()
        val journal = CreationCollagePublicationJournal(directory, { throw IOException("before rename") }, null)
        rejected { journal.begin(initial) }; assertNull(journal.read(initial.sessionId))
        val orphan = File(directory, "publication-orphan.tmp").apply { writeText("not a receipt") }
        assertNull(journal.read(initial.sessionId)); assertTrue(orphan.exists())
    }
    @Test fun directorySyncFailureAfterRenameRetainsKnownIntentAndForbidsDifferentRequest() {
        val directory = folder(); val initial = receipt(); var failAfterRename = true
        val journal = CreationCollagePublicationJournal(directory, null) { path ->
            if (failAfterRename && File(path.toFile(), "${initial.sessionId}.bin").exists()) throw IOException("fsync")
        }
        rejected { journal.begin(initial) }; assertTrue(journal.hasEntry(initial.sessionId)); assertEquals(initial, journal.read(initial.sessionId))
        failAfterRename = false; journal.begin(initial)
        rejected { journal.begin(initial.copy(token = UUID.randomUUID().toString())) }
    }
    @Test fun corruptTruncatedAndOversizedEntriesAreKnownButNeverMissing() {
        val directory = folder(); val journal = CreationCollagePublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val file = File(directory, "${initial.sessionId}.bin"); val original = file.readBytes()
        listOf(original.copyOf(19), original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }, ByteArray(65537)).forEach {
            file.writeBytes(it); assertTrue(journal.hasEntry(initial.sessionId)); rejected { journal.read(initial.sessionId) }
        }
    }
    @Test fun validChecksumStillRejectsUnsupportedSchemaAndTrailingPayload() {
        val directory = folder(); val journal = CreationCollagePublicationJournal(directory); val initial = receipt()
        journal.begin(initial); val file = File(directory, "${initial.sessionId}.bin"); val payload = file.readBytes().dropLast(32).toByteArray()
        listOf(payload.copyOf().also { it[7] = 2 }, payload + byteArrayOf(1)).forEach {
            file.writeBytes(it + MessageDigest.getInstance("SHA-256").digest(it)); rejected { journal.read(initial.sessionId) }
        }
    }
    @Test fun wrongSessionPathAndSymlinkAreRejectedWithoutTouchingTarget() {
        val directory = folder(); val journal = CreationCollagePublicationJournal(directory); val initial = receipt(); journal.begin(initial)
        val other = receipt().sessionId; val original = File(directory, "${initial.sessionId}.bin")
        File(directory, "$other.bin").writeBytes(original.readBytes()); rejected { journal.read(other) }
        File(directory, "$other.bin").delete(); Files.createSymbolicLink(File(directory, "$other.bin").toPath(), original.toPath())
        assertTrue(journal.hasEntry(other)); rejected { journal.read(other) }; assertEquals(initial, journal.read(initial.sessionId))
    }
    @Test fun receiptsDetachCollectionsAndRejectMalformedIdentityOrDestination() {
        val value = receipt(); val identities = value.sourceIdentities.toMutableList(); val order = value.layout.order.toMutableList()
        val detached = CreationCollagePublicationReceipt.validatedCopy(value.copy(sourceIdentities = identities, layout = value.layout.copy(order = order)))
        identities[0] = "changed"; order.reverse(); assertEquals(value, detached)
        rejected { (detached.sourceIdentities as MutableList<String>)[0] = "mutation" }
        rejected { CreationCollagePublicationReceipt.validatedCopy(value.copy(sessionId = "../escape")) }
        rejected { CreationCollagePublicationReceipt.validatedCopy(value.copy(sourceSha256 = listOf("short", "b".repeat(64)))) }
        rejected { CreationCollagePublicationReceipt.validatedCopy(inserted(value).copy(destination = inserted(value).destination!!.copy(displayName = "other.png"))) }
    }
    @Test fun retirementIsExactAndIncompleteIsRetained() {
        val journal = CreationCollagePublicationJournal(folder()); val initial = receipt(); val first = inserted(initial); val ready = ready(first); val published = published(ready)
        journal.begin(initial); rejected { journal.retire(initial) }; journal.advance(initial, first); journal.advance(first, ready); journal.advance(ready, published)
        assertFalse(journal.retire(published.copy(token = UUID.randomUUID().toString()))); assertEquals(published, journal.read(initial.sessionId))
        assertTrue(journal.retire(published)); assertNull(journal.read(initial.sessionId))
    }
    @Test fun unlinkSyncFailureRequiresSuccessfulAbsentSyncBeforeMissing() {
        val directory = folder(); var failSync = false
        val journal = CreationCollagePublicationJournal(directory, null) { if (failSync) throw IOException("directory sync") }
        val initial = receipt(); val first = inserted(initial); val ready = ready(first)
        journal.begin(initial); journal.advance(initial, first); journal.advance(first, ready)
        failSync = true; rejected { journal.retire(ready) }; rejected { journal.read(initial.sessionId) }
        failSync = false; assertNull(journal.read(initial.sessionId))
    }
    @Test fun readyCanRecordPendingZeroSizeButPublishedRequiresVerifiedRenderSize() {
        val journal = CreationCollagePublicationJournal(folder()); val initial = receipt(); val first = inserted(initial)
        val pending = ready(first).copy(destination = ready(first).destination!!.copy(sizeBytes = 0))
        journal.begin(initial); journal.advance(initial, first); journal.advance(first, pending)
        assertEquals(pending, journal.read(initial.sessionId))
        assertEquals(initial.renderSizeBytes, journal.read(initial.sessionId)!!.renderSizeBytes)
        rejected { journal.advance(pending, published(pending)) }
        rejected { CreationCollagePublicationReceipt.validatedCopy(pending.copy(destination = pending.destination!!.copy(sizeBytes = 1))) }
        val completed = published(pending).copy(destination = published(pending).destination!!.copy(sizeBytes = initial.renderSizeBytes))
        journal.advance(pending, completed); assertEquals(completed, journal.read(initial.sessionId))
    }

}
