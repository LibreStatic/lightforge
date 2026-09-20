package com.ugallery.app

import com.ugallery.core.mediastore.VerifiedMoveProof
import com.ugallery.core.mediastore.MoveCopyDraft
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedMoveJournalTest {
    private fun draft(): MoveCopyDraft = proof().let { p ->
        MoveCopyDraft(p.id, p.targetVolume, p.targetId, p.targetKind, p.sourceUri, p.treeUri,
            "photo.png", "image/png", 123L, p.bytes, p.sha256, p.generationAdded, p.generationModified,
            grantReadAcquired = p.grantReadAcquired)
    }

    @Test fun copyDraftReopensAndPromotesAtomicallyWithoutChangingProofIdentity() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); val initial = draft()
        assertNull(journal.readCopyDraft()); assertFalse(dir.exists())
        assertEquals(initial, journal.beginCopy(initial))
        assertEquals(initial, VerifiedMoveJournal(dir).readCopyDraft())
        assertEquals(2, java.nio.ByteBuffer.wrap(file(dir).readBytes()).getInt(4))
        rejected { journal.readActive() }
        rejected { journal.reviewCorrupt() } // A readable draft is NOT corrupt tracking.
        rejected { journal.promoteCopy(initial) }
        val destination = initial.treeUri + "/document/own%3Adir%2Fphoto.png"
        val attached = journal.attachCopyDestination(initial, destination)
        assertEquals(attached, VerifiedMoveJournal(dir).readCopyDraft())
        rejected { journal.attachCopyDestination(initial, destination) }
        rejected { journal.attachCopyDestination(attached, destination) }
        rejected { journal.promoteCopy(initial) }
        val ready = journal.promoteCopy(attached)
        assertEquals(attached.toProof(), ready.proof)
        assertEquals(VerifiedMovePhase.Ready, ready.phase)
        assertEquals(ready, VerifiedMoveJournal(dir).readActive())
        assertNull(journal.readCopyDraft())
        assertFalse(journal.forgetCopy(attached))
        assertEquals(1, java.nio.ByteBuffer.wrap(file(dir).readBytes()).getInt(4))
    }

    @Test fun copySlotExcludesOtherDraftsAndProofsAndTrackingForgetIsExact() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); val initial = draft()
        journal.beginCopy(initial)
        val before = file(dir).readBytes()
        assertEquals(initial, VerifiedMoveJournal(dir).beginCopy(initial))
        rejected { journal.beginCopy(draft()) }
        rejected { journal.begin(proof()) }
        assertFalse(journal.forgetCopy(initial.copy(name = "other.png")))
        assertArrayEquals(before, file(dir).readBytes())
        assertTrue(journal.forgetCopy(initial))
        assertNull(journal.readActive())
        assertNull(journal.readCopyDraft())
        val entry = journal.begin(proof())
        rejected { journal.beginCopy(initial) }
        assertEquals(entry, journal.readActive())
        assertFalse(journal.forgetCopy(initial))
    }

    @Test fun copyFailuresRetainRecoverableExactPhaseBeforeAndAfterAtomicRename() {
        val dir = directory(); val initial = draft(); var failBefore = true; var failSync = false
        val journal = VerifiedMoveJournal(dir, { if (failBefore) throw IOException("publish") }) { path ->
            if (failSync && path == dir.toPath()) throw IOException("fsync")
            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
        rejected { journal.beginCopy(initial) }
        assertNull(journal.readCopyDraft())
        failBefore = false; journal.beginCopy(initial)
        failBefore = true
        val destination = initial.treeUri + "/document/own%3Adir%2Fphoto.png"
        rejected { journal.attachCopyDestination(initial, destination) }
        assertEquals(initial, journal.readCopyDraft())
        failBefore = false; failSync = true
        rejected { journal.attachCopyDestination(initial, destination) }
        val attached = requireNotNull(journal.readCopyDraft())
        assertEquals(destination, attached.destinationUri)
        failSync = false; failBefore = true
        rejected { journal.promoteCopy(attached) }
        assertEquals(attached, journal.readCopyDraft())
        failBefore = false; failSync = true
        rejected { journal.promoteCopy(attached) }
        assertEquals(attached.toProof(), requireNotNull(journal.readActive()).proof)
        assertNull(journal.readCopyDraft())
        failSync = false
        assertEquals(attached.toProof(), journal.begin(attached.toProof()).proof)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun v1GoldenProofStillReadsAndAdvancesWithoutDraftConversion() {
        val dir = directory(); check(dir.mkdir()); val p = proof()
        // Independent retained wire-v1 writer, rather than roundtripping the current encoder.
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            fun text(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
            out.writeInt(0x55474d56); out.writeInt(1)
            text(p.id); text(p.targetVolume); out.writeLong(p.targetId); text(p.targetKind)
            text(p.sourceUri); text(p.destinationUri); text(p.treeUri); out.writeLong(p.bytes); text(p.sha256)
            out.writeLong(p.generationAdded); out.writeLong(p.generationModified); out.writeByte(if (p.grantReadAcquired) 1 else 0)
            out.writeInt(0); out.writeLong(0L)
        }
        file(dir).writeBytes(checksum(bytes.toByteArray()))
        val journal = VerifiedMoveJournal(dir)
        assertNull(journal.readCopyDraft())
        val entry = requireNotNull(journal.readActive())
        assertEquals(VerifiedMoveEntry(p), entry)
        val next = journal.advance(entry, VerifiedMovePhase.AwaitingSystem, 1L)
        assertEquals(next, VerifiedMoveJournal(dir).readActive())
    }

    @Test fun draftCorruptionRejectsBothReadersWithoutOverwritingOrAdopting() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); val initial = draft()
        journal.beginCopy(initial)
        val original = file(dir).readBytes(); val payload = original.copyOf(original.size - 32)
        listOf(original.copyOf(20), original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() },
            checksum(payload.copyOf().also { it[7] = 3 }), checksum(payload + byteArrayOf(0)),
            ByteArray(VerifiedMoveJournal.MaximumBytes + 1)).forEach { corrupt ->
            file(dir).writeBytes(corrupt)
            rejected { journal.readCopyDraft() }; rejected { journal.readActive() }
            rejected { journal.beginCopy(initial) }; rejected { journal.forgetCopy(initial) }
            assertArrayEquals(corrupt, file(dir).readBytes())
        }
        file(dir).writeBytes(original)
        assertEquals(initial, journal.readCopyDraft())
    }

    @Test fun copyDraftBoundsAndOptionalValuesAreStrictBeforeJournalWrites() {
        val d = draft(); assertEquals(d, d.validated())
        assertNull(d.copy(lastModifiedMillis = null).validated().lastModifiedMillis)
        assertEquals(0L, d.copy(bytes = 0L).validated().bytes)
        listOf(d.copy(name = ""), d.copy(name = "../source"), d.copy(name = "a\\b"),
            d.copy(name = " padded "), d.copy(name = "x".repeat(256)), d.copy(name = "a\nb"),
            d.copy(mime = "image/*"), d.copy(mime = "image/png;extra"), d.copy(mime = "x".repeat(256)),
            d.copy(lastModifiedMillis = -1L), d.copy(bytes = -1L), d.copy(targetId = 99L),
            d.copy(id = "../escape"), d.copy(sha256 = "a"), d.copy(generationAdded = d.generationModified + 1),
            d.copy(destinationUri = d.sourceUri), d.copy(treeUri = "https://fixture.invalid/tree/x")).forEach { bad ->
            rejected { bad.validated() }
        }
        rejected { d.toProof() }
        val dir = directory(); val journal = VerifiedMoveJournal(dir)
        rejected { journal.beginCopy(d.copy(name = "bad/name")) }
        rejected { journal.beginCopy(d.copy(destinationUri = d.treeUri + "/document/a")) }
        assertFalse(dir.exists())
        val nullable = d.copy(lastModifiedMillis = null, bytes = 0L, grantReadAcquired = false)
        journal.beginCopy(nullable)
        assertEquals(nullable, VerifiedMoveJournal(dir).readCopyDraft())
    }

    @Test fun pickerSnapshotSerializesModeAndExactSourceIndependentlyOfViewer() {
        for (move in listOf(false, true)) {
            val expected = PendingTreeOperation(
                com.ugallery.core.mediastore.MediaActionTarget(com.ugallery.core.model.MediaKey("external_primary", 17L), com.ugallery.core.model.MediaKind.Image),
                "original.png", "image/png", 1234L, move,
            )
            val output = java.io.ByteArrayOutputStream()
            java.io.ObjectOutputStream(output).use { it.writeObject(expected) }
            val restored = java.io.ObjectInputStream(output.toByteArray().inputStream()).use { it.readObject() }
            assertEquals(expected, restored)
        }
    }

    @Test fun reviewedCorruptionIsPreservedAndNewMoveCanBegin() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); journal.begin(proof())
        val damaged = byteArrayOf(7, 3, 1, 9); file(dir).writeBytes(damaged)
        val reviewed = journal.reviewCorrupt()
        val preserved = journal.quarantineCorrupt(reviewed)
        assertArrayEquals(damaged, preserved.readBytes())
        assertFalse(file(dir).exists()); assertNull(VerifiedMoveJournal(dir).readActive())
        val next = proof(); assertEquals(next, journal.begin(next).proof)
        assertArrayEquals(damaged, preserved.readBytes())
    }

    @Test fun staleCorruptionReviewNeverMovesChangedOrValidRecord() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); val entry = journal.begin(proof())
        val validBytes = file(dir).readBytes()
        file(dir).writeBytes(byteArrayOf(1)); val reviewed = journal.reviewCorrupt()
        file(dir).writeBytes(byteArrayOf(2)); rejected { journal.quarantineCorrupt(reviewed) }
        assertArrayEquals(byteArrayOf(2), file(dir).readBytes())
        file(dir).writeBytes(validBytes); rejected { journal.quarantineCorrupt(reviewed) }
        assertEquals(entry, journal.readActive()); rejected { journal.reviewCorrupt() }
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("unreadable-") })
    }

    @Test fun emptyAndOversizedCorruptionPreserveBytesWithoutDecoding() {
        for (bytes in listOf(byteArrayOf(), ByteArray(VerifiedMoveJournal.MaximumBytes + 1) { 5 })) {
            val dir = directory(); val journal = VerifiedMoveJournal(dir); journal.begin(proof()); file(dir).writeBytes(bytes)
            val preserved = journal.quarantineCorrupt(journal.reviewCorrupt())
            assertArrayEquals(bytes, preserved.readBytes()); assertNull(journal.readActive())
        }
    }

    @Test fun cancelledCorruptionReviewLeavesActiveBytesInPlace() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); journal.begin(proof())
        val bytes = ByteArray(100) { 8 }; file(dir).writeBytes(bytes)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            journal.reviewCorrupt { throw java.util.concurrent.CancellationException() }
        }
        assertArrayEquals(bytes, file(dir).readBytes()); assertTrue(dir.listFiles()!!.none { it.name.startsWith("unreadable-") })
    }

    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = File(temporary.newFolder(), "move-journal")
    private fun proof() = VerifiedMoveProof(
        UUID.randomUUID().toString(), "external_primary", 17L, "Image",
        "content://media/external_primary/images/media/17",
        "content://fixture.documents/tree/own%3Adir/document/own%3Adir%2Fphoto.png",
        "content://fixture.documents/tree/own%3Adir", 123L, "ab".repeat(32), 7L, 9L, true,
    )
    private fun rejected(block: () -> Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }
    private fun file(directory: File) = File(directory, "active.bin")
    private fun checksum(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)

    @Test fun roundtripReopenAndRetryRetainExactProofAndGrantOwnership() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); val p = proof()
        val ready = journal.begin(p)
        assertEquals(ready, VerifiedMoveJournal(dir).readActive())
        val awaiting = journal.advance(ready, VerifiedMovePhase.AwaitingSystem, 1L)
        val cancelled = journal.advance(awaiting, VerifiedMovePhase.Cancelled, 1L)
        assertEquals(cancelled, VerifiedMoveJournal(dir).readActive())
        val retry = VerifiedMoveJournal(dir).advance(cancelled, VerifiedMovePhase.AwaitingSystem, 2L)
        val failed = journal.advance(retry, VerifiedMovePhase.RequestFailed, 2L)
        val resumed = journal.advance(failed, VerifiedMovePhase.AwaitingSystem, 3L)
        val done = journal.advance(resumed, VerifiedMovePhase.Completed, 3L)
        assertEquals(p, done.proof)
        assertEquals(done, VerifiedMoveJournal(dir).readActive())
        assertTrue(done.proof.grantReadAcquired)
        // Completion still occupies the slot until explicit tracking-only retirement.
        rejected { journal.begin(proof()) }
        assertTrue(journal.forget(done))
        assertNull(VerifiedMoveJournal(dir).readActive())
        assertFalse(journal.forget(done))
    }

    @Test fun identicalBeginIsIdempotentAndConcurrentDifferentOperationsCannotOverwrite() {
        val dir = directory(); val first = proof(); val second = proof()
        val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(first, second).map { value -> pool.submit<Boolean> {
                check(gate.await(5L, TimeUnit.SECONDS))
                runCatching { VerifiedMoveJournal(dir).begin(value) }.isSuccess
            } }
            gate.countDown()
            assertEquals(1, tasks.count { it.get(10L, TimeUnit.SECONDS) })
            val winner = requireNotNull(VerifiedMoveJournal(dir).readActive())
            val original = file(dir).readBytes()
            assertEquals(winner, VerifiedMoveJournal(dir).begin(winner.proof))
            rejected { VerifiedMoveJournal(dir).begin(winner.proof.copy(sha256 = "c".repeat(64))) }
            assertArrayEquals(original, file(dir).readBytes())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5L, TimeUnit.SECONDS)) }
    }

    @Test fun staleStateInvalidTransitionsAndForgetNeverAlterCurrentOrSiblingFiles() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir); val ready = journal.begin(proof())
        val sibling = File(dir, "unrelated.bin").apply { writeText("preserve me") }
        rejected { journal.advance(ready, VerifiedMovePhase.Completed, 1L) }
        val awaiting = journal.advance(ready, VerifiedMovePhase.AwaitingSystem, 1L)
        rejected { journal.advance(ready, VerifiedMovePhase.AwaitingSystem, 2L) }
        assertFalse(journal.forget(ready))
        rejected { journal.advance(awaiting, VerifiedMovePhase.Cancelled, 2L) }
        val cancelled = journal.advance(awaiting, VerifiedMovePhase.Cancelled, 1L)
        rejected { journal.advance(cancelled, VerifiedMovePhase.AwaitingSystem, 1L) }
        assertEquals(cancelled, journal.readActive())
        assertTrue(journal.forget(cancelled))
        assertEquals("preserve me", sibling.readText())
    }

    @Test fun absentReadDoesNotCreateStateAndCorruptionNeverLooksAbsentOrGetsOverwritten() {
        val dir = directory(); val journal = VerifiedMoveJournal(dir)
        assertNull(journal.readActive()); assertFalse(dir.exists())
        val ready = journal.begin(proof()); val original = file(dir).readBytes()
        val payload = original.copyOf(original.size - 32)
        val badSchema = payload.copyOf().also { it[7] = 2 }
        listOf(original.copyOf(19), original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() },
            ByteArray(VerifiedMoveJournal.MaximumBytes + 1), checksum(badSchema), checksum(payload + byteArrayOf(1))).forEach { corrupt ->
            file(dir).writeBytes(corrupt)
            rejected { journal.readActive() }; rejected { journal.begin(proof()) }; rejected { journal.forget(ready) }
            assertArrayEquals(corrupt, file(dir).readBytes())
        }
        file(dir).writeBytes(original)
        assertEquals(ready, journal.readActive())
    }

    @Test fun failureBeforeRenameLeavesNoNewProofAndKeepsExistingStateForRetry() {
        val dir = directory(); var fail = true
        val journal = VerifiedMoveJournal(dir, { if (fail) throw IOException("injected publish") }, null)
        rejected { journal.begin(proof()) }
        assertNull(journal.readActive())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        fail = false
        val p = proof(); val ready = journal.begin(p); val original = file(dir).readBytes()
        fail = true
        rejected { journal.advance(ready, VerifiedMovePhase.AwaitingSystem, 1L) }
        assertArrayEquals(original, file(dir).readBytes())
        fail = false
        assertEquals(VerifiedMovePhase.AwaitingSystem, journal.advance(ready, VerifiedMovePhase.AwaitingSystem, 1L).phase)
    }

    @Test fun directorySyncFailureAfterRenameRetainsProofAndBlocksDifferentMove() {
        val dir = directory(); var fail = true
        val journal = VerifiedMoveJournal(dir, null) { path ->
            if (fail && path == dir.toPath() && file(dir).exists()) throw IOException("injected directory fsync")
            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
        val p = proof()
        rejected { journal.begin(p) }
        assertEquals(p, requireNotNull(journal.readActive()).proof)
        rejected { journal.begin(proof()) }
        rejected { journal.begin(p) }
        fail = false
        assertEquals(p, journal.begin(p).proof)
        assertEquals(p, requireNotNull(VerifiedMoveJournal(dir).readActive()).proof)
    }

    @Test fun symlinkIsRejectedWithoutReadingOrReplacingItsTarget() {
        val dir = directory(); assertTrue(dir.mkdir())
        val unrelated = temporary.newFile().apply { writeText("untouched original") }
        Files.createSymbolicLink(file(dir).toPath(), unrelated.toPath())
        val journal = VerifiedMoveJournal(dir)
        rejected { journal.readActive() }; rejected { journal.begin(proof()) }
        assertTrue(Files.isSymbolicLink(file(dir).toPath()))
        assertEquals("untouched original", unrelated.readText())
    }

    @Test fun boundedTypedProofRejectsMismatchedSourceTraversalAndMalformedIdentity() {
        val p = proof(); assertEquals(p, p.validated())
        assertEquals(0L, p.copy(bytes = 0L).validated().bytes)
        listOf(p.copy(id = "../escape"), p.copy(targetId = 18L), p.copy(targetKind = "Audio"),
            p.copy(targetVolume = "../external"), p.copy(bytes = -1L), p.copy(sha256 = "f".repeat(63)),
            p.copy(generationAdded = 10L), p.copy(sourceUri = p.sourceUri + "?query=1"),
            p.copy(destinationUri = "https://fixture.invalid/media"), p.copy(treeUri = "content://other/tree/own%3Adir"),
            p.copy(destinationUri = "content://fixture.documents/tree/other/document/file"),
            p.copy(destinationUri = "content://fixture.documents/document/" + "a".repeat(8192))).forEach { invalid ->
            rejected { invalid.validated() }
        }
        assertEquals("Video", p.copy(targetKind = "Video", sourceUri = "content://media/external_primary/video/media/17").validated().targetKind)
        assertEquals("content://fixture.documents/document/photo", p.copy(destinationUri = "content://fixture.documents/document/photo").validated().destinationUri)
    }
}
