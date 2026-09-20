package com.ugallery.app

import com.ugallery.core.data.ManualMomentDraft
import com.ugallery.core.data.ManualMomentSource
import com.ugallery.core.model.MediaKey
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class ManualMomentPendingCreateTest {
    @Test fun onlyExplicitSuccessArmsAckForTheExactDisplayedCommittedRequest() {
        val value = request()
        val committed = com.ugallery.core.data.ManualMomentCommitStatus.Committed
        assertTrue(manualMomentMayAcknowledge(value, value.token, value.draft.id, committed))
        // Initial recovery/Later does not arm a token, even if the same Moment is already behind the notice.
        assertFalse(manualMomentMayAcknowledge(value, null, value.draft.id, committed))
        assertFalse(manualMomentMayAcknowledge(value, "stale-token", value.draft.id, committed))
        assertFalse(manualMomentMayAcknowledge(value, value.token, "another-moment", committed))
        assertFalse(manualMomentMayAcknowledge(value, value.token, null, committed))
        assertFalse(manualMomentMayAcknowledge(value, value.token, value.draft.id,
            com.ugallery.core.data.ManualMomentCommitStatus.Missing))
        assertFalse(manualMomentMayAcknowledge(value, value.token, value.draft.id,
            com.ugallery.core.data.ManualMomentCommitStatus.Conflict))
    }

    private val draftId = "192d2194-d06c-4ff2-9fa8-97d73c707db7"
    private fun request(): ManualMomentCreateRequest {
        val sources = listOf(ManualMomentSource(MediaKey("external_primary", 1), 3, 8),
            ManualMomentSource(MediaKey("external_primary", 2), 4, 9, true))
        return ManualMomentCreateRequest.capture(ManualMomentDraft(draftId, sources),
            "  Viaje 東京 😀  ", sources.reversed().map { it.key }, true)
    }
    private inline fun fixture(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("manual-pending-test-").toFile()
        try { block(root, File(root, "pending")) }
        finally { Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejected pending intent") } catch (_: Exception) { }
    }
    private fun main(directory: File) = File(directory, ManualMomentPendingCreateStore.FileName)
    private fun withDigest(payload: ByteArray): ByteArray = payload + MessageDigest.getInstance("SHA-256").digest(payload)

    @Test fun roundtripPreservesUnicodeExactOrderBothGenerationsAndFlags() = fixture { _, directory ->
        val value = request()
        val store = ManualMomentPendingCreateStore(directory)
        assertNull(store.read())
        store.put(value)
        assertEquals("Viaje 東京 😀", value.title)
        assertEquals(value, ManualMomentPendingCreateStore(directory).read())
        assertNull(value.draft.originSelection)
        assertTrue(main(directory).length() <= ManualMomentPendingCreateStore.MaximumBytes)
        assertEquals(listOf(2L, 1L), store.read()!!.orderedKeys.map { it.mediaStoreId })
    }

    @Test fun captureDetachesMutableDraftAndOrder() {
        val original = request()
        val sources = original.draft.toDraft().sources.toMutableList()
        val order = original.orderedKeys.toMutableList()
        val value = ManualMomentCreateRequest.capture(ManualMomentDraft(draftId, sources), "", order, false)
        sources.clear(); order.clear()
        assertEquals(2, value.draft.sources.size)
        assertEquals(2, value.orderedKeys.size)
        assertEquals("", value.title)
        assertFalse(value.includeSpecialMedia)
        try { (value.orderedKeys as MutableList<MediaKey>).clear(); fail("Mutable request order") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun rejectsMalformedIdentifiersTitleOrdersAndGenerations() {
        val value = request()
        listOf(value.copy(token = "bad"), value.copy(token = value.token.uppercase()),
            value.copy(title = " unnormalized "), value.copy(title = "x".repeat(121)),
            value.copy(orderedKeys = emptyList()), value.copy(orderedKeys = List(121) { value.orderedKeys[0] }),
            value.copy(orderedKeys = listOf(value.orderedKeys[0], value.orderedKeys[0])),
            value.copy(orderedKeys = listOf(MediaKey("external_primary", 999))),
            value.copy(draft = value.draft.copy(id = "bad")),
            value.copy(draft = value.draft.copy(sources = listOf(value.draft.sources[0].copy(generationAdded = -1)))),
            value.copy(draft = value.draft.copy(sources = listOf(value.draft.sources[0].copy(generationModified = -1)))),
        ).forEach { rejected { ManualMomentCreateRequest.validatedCopy(it) } }
    }

    @Test fun exactPutIsIdempotentButAnotherIntentNeverOverwrites() = fixture { _, directory ->
        val store = ManualMomentPendingCreateStore(directory); val value = request()
        store.put(value); val bytes = main(directory).readBytes()
        ManualMomentPendingCreateStore(directory).put(value)
        assertArrayEquals(bytes, main(directory).readBytes())
        rejected { store.put(value.copy(token = UUID.randomUUID().toString())) }
        rejected { store.put(value.copy(title = "Changed")) }
        assertArrayEquals(bytes, main(directory).readBytes())
    }

    @Test fun staleClearPreservesCurrentIntentAndExactClearIsDurable() = fixture { _, directory ->
        val store = ManualMomentPendingCreateStore(directory); val value = request(); store.put(value)
        assertFalse(store.clear(value.copy(token = UUID.randomUUID().toString())))
        assertFalse(store.clear(value.copy(orderedKeys = value.orderedKeys.reversed())))
        assertEquals(value, store.read())
        assertTrue(store.clear(value))
        assertNull(ManualMomentPendingCreateStore(directory).read())
        assertFalse(store.clear(value))
    }

    @Test fun strictCodecRejectsCorruptionTruncationTrailingAndUnknownVersion() = fixture { _, directory ->
        val store = ManualMomentPendingCreateStore(directory); store.put(request())
        val bytes = main(directory).readBytes(); val payload = bytes.copyOf(bytes.size - 32)
        val unknownVersion = payload.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) }
        val invalidUtf8 = payload.copyOf().also { it[12] = 0xff.toByte() }
        listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().also { it[0] = 0 },
            withDigest(payload + byteArrayOf(0)), withDigest(payload.copyOf(payload.size - 1)),
            withDigest(unknownVersion), withDigest(invalidUtf8), byteArrayOf(),
        ).forEach { malformed ->
            main(directory).writeBytes(malformed)
            rejected { store.read() }
            rejected { store.put(request()) }
            assertArrayEquals(malformed, main(directory).readBytes())
        }
    }

    @Test fun oversizedInputFailsBeforeWritingAndOversizedFileIsNotAbsent() = fixture { _, directory ->
        val value = request(); val key = MediaKey("x".repeat(65_500), 1)
        val oversized = value.copy(draft = value.draft.copy(sources = listOf(value.draft.sources[0].copy(key = key))),
            orderedKeys = listOf(key))
        val store = ManualMomentPendingCreateStore(directory)
        rejected { store.put(oversized) }
        assertFalse(directory.exists())
        directory.mkdir(); main(directory).writeBytes(ByteArray(65_537))
        rejected { store.read() }
    }

    @Test fun orphanTempIsNotCommittedAndInterruptedPutLeavesNoMain() = fixture { _, directory ->
        directory.mkdir()
        val orphan = File(directory, "pending-owned-orphan.tmp").apply { writeText("incomplete") }
        val store = ManualMomentPendingCreateStore(directory)
        assertNull(store.read())
        val failing = ManualMomentPendingCreateStore(directory) { throw IOException("fixture before publish") }
        rejected { failing.put(request()) }
        assertNull(store.read())
        assertEquals(listOf(orphan.name), directory.list()!!.toList())
        val value = request(); store.put(value)
        assertEquals(value, store.read())
        assertEquals("incomplete", orphan.readText())
    }

    @Test fun concurrentInstancesPublishExactlyOneIntent() = fixture { _, directory ->
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val requests = listOf(request(), request())
        try {
            val results = requests.map { value -> pool.submit<Boolean> {
                start.await()
                try { ManualMomentPendingCreateStore(directory).put(value); true }
                catch (_: IllegalStateException) { false }
            } }
            start.countDown()
            val accepted = results.map { it.get() }
            assertEquals(1, accepted.count { it })
            assertEquals(requests[accepted.indexOf(true)], ManualMomentPendingCreateStore(directory).read())
        } finally { pool.shutdownNow() }
    }

    @Test fun rejectsSymlinkDirectoryAncestorAndMainWithoutTouchingTargets() = fixture { root, directory ->
        val target = File(root, "target").apply { mkdir() }
        Files.createSymbolicLink(directory.toPath(), target.toPath())
        rejected { ManualMomentPendingCreateStore(directory).read() }
        rejected { ManualMomentPendingCreateStore(directory).put(request()) }
        Files.delete(directory.toPath())
        val linkParent = File(root, "link-parent")
        Files.createSymbolicLink(linkParent.toPath(), target.toPath())
        rejected { ManualMomentPendingCreateStore(File(linkParent, "child")).put(request()) }
        directory.mkdir()
        val sentinel = File(target, "sentinel").apply { writeText("unchanged") }
        Files.createSymbolicLink(main(directory).toPath(), sentinel.toPath())
        rejected { ManualMomentPendingCreateStore(directory).read() }
        rejected { ManualMomentPendingCreateStore(directory).clear(request()) }
        assertEquals("unchanged", sentinel.readText())
    }
    @Test fun failedUnlinkSyncCannotBeAcknowledgedAsDurableAbsence() = fixture { _, directory ->
        val store = ManualMomentPendingCreateStore(directory)
        val value = request()
        store.put(value)
        var failedSyncs = 0
        store.beforeDirectorySync = {
            if (!main(directory).exists()) {
                failedSyncs++
                throw IOException("fixture directory fsync after unlink")
            }
        }
        rejected { store.clear(value) }
        assertFalse(main(directory).exists())
        rejected { store.read() }
        rejected { store.clear(value) }
        assertEquals(3, failedSyncs)
        var successfulSyncs = 0
        store.beforeDirectorySync = { successfulSyncs++ }
        assertNull(store.read())
        assertFalse(store.clear(value))
        assertEquals(2, successfulSyncs)
        store.beforeDirectorySync = null
        val another = request()
        store.put(another)
        val retained = main(directory).readBytes()
        assertFalse(store.clear(value))
        assertEquals(another, store.read())
        assertArrayEquals(retained, main(directory).readBytes())
    }

}
