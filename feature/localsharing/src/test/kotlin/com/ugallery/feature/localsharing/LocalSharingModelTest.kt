package com.ugallery.feature.localsharing

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class LocalSharingModelTest {
    private fun entry() =
        LocalSharingEntry(
            "source-a",
            "revision-a",
            "original.jpg",
            "image/jpeg",
            3,
            "a".repeat(64),
            123,
            true,
        )

    @Test
    fun validSanitizedManifest() {
        LocalSharingManifest(listOf(entry()), true).validate()
    }

    @Test
    fun stripNeverAcceptsUnsanitizedOriginal() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSharingManifest(listOf(entry().copy(sanitized = false)), true).validate()
        }
    }

    @Test
    fun distinctSourcesCanContainIdenticalBytes() {
        LocalSharingManifest(listOf(entry(), entry().copy(sourceId = "source-b")), true).validate()
    }

    @Test
    fun duplicateSourceIdentityRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSharingManifest(listOf(entry(), entry()), true).validate()
        }
    }

    @Test
    fun namesNeverBecomePaths() {
        listOf("../x", "a/b", "a\\b", ".", "a\u0000b").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                entry().copy(name = bad).validate()
            }
        }
    }

    @Test
    fun negativeAndOversizeBytesRejected() {
        listOf(-1L, LOCAL_SHARING_MAX_FILE_BYTES + 1).forEach { n ->
            assertThrows(IllegalArgumentException::class.java) {
                entry().copy(bytes = n).validate()
            }
        }
    }

    @Test
    fun shaMustBeCanonical() {
        assertThrows(IllegalArgumentException::class.java) {
            entry().copy(sha256 = "B".repeat(64)).validate()
        }
    }

    @Test
    fun expiredInvitationRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSharingInvitation("127.0.0.1", 22238, "a".repeat(64), "b".repeat(64), 999)
                .validate(1000)
        }
    }

    @Test
    fun invitationRequiresRandomLengthSecretAndLiteralEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSharingInvitation("server.example", 22238, "a".repeat(64), "123456", 2000)
                .validate(1000)
        }
    }

    @Test
    fun boundedStreamingDigestDoesNotNeedFileSizedArray() {
        val size = 32L * 1024 * 1024
        var left = size
        var calls = 0
        val input =
            object : InputStream() {
                override fun read(): Int = error("bulk only")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left == 0L) return -1
                    val n = minOf(left, len.toLong()).toInt()
                    java.util.Arrays.fill(b, off, off + n, 7.toByte())
                    left -= n
                    return n
                }
            }
        val digest = peerDigest(input, size) { calls++ }
        assertEquals(size, digest.first)
        assertTrue(calls > 100)
        assertEquals(64, digest.second.length)
    }

    @Test
    fun streamingDigestStopsAtLimit() {
        assertThrows(java.io.IOException::class.java) {
            peerDigest(ByteArrayInputStream(ByteArray(10)), 9)
        }
    }

    @Test
    fun streamingDigestChecksCancellation() {
        assertThrows(PeerStopped::class.java) {
            peerDigest(ByteArrayInputStream(ByteArray(100)), 100) { throw PeerStopped() }
        }
    }
}
