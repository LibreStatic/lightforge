package com.librestatic.lightforge.core.remotestorage.sftp

import com.librestatic.lightforge.core.remotestorage.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class SftpSafetyTest {
    @Test fun rootAndChildAreLiteralAndConfined() {
        assertEquals("/data/archive", SftpPaths.root("/data//archive/"))
        assertEquals("/data/archive/backup 2026.zip", SftpPaths.child("/data/archive", "backup 2026.zip"))
        for (bad in listOf("relative", "/a/../b", "/a/./b", "/a\\b", "/a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { SftpPaths.root(bad) }
        }
        for (bad in listOf("../a", "a/b", "a\\b", "*", "?", ".", "..", "name ")) {
            assertThrows(IllegalArgumentException::class.java) { SftpPaths.child("/data/archive", bad) }
        }
    }

    @Test fun hostPinRequiresAnExplicitExactFingerprint() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val unknown = SftpHostKeyVerifier(null)
        assertFalse(unknown.verify("fixture", 22234, pair.public))
        assertTrue(unknown.rejected)
        assertTrue(unknown.observed!!.startsWith("SHA256:"))
        assertTrue(SftpHostKeyVerifier(unknown.observed).verify("fixture", 22234, pair.public))
        assertFalse(SftpHostKeyVerifier("SHA256:" + "A".repeat(43)).verify("fixture", 22234, pair.public))
    }

    @Test fun cancellationClosesSocketBeforeConnectAndRejectsNewSockets() {
        val cancellation = RemoteCancellation()
        val factory = SftpSocketFactory(cancellation)
        val socket = factory.createSocket()
        assertFalse(socket.isConnected)
        cancellation.cancel()
        assertTrue(socket.isClosed)
        val error = assertThrows(RemoteStorageException::class.java) { factory.createSocket() }
        assertEquals(RemoteFailure.CANCELLED, error.failure)
        factory.close()
    }

    @Test fun credentialsAreErasedEvenWhenValidationRejectsTheProfile() {
        val secret = RemoteCredentials.Password("discard-me".toCharArray())
        val profile = RemoteProfile(name = "fixture", protocol = RemoteProtocol.SFTP, host = "", username = "u", root = "/")
        assertThrows(RemoteStorageException::class.java) {
            SftpRemoteConnectionFactory().connect(profile, secret, RemoteCancellation())
        }
        assertTrue(secret.value.all { it == '\u0000' })
    }

    @Test fun digestHasExpectedSizeAndSha256() {
        assertEquals(RemoteDigest(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            SftpRemoteConnection.digest("abc".toByteArray()))
    }
}
