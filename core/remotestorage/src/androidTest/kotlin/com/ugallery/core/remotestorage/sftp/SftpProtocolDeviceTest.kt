package com.ugallery.core.remotestorage.sftp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.remotestorage.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.security.Security
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real OpenSSH loopback fixture only; each test owns UUID files, never normal gallery/source paths. */
@RunWith(AndroidJUnit4::class)
class SftpProtocolDeviceTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var profile: RemoteProfile
    private val factory = SftpRemoteConnectionFactory(3_000, 3_000)
    @Before fun fixture() {
        require(args.getString("sftpFixture") == "ugallery-wave34") { "Explicit disposable SFTP fixture required" }
        profile = RemoteProfile(name = "SFTP acceptance fixture", protocol = RemoteProtocol.SFTP,
            host = args.getString("sftpHost") ?: "10.0.2.2", port = 22234,
            username = "ugalleryfixture", root = "/data/archive",
            trustedHostKey = requireNotNull(args.getString("sftpPin")))
        require(profile.host in setOf("10.0.2.2", "127.0.0.1"))
    }
    private fun password(value: String = "ugallery-fixture-password") = RemoteCredentials.Password(value.toCharArray())
    private fun open(cancellation: RemoteCancellation = RemoteCancellation()) = factory.connect(profile, password(), cancellation)
    private fun own(suffix: String) = "sftp-device-${UUID.randomUUID()}-$suffix"
    private fun failure(expected: RemoteFailure, block: () -> Unit) {
        val error = assertThrows(RemoteStorageException::class.java, block)
        assertEquals(expected, error.failure)
    }
    private fun digest(bytes: ByteArray) = RemoteDigest(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })

    @Test fun unknownAndChangedHostKeysAbortBeforeAuthenticationAndEraseCredentials() {
        val unknownSecret = password()
        val unknown = assertThrows(RemoteStorageException::class.java) {
            factory.connect(profile.copy(trustedHostKey = null), unknownSecret, RemoteCancellation())
        }
        assertEquals(RemoteFailure.IDENTITY_REQUIRED, unknown.failure)
        assertEquals(profile.trustedHostKey, unknown.observedHostKey)
        assertTrue(unknownSecret.value.all { it == '\u0000' })
        val changedSecret = password()
        failure(RemoteFailure.IDENTITY_CHANGED) {
            factory.connect(profile.copy(trustedHostKey = "SHA256:" + "A".repeat(43)), changedSecret, RemoteCancellation())
        }
        assertTrue(changedSecret.value.all { it == '\u0000' })
    }

    @Test fun exclusiveUploadOffsetReadListAndAtomicPublicationPreserveCollisionsAndProviders() {
        val providers = Security.getProviders().map { it.name to it.javaClass.name }
        open().use { connection ->
            assertTrue(connection.capabilities.atomicPublish)
            assertTrue(connection.capabilities.encrypted)
            assertTrue(connection.residualNames.isEmpty())
            val source = own("staging.bin")
            val destination = own("published.bin")
            val collision = own("collision.bin")
            val data = ByteArray(100_123) { ((it * 31) % 251).toByte() }
            val sentinel = "existing destination stays intact".toByteArray()
            connection.createExclusive(source).use { it.write(data) }
            connection.createExclusive(collision).use { it.write(sentinel) }
            failure(RemoteFailure.ALREADY_EXISTS) { connection.createExclusive(source).close() }
            assertArrayEquals(data, connection.openRead(source).use { it.readBytes() })
            assertArrayEquals(data.copyOfRange(91_337, data.size), connection.openRead(source, 91_337).use { it.readBytes() })
            connection.publishNoReplace(source, destination, digest(data))
            assertArrayEquals(data, connection.openRead(destination).use { it.readBytes() })
            assertArrayEquals(data, connection.openRead(source).use { it.readBytes() })
            failure(RemoteFailure.ALREADY_EXISTS) { connection.publishNoReplace(source, collision, digest(data)) }
            assertArrayEquals(sentinel, connection.openRead(collision).use { it.readBytes() })
            assertTrue(connection.list(1000).any { it.name == destination && it.size == data.size.toLong() && it.regularFile })
            assertEquals(data.size.toLong(), connection.stat(destination)!!.size)
        }
        assertEquals(providers, Security.getProviders().map { it.name to it.javaClass.name })
    }

    @Test fun changedStagingDigestRejectsPublicationBeforeCreatingDestination() {
        open().use { connection ->
            val source = own("changed.bin")
            val destination = own("unpublished.bin")
            connection.createExclusive(source).use { it.write("changed".toByteArray()) }
            failure(RemoteFailure.CONFLICT) { connection.publishNoReplace(source, destination, digest("before!".toByteArray())) }
            assertNull(connection.stat(destination))
            assertArrayEquals("changed".toByteArray(), connection.openRead(source).use { it.readBytes() })
        }
    }

    @Test fun traversalAndSymlinkAreRejectedAndListingNeverSilentlyTruncates() {
        open().use { connection ->
            val source = own("list.bin")
            connection.createExclusive(source).use { it.write(42) }
            failure(RemoteFailure.INVALID_PATH) { connection.openRead("../outside/sentinel.txt") }
            failure(RemoteFailure.INVALID_PATH) { connection.openRead("outside-link") }
            failure(RemoteFailure.ALREADY_EXISTS) { connection.createExclusive("outside-link") }
            failure(RemoteFailure.CONFLICT) { connection.list(1) }
            assertFalse(connection.stat("outside-link")!!.regularFile)
        }
    }

    @Test fun cancellationInterruptsStalledHandshakeWithoutWaitingForTimeout() {
        val cancellation = RemoteCancellation()
        val result = AtomicReference<Throwable>()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try { SftpRemoteConnectionFactory(15_000, 30_000).connect(profile.copy(port = 22235), password(), cancellation).close() }
            catch (error: Throwable) { result.set(error) }
            finally { stopped.countDown() }
        }
        thread.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(250)
        cancellation.cancel()
        assertTrue("Cancellation did not stop stalled handshake", stopped.await(3, TimeUnit.SECONDS))
        assertEquals(RemoteFailure.CANCELLED, (result.get() as RemoteStorageException).failure)
    }

    @Test fun stalledHandshakeTimesOutWithoutCredentialLeak() {
        val secret = password()
        val started = System.nanoTime()
        failure(RemoteFailure.CONNECTION) {
            SftpRemoteConnectionFactory(700, 700).connect(profile.copy(port = 22235), secret, RemoteCancellation())
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
        assertTrue(secret.value.all { it == '\u0000' })
    }

    @Test fun encryptedOpenSshRsaPrivateKeyAuthenticatesWithoutProviderMutation() {
        val providers = Security.getProviders().map { it.name to it.javaClass.name }
        val key = Base64.getDecoder().decode(requireNotNull(args.getString("sftpEncryptedKey")))
        val credential = RemoteCredentials.PrivateKey(key, "fixture-key-passphrase".toCharArray())
        factory.connect(profile.copy(authKind = RemoteAuthKind.PRIVATE_KEY), credential, RemoteCancellation()).use {
            assertTrue(it.capabilities.atomicPublish)
            assertEquals(profile.trustedHostKey, it.identity)
        }
        assertTrue(key.all { it == 0.toByte() })
        assertTrue(credential.passphrase.all { it == '\u0000' })
        assertEquals(providers, Security.getProviders().map { it.name to it.javaClass.name })
    }

    @Test fun serverWithoutHardlinkNeverAdvertisesAtomicPublication() {
        factory.connect(profile.copy(port = 22236), password(), RemoteCancellation()).use {
            assertFalse(it.capabilities.atomicPublish)
            failure(RemoteFailure.UNSUPPORTED) { it.publishNoReplace("never-created", "never-published", digest(byteArrayOf())) }
            assertNull(it.stat("never-created"))
        }
    }

    @Test fun rejectedPasswordReturnsAuthenticationFailureAndErasesSecret() {
        val wrong = password("wrong-fixture-password")
        failure(RemoteFailure.AUTHENTICATION) { factory.connect(profile, wrong, RemoteCancellation()) }
        assertTrue(wrong.value.all { it == '\u0000' })
    }

    @Test fun cancelledReadPreservesUploadedBytesAndSourceStaging() {
        val cancellation = RemoteCancellation()
        val source = own("cancel-read.bin")
        val bytes = ByteArray(98_304) { it.toByte() }
        open(cancellation).use { connection ->
            connection.createExclusive(source).use { it.write(bytes) }
            val input = connection.openRead(source)
            assertEquals(0, input.read())
            cancellation.cancel()
            failure(RemoteFailure.CANCELLED) { input.read() }
            runCatching { input.close() }
        }
        open().use { assertArrayEquals(bytes, it.openRead(source).use { input -> input.readBytes() }) }
    }
}
