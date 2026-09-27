package com.librestatic.lightforge.core.remotestorage.smb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.remotestorage.*
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only a disposable dedicated Samba fixture; all names owned by this test run. */
@RunWith(AndroidJUnit4::class)
class SmbRemoteConnectionDeviceTest {
    private val args
        get() = InstrumentationRegistry.getArguments()

    private fun profile() =
        RemoteProfile(
            name = "Disposable encrypted SMB",
            protocol = RemoteProtocol.SMB,
            host = args.getString("smbHost") ?: "10.0.2.2",
            port = (args.getString("smbPort") ?: "14455").toInt(),
            username = "lightforgefixture",
            share = "backup",
            root = "",
            domain = "WORKGROUP",
        )

    private fun connect(cancellation: RemoteCancellation = RemoteCancellation()): RemoteConnection {
        val password =
            requireNotNull(args.getString("smbPassword")) {
                "Dedicated smbPassword fixture argument required"
            }
        return SmbRemoteConnectionFactory(5000)
            .connect(profile(), RemoteCredentials.Password(password.toCharArray()), cancellation)
    }

    private fun name() = "owned-${UUID.randomUUID()}.zip"

    private fun digest(bytes: ByteArray) =
        RemoteDigest(
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            },
        )

    private fun assertFailure(expected: RemoteFailure, action: () -> Unit) {
        assertEquals(
            expected,
            assertThrows(RemoteStorageException::class.java) { action() }.failure,
        )
    }

    @Test
    fun encryptedSignedUnicodeWriteReadStatOffsetAndPublish() {
        connect().use { remote ->
            assertTrue(remote.capabilities.encrypted)
            assertTrue(remote.capabilities.signed)
            assertTrue(remote.capabilities.atomicPublish)
            val staging = "Málaga-${UUID.randomUUID()}.partial"
            val destination = name()
            val bytes = ByteArray(700123) { (it * 31).toByte() }
            remote.createExclusive(staging).use {
                it.write(bytes)
                it.flush()
            }
            assertEquals(bytes.size.toLong(), remote.stat(staging)!!.size)
            assertArrayEquals(
                bytes.copyOfRange(262144, bytes.size),
                remote.openRead(staging, 262144).use { it.readBytes() },
            )
            remote.publishNoReplace(staging, destination, digest(bytes))
            assertArrayEquals(bytes, remote.openRead(destination).use { it.readBytes() })
            assertNull(remote.stat(staging))
            assertFalse(remote.residualNames.contains(destination))
            assertTrue(remote.list(10000).any { it.name == destination && it.regularFile })
        }
    }

    @Test
    fun exclusiveCreationNeverOverwritesExistingBytes() {
        val path = name()
        val bytes = "original".toByteArray()
        connect().use { remote ->
            remote.createExclusive(path).use { it.write(bytes) }
            assertFailure(RemoteFailure.ALREADY_EXISTS) { remote.createExclusive(path).close() }
            assertArrayEquals(bytes, remote.openRead(path).use { it.readBytes() })
        }
    }

    @Test
    fun publishNoReplaceRetainsBothOriginalAndStageOnConflict() {
        connect().use { remote ->
            val stage = name()
            val destination = name()
            val bytes = "stage".toByteArray()
            val original = "original".toByteArray()
            remote.createExclusive(stage).use { it.write(bytes) }
            remote.createExclusive(destination).use { it.write(original) }
            assertFailure(RemoteFailure.ALREADY_EXISTS) {
                remote.publishNoReplace(stage, destination, digest(bytes))
            }
            assertArrayEquals(original, remote.openRead(destination).use { it.readBytes() })
            assertArrayEquals(bytes, remote.openRead(stage).use { it.readBytes() })
        }
    }

    @Test
    fun digestMismatchKeepsStageAndDoesNotPublish() {
        connect().use { remote ->
            val stage = name()
            val destination = name()
            remote.createExclusive(stage).use { it.write("actual".toByteArray()) }
            assertFailure(RemoteFailure.CONFLICT) {
                remote.publishNoReplace(stage, destination, digest("other".toByteArray()))
            }
            assertNull(remote.stat(destination))
            assertArrayEquals("actual".toByteArray(), remote.openRead(stage).use { it.readBytes() })
        }
    }

    @Test
    fun listLimitIsExplicitAndTraversalRejected() {
        connect().use { remote ->
            repeat(2) { remote.createExclusive(name()).use { it.write(1) } }
            assertFailure(RemoteFailure.CONFLICT) { remote.list(1) }
            assertFailure(RemoteFailure.INVALID_PATH) {
                remote.openRead("../outside/sentinel").close()
            }
            assertThrows(RemoteStorageException::class.java) { remote.openRead("escape").close() }
        }
    }

    @Test
    fun wrongPasswordIsRejected() {
        assertFailure(RemoteFailure.AUTHENTICATION) {
            SmbRemoteConnectionFactory(5000)
                .connect(
                    profile(),
                    RemoteCredentials.Password("incorrect-${UUID.randomUUID()}".toCharArray()),
                    RemoteCancellation(),
                )
                .close()
        }
    }

    @Test
    fun cancellationClosesConnectionAndRejectsFurtherIo() {
        val cancellation = RemoteCancellation()
        connect(cancellation).use { remote ->
            val stage = name()
            val output = remote.createExclusive(stage)
            output.write(ByteArray(4096))
            cancellation.cancel()
            assertFailure(RemoteFailure.CANCELLED) { output.write(1) }
            runCatching { output.close() }
            assertFailure(RemoteFailure.CANCELLED) { remote.list(10) }
        }
    }

    @Test
    fun smb2OnlyServerIsRejectedRatherThanDowngraded() {
        val password = requireNotNull(args.getString("smbPassword"))
        assertThrows(RemoteStorageException::class.java) {
            SmbRemoteConnectionFactory(2000)
                .connect(
                    profile().copy(port = 14456),
                    RemoteCredentials.Password(password.toCharArray()),
                    RemoteCancellation(),
                )
                .close()
        }
    }

    @Test
    fun stalledNegotiationTimesOutAndCancelsPromptly() {
        val password = requireNotNull(args.getString("smbPassword"))
        val start = android.os.SystemClock.elapsedRealtime()
        assertThrows(RemoteStorageException::class.java) {
            SmbRemoteConnectionFactory(500)
                .connect(
                    profile().copy(port = 14457),
                    RemoteCredentials.Password(password.toCharArray()),
                    RemoteCancellation(),
                )
                .close()
        }
        assertTrue(
            "Negotiation timeout exceeded bound",
            android.os.SystemClock.elapsedRealtime() - start < 5000,
        )
        val cancel = RemoteCancellation()
        val result = java.util.concurrent.CompletableFuture<RemoteFailure>()
        val thread = Thread {
            try {
                SmbRemoteConnectionFactory(15000)
                    .connect(
                        profile().copy(port = 14457),
                        RemoteCredentials.Password(password.toCharArray()),
                        cancel,
                    )
                    .close()
                result.completeExceptionally(AssertionError("Unexpected connection"))
            } catch (error: RemoteStorageException) {
                result.complete(error.failure)
            }
        }
        thread.start()
        awaitStalledInHandshake(thread)
        cancel.cancel()
        assertEquals(RemoteFailure.CANCELLED, result.get(3, java.util.concurrent.TimeUnit.SECONDS))
        thread.join(1000)
        assertFalse(thread.isAlive)
    }

    @Test
    fun listRefreshSeesNewCreatesAndRenameInSameConnection() {
        connect().use { remote ->
            val stage = name()
            val destination = name()
            val bytes = "fresh directory snapshot".toByteArray()
            assertFalse(remote.list(10000).any { it.name == stage })
            remote.createExclusive(stage).use { it.write(bytes) }
            assertTrue(remote.list(10000).any { it.name == stage && it.regularFile })
            remote.publishNoReplace(stage, destination, digest(bytes))
            val refreshed = remote.list(10000)
            assertTrue(refreshed.any { it.name == destination && it.regularFile })
            assertFalse(refreshed.any { it.name == stage })
        }
    }
}
