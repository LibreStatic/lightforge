package com.ugallery.feature.remotebackup

import com.ugallery.core.remotestorage.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class RemoteBackupIOTest {
    @Test
    fun boundedReaderHandlesShortReadsAndRejectsOverflow() {
        val input =
            object : ByteArrayInputStream(ByteArray(65539) { it.toByte() }) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) =
                    super.read(bytes, offset, minOf(7, length))
            }
        assertEquals(65539, RemoteBackupIO.readBounded(input, 65539).size)
        assertThrows(IOException::class.java) {
            RemoteBackupIO.readBounded(ByteArrayInputStream(ByteArray(11)), 10)
        }
    }

    @Test
    fun largeStreamNeverRequestsMoreThan64KiBAndChecksEveryChunk() {
        var remaining = 32 * 1024 * 1024L
        var checks = 0
        val input =
            object : InputStream() {
                override fun read(): Int = error("No single-byte fallback")

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    assertTrue(length <= 65536)
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    bytes.fill(7, offset, offset + count)
                    remaining -= count
                    return count
                }
            }
        assertEquals(32 * 1024 * 1024L, RemoteBackupIO.digest(input) { checks++ }.size)
        assertTrue(checks > 500)
    }

    @Test
    fun oversizedSourceStopsBeforeWritingBeyondLimit() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            RemoteBackupIO.copy(ByteArrayInputStream(ByteArray(100)), output, {}, 99)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun cancellationInterruptsHashRatherThanWaitingForArchiveEnd() {
        var checks = 0
        assertThrows(InterruptedIOException::class.java) {
            RemoteBackupIO.digest(ByteArrayInputStream(ByteArray(8 * 65536))) {
                if (++checks == 3) throw InterruptedIOException()
            }
        }
        assertEquals(3, checks)
    }

    @Test
    fun prefixComparisonLeavesInputExactlyAtResumePosition() {
        val file = File.createTempFile("remote-prefix", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val remote = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            assertEquals(3L, RemoteBackupIO.comparePrefix(file, remote) {})
            assertEquals(4, remote.read())
        } finally {
            file.delete()
        }
    }

    @Test
    fun modifiedOrTruncatedRemotePrefixNeverPasses() {
        val file = File.createTempFile("remote-prefix", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(RemoteContentChanged::class.java) {
                RemoteBackupIO.comparePrefix(file, ByteArrayInputStream(byteArrayOf(1, 9, 3))) {}
            }
            assertThrows(RemoteContentChanged::class.java) {
                RemoteBackupIO.comparePrefix(file, ByteArrayInputStream(byteArrayOf(1, 2))) {}
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun sameSizeIsNotEnoughForRemoteIdentity() {
        val expected = RemoteEntry("a.ugallery.zip", 10, 20, true)
        assertThrows(RemoteContentChanged::class.java) {
            RemoteBackupIO.requireSame(expected, expected.copy(modifiedMillis = 21))
        }
        assertThrows(RemoteContentChanged::class.java) {
            RemoteBackupIO.requireSame(expected, expected.copy(regularFile = false))
        }
        RemoteBackupIO.requireSame(expected, expected.copy())
    }

    @Test
    fun unsupportedAtomicityOrProtectionIsRejectedBeforeUpload() {
        fun connection(caps: RemoteCapabilities) =
            object : RemoteConnection {
                override val capabilities = caps
                override val identity = "fixture"

                override fun list(limit: Int) = emptyList<RemoteEntry>()

                override fun stat(name: String): RemoteEntry? = null

                override fun openRead(name: String, offset: Long): InputStream =
                    error("No I/O expected")

                override fun createExclusive(name: String): OutputStream = error("No I/O expected")

                override fun publishNoReplace(
                    staging: String,
                    destination: String,
                    expected: RemoteDigest,
                ) = error("No I/O expected")

                override fun close() {}
            }
        listOf(
                RemoteCapabilities(false, true, true),
                RemoteCapabilities(true, false, true),
                RemoteCapabilities(true, true, false),
            )
            .forEach {
                assertThrows(RemoteStorageException::class.java) {
                    RemoteBackupIO.requireUploadCapability(connection(it))
                }
            }
        RemoteBackupIO.requireUploadCapability(connection(RemoteCapabilities(true, true, true)))
    }
}
