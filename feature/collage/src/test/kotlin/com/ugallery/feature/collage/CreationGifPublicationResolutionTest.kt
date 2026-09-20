package com.ugallery.feature.collage

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreationGifPublicationResolutionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = File(temporary.root, UUID.randomUUID().toString())
    private fun receipt() = CreationGifPublicationReceipt(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        listOf("source-a@2/1", "source-b@4/3"), listOf("a".repeat(64), "b".repeat(64)), listOf(1, 0), 2, "c".repeat(64), 1234)
    private fun inserted(value: CreationGifPublicationReceipt) = value.copy(phase = CreationGifPublicationPhase.Inserted,
        destination = CreationGifPublicationDestination("content://media/external_primary/images/media/17", "com.ugallery.app",
            "UGallery-GIF-${value.token}.gif", "Pictures/UGallery/GIF/", "image/gif", 10, 10, 0, true, false))
    @Test fun enumerateValidAndUnreadableEntriesWithoutAdoptingTemporaryFiles() {
        val directory = directory(); val journal = CreationGifPublicationJournal(directory); val first = receipt(); val second = receipt()
        journal.begin(first); journal.begin(second)
        File(directory, "${second.sessionId}.bin").appendBytes(byteArrayOf(1))
        File(directory, "publication-orphan.tmp").writeText("ignored; not a committed marker")
        File(directory, "not-a-uuid.bin").writeText("invalid filename")
        val entries = journal.listEntries()
        assertEquals(3, entries.size); assertEquals(first, entries.single { it.id == first.sessionId }.receipt)
        assertTrue(entries.single { it.id == second.sessionId }.unreadable)
        assertNotNull(entries.single { it.id == second.sessionId }.journalSha256)
        assertTrue(entries.single { it.id == "not-a-uuid" }.unreadable)
        assertTrue(File(directory, "publication-orphan.tmp").exists())
    }
    @Test fun forgetExactIntentNeverTouchesOutputOrAnotherPublication() {
        val directory = directory(); val journal = CreationGifPublicationJournal(directory); val first = receipt(); val other = receipt()
        val output = File(temporary.root, "public-output-sentinel").apply { writeText("keep public bytes") }
        journal.begin(first); journal.begin(other)
        val entry = journal.readEntry(first.sessionId)
        assertTrue(journal.forgetEntry(entry)); assertFalse(journal.hasEntry(first.sessionId))
        assertEquals(other, journal.read(other.sessionId)); assertEquals("keep public bytes", output.readText())
    }
    @Test fun staleRawHashOrReceiptCannotForgetChangedPublication() {
        val directory = directory(); val journal = CreationGifPublicationJournal(directory); val first = receipt(); journal.begin(first)
        val entry = journal.readEntry(first.sessionId); val next = inserted(first); journal.advance(first, next)
        assertFalse(journal.forgetEntry(entry)); assertEquals(next, journal.read(first.sessionId))
        val current = journal.readEntry(first.sessionId)
        assertFalse(journal.forgetEntry(current.copy(journalSha256 = "0".repeat(64))))
        assertTrue(journal.forgetEntry(current))
    }
    @Test fun malformedOrOversizedJournalHasNoForgetActionAndRemainsOnDisk() {
        val directory = directory(); val journal = CreationGifPublicationJournal(directory); val first = receipt(); journal.begin(first)
        val file = File(directory, "${first.sessionId}.bin"); file.writeBytes(ByteArray(65537))
        val entry = journal.readEntry(first.sessionId); assertTrue(entry.unreadable); assertNull(entry.receipt)
        assertFalse(journal.forgetEntry(entry)); assertTrue(file.exists())
    }
    @Test fun writerLeaseIsSharedAcrossInstancesAndRejectsResolutionImmediately() = runBlocking {
        val directory = directory(); val first = CreationGifPublicationJournal(directory); val other = CreationGifPublicationJournal(directory); val value = receipt()
        first.withPublicationLock(value.sessionId) {
            first.begin(value)
            val entry = other.readEntry(value.sessionId)
            val result = withTimeout(200) { other.tryWithPublicationLock(value.sessionId, { false }) { other.forgetEntry(entry) } }
            assertFalse(result); assertEquals(value, other.read(value.sessionId))
        }
        assertTrue(other.tryWithPublicationLock(value.sessionId, { false }) { other.forgetEntry(other.readEntry(value.sessionId)) })
    }
    @Test fun cancellationReleasesLeaseAndDoesNotImplicitlyForgetMarker() = runBlocking {
        val journal = CreationGifPublicationJournal(directory()); val value = receipt()
        try { journal.withPublicationLock(value.sessionId) { journal.begin(value); throw java.util.concurrent.CancellationException("writer stopped") } }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(value, journal.read(value.sessionId))
        assertTrue(journal.tryWithPublicationLock(value.sessionId, { false }) { true })
    }
    @Test fun forgetSyncFailureRequiresDurableAbsentReadAndCannotTouchNewMarker() {
        val directory = directory(); var failSync = false
        val journal = CreationGifPublicationJournal(directory, null) { if (failSync) throw IOException("fsync") }
        val value = receipt(); journal.begin(value); val reviewed = journal.readEntry(value.sessionId)
        failSync = true
        try { journal.forgetEntry(reviewed); fail("Expected sync error") } catch (_: IOException) { }
        assertTrue(journal.readEntry(value.sessionId).unreadable)
        failSync = false; assertNull(journal.readEntry(value.sessionId).receipt)
        val replacement = value.copy(token = UUID.randomUUID().toString()); journal.begin(replacement)
        assertFalse(journal.forgetEntry(reviewed)); assertEquals(replacement, journal.read(value.sessionId))
    }
    @Test fun backingMissingRequiresExactEnoentAndNeverPermissionOrPartialMessage() {
        assertTrue(isGifPublicationBackingFileMissing(FileNotFoundException("open failed: ENOENT (No such file or directory)")))
        assertFalse(isGifPublicationBackingFileMissing(FileNotFoundException("open failed: EACCES (Permission denied)")))
        assertFalse(isGifPublicationBackingFileMissing(FileNotFoundException("ENOENT")))
        assertFalse(isGifPublicationBackingFileMissing(FileNotFoundException("permission denied; ENOENT elsewhere")))
    }
}
