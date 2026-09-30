package com.librestatic.lightforge.feature.motionphotos

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionPhotoPublicationResolutionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun receipt() = MotionPhotoPublicationReceipt(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "content://media/external_primary/images/media/80", 8, 2,
        "content://media/external_primary/images/media/80@8/2", "a".repeat(64),
        MotionPhotoPublicationKind.Frame, 1000, 3000, "b".repeat(64), 1234)
    private fun inserted(r: MotionPhotoPublicationReceipt) = r.copy(phase = MotionPhotoPublicationPhase.Inserted,
        destination = MotionPhotoPublicationDestination("content://media/external/images/media/17", "com.librestatic.lightforge",
            "Lightforge-Motion-${r.token}.jpg", "Pictures/Lightforge/Motion/", "image/jpeg", 10, 10, 0, true, false))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun listingKeepsRawHashAndExactReceiptButIgnoresNonUuidAndTemporaryFiles() {
        val directory = File(temporary.root, "journal"); val journal = MotionPhotoPublicationJournal(directory); val r = receipt()
        assertTrue(journal.listEntries().isEmpty()); assertFalse(directory.exists())
        journal.begin(r)
        File(directory, "publication-orphan.tmp").writeText("not adopted")
        File(directory, "unknown.bin").writeText("not adopted")
        val entry = journal.listEntries().single()
        assertEquals(r.publicationId, entry.id); assertEquals(r, entry.receipt); assertFalse(entry.unreadable)
        assertEquals(hash(File(directory, "${r.publicationId}.bin").readBytes()), entry.journalSha256)
        assertEquals(entry, journal.readEntry(r.publicationId))
    }

    @Test fun unreadableChecksumWrongIdentityAndSymlinkNeverBecomeForgettable() {
        val directory = File(temporary.root, "journal"); val journal = MotionPhotoPublicationJournal(directory); val r = receipt(); journal.begin(r)
        val file = File(directory, "${r.publicationId}.bin"); val original = file.readBytes()
        file.writeBytes(original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() })
        val corrupt = journal.readEntry(r.publicationId)
        assertTrue(corrupt.unreadable); assertNull(corrupt.receipt); assertNotNull(corrupt.journalSha256); assertFalse(journal.forget(corrupt))
        file.writeBytes(original)
        val other = receipt().publicationId; val otherFile = File(directory, "$other.bin"); otherFile.writeBytes(original)
        assertTrue(journal.readEntry(other).unreadable); assertFalse(journal.forget(journal.readEntry(other)))
        check(otherFile.delete()); Files.createSymbolicLink(otherFile.toPath(), file.toPath())
        assertTrue(journal.readEntry(other).unreadable); assertFalse(journal.forget(journal.readEntry(other)))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun forgetRequiresExactRawHashAndReceiptAndAllowsKnownIntentWithoutOutputAccess() {
        val directory = File(temporary.root, "journal"); val journal = MotionPhotoPublicationJournal(directory); val r = receipt(); journal.begin(r)
        val entry = journal.readEntry(r.publicationId)
        assertFalse(journal.forget(entry.copy(journalSha256 = "0".repeat(64))))
        assertFalse(journal.forget(entry.copy(receipt = r.copy(sourceSha256 = "c".repeat(64)))))
        journal.advance(r, inserted(r)); assertFalse(journal.forget(entry))
        assertTrue(journal.forget(journal.readEntry(r.publicationId))); assertNull(journal.read(r.publicationId))
        journal.begin(r); assertTrue(journal.forget(journal.readEntry(r.publicationId)))
    }

    @Test fun forgetDirectorySyncFailureIsNotReportedAsSuccess() {
        val directory = File(temporary.root, "journal"); var failSync = false
        val journal = MotionPhotoPublicationJournal(directory, null) { if (failSync) throw IOException("directory fsync") }
        val r = receipt(); journal.begin(r); val entry = journal.readEntry(r.publicationId); failSync = true
        try { journal.forget(entry); fail("Expected fsync error") } catch (_: IOException) { }
        assertTrue(journal.readEntry(r.publicationId).unreadable)
        failSync = false; assertNull(journal.readEntry(r.publicationId).receipt)
    }

    @Test fun missingBackingFileRequiresExactErrnoMessageNotGenericMissingOrPermissionFailure() {
        assertTrue(isMotionPublicationBackingFileMissing(java.io.FileNotFoundException("open failed: ENOENT (No such file or directory)")))
        for (message in listOf("No such file or directory", "Permission denied", "open failed: EACCES (Permission denied)",
            "URI not found", "prefix open failed: ENOENT (No such file or directory)")) {
            assertFalse(isMotionPublicationBackingFileMissing(java.io.FileNotFoundException(message)))
        }
    }

    @Test fun fastBusyIsSharedAcrossInstancesAndDifferentIdsRemainIndependent() = runBlocking {
        val directory = File(temporary.root, "journal"); val a = MotionPhotoPublicationJournal(directory); val b = MotionPhotoPublicationJournal(directory)
        val id = receipt().publicationId; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writer = launch { a.withPublicationLock(id) { entered.complete(Unit); release.await() } }
        entered.await()
        assertEquals("busy", b.tryWithPublicationLock(id, { "busy" }) { error("Entered active writer") })
        assertEquals("other", b.tryWithPublicationLock(receipt().publicationId, { "busy" }) { "other" })
        release.complete(Unit); writer.join()
        assertEquals("available", b.tryWithPublicationLock(id, { "busy" }) { "available" })
    }

    @Test fun writerCanReadItsOwnJournalAndFailureReleasesTheGate() = runBlocking {
        val journal = MotionPhotoPublicationJournal(File(temporary.root, "journal")); val r = receipt()
        try { journal.withPublicationLock(r.publicationId) { journal.begin(r); assertEquals(r, journal.read(r.publicationId)); throw IOException("callback") } }
        catch (_: IOException) { }
        assertTrue(journal.tryWithPublicationLock(r.publicationId, { false }) { journal.forget(journal.readEntry(r.publicationId)) })
    }
}
