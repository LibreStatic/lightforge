package com.librestatic.lightforge.feature.ownsync

import com.librestatic.lightforge.core.remotestorage.RemoteDigest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OwnSyncModelTest {
    private val digest = RemoteDigest(4, "a".repeat(64))

    @Test
    fun alternateNameIsStableAndPreservesExtension() {
        val value = ownSyncVersionName("photo.jpeg", "source", digest)
        assertEquals(value, ownSyncVersionName("photo.jpeg", "source", digest))
        assertTrue(value.endsWith(".jpeg"))
        assertNotEquals(value, ownSyncVersionName("photo.jpeg", "another", digest))
    }

    @Test
    fun newVersionsNeverReuseOldVersionName() {
        assertNotEquals(
            ownSyncVersionName("a.jpg", "one", digest),
            ownSyncVersionName("a.jpg", "one", digest.copy(sha256 = "b".repeat(64))),
        )
    }

    @Test
    fun identicalDocumentsKeepDistinctIdentity() {
        val a = OwnSyncSourceEntry("a", "content://one", listOf("a"), "image/png", digest, 0)
        val b = a.copy(key = "b", uri = "content://two", path = listOf("b"))
        assertNotEquals(
            OwnSyncSnapshot(listOf(a)).fingerprint,
            OwnSyncSnapshot(listOf(b)).fingerprint,
        )
    }

    @Test
    fun snapshotFingerprintIgnoresCursorOrderButIncludesDirectories() {
        val a = OwnSyncSourceEntry("a", "content://one", listOf("a"), "image/png", digest, 0)
        val b = a.copy(key = "b")
        assertEquals(
            OwnSyncSnapshot(listOf(a, b)).fingerprint,
            OwnSyncSnapshot(listOf(b, a)).fingerprint,
        )
        assertNotEquals(
            OwnSyncSnapshot(listOf(a)).fingerprint,
            OwnSyncSnapshot(listOf(a), directories = listOf(listOf("empty"))).fingerprint,
        )
    }

    @Test
    fun incompleteNeverEqualsCompleteFlag() {
        assertFalse(OwnSyncSnapshot(emptyList(), listOf("loading")).complete)
        assertTrue(OwnSyncSnapshot(emptyList()).complete)
    }

    @Test
    fun streamingCopyHashesExactBytes() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 253).toByte() }
        val out = ByteArrayOutputStream()
        val actual = OwnSyncIO.copy(ByteArrayInputStream(bytes), out)
        assertEquals(bytes.size.toLong(), actual.size)
        assertEquals(ownSyncHash(bytes), actual.sha256)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun cancellationIsCheckedDuringHashing() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            OwnSyncIO.copy(
                ByteArrayInputStream(ByteArray(1024 * 1024)),
                check = { if (++checks == 3) throw CancellationException() },
            )
        }
        assertEquals(3, checks)
    }

    @Test
    fun boundedReadRejectsOversizeRatherThanTruncates() {
        assertThrows(IllegalArgumentException::class.java) {
            OwnSyncIO.bounded(ByteArrayInputStream(ByteArray(1025)), 1024)
        }
    }

    @Test
    fun unsafePathComponentsAreRejected() {
        listOf("..", "a/b", "x\\y", "tail.").forEach {
            assertThrows(IllegalArgumentException::class.java) { ownSyncPath(listOf(it)) }
        }
    }

    @Test
    fun progressCountsOnlyCompletedNewCopies() {
        val source = OwnSyncSourceEntry("k", "content://one", listOf("a"), "x", digest, 0)
        val run =
            OwnSyncRun(
                ownSyncUuid(),
                ownSyncUuid(),
                plan =
                    listOf(
                        OwnSyncPlanEntry(
                            ownSyncUuid(),
                            OwnSyncAction.Add,
                            source = source,
                            path = listOf("a"),
                            done = true,
                        ),
                        OwnSyncPlanEntry(
                            ownSyncUuid(),
                            OwnSyncAction.Verified,
                            source = source,
                            path = listOf("b"),
                            done = true,
                        ),
                    ),
            )
        assertEquals(4L, run.bytesDone)
    }

    @Test
    fun onlyUserRequestsPauseOrCancelAnInterruptedRun() {
        val run = OwnSyncRun("r", "j", status = OwnSyncStatus.Running)
        assertEquals(OwnSyncStatus.Queued, run.interruptedStatus)
        assertEquals(OwnSyncStatus.Paused, run.copy(pauseRequested = true).interruptedStatus)
        assertEquals(
            OwnSyncStatus.Cancelled,
            run.copy(pauseRequested = true, cancelRequested = true).interruptedStatus,
        )
    }
}
