package com.librestatic.lightforge.core.remotestorage

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class RemoteStorageTest {
    @Test
    fun childPathsRejectTraversalAndSmbAlternateStreams() {
        listOf("..", ".", "a/b", "a\\b", "a:b", "a*", "a?", "a.", "a ", "\u0000").forEach {
            assertTrue(it, runCatching { RemoteNames.requireChild(it) }.isFailure)
        }
        assertEquals("Álbum 2026.zip", RemoteNames.requireChild("Álbum 2026.zip"))
    }

    @Test
    fun cancellationClosesBothAlreadyAndLateRegisteredSockets() {
        val token = RemoteCancellation()
        var closed = 0
        token.register(Closeable { closed++ })
        token.cancel()
        token.cancel()
        assertEquals(1, closed)
        assertTrue(runCatching { token.register(Closeable { closed++ }) }.isFailure)
        assertEquals(2, closed)
        assertTrue(runCatching { token.check() }.exceptionOrNull() is RemoteStorageException)
    }

    @Test
    fun unregisteredSocketNotClosedAgain() {
        val token = RemoteCancellation()
        var closed = 0
        val socket = Closeable { closed++ }
        token.register(socket)
        token.unregister(socket)
        token.cancel()
        assertEquals(0, closed)
    }

    @Test
    fun secretsAreRedactedAndZeroed() {
        val secret = RemoteCredentials.Password("private secret".toCharArray())
        assertFalse(secret.toString().contains("private secret"))
        secret.close()
        assertTrue(secret.value.all { it == '\u0000' })
        val key = RemoteCredentials.PrivateKey(byteArrayOf(1, 2), charArrayOf('a'))
        key.close()
        assertTrue(key.bytes.all { it == 0.toByte() })
        assertEquals('\u0000', key.passphrase[0])
    }

    @Test
    fun profileAllowsOwnRemoteHostAndIpv6ButRejectsEmbeddedUrls() {
        val profile =
            RemoteProfile(
                name = "My server",
                protocol = RemoteProtocol.SFTP,
                host = "nas.example.test",
                username = "me",
                root = "/backups",
            )
        profile.validate()
        profile.copy(host = "2001:db8::1").validate()
        listOf("ssh://host", "user@host", "host/path", "host\n").forEach {
            assertTrue(runCatching { profile.copy(host = it).validate() }.isFailure)
        }
        assertTrue(runCatching { profile.copy(root = "/root/../other").validate() }.isFailure)
    }

    @Test
    fun invalidDigestsRejected() {
        assertTrue(runCatching { RemoteDigest(-1, "0".repeat(64)) }.isFailure)
        assertTrue(runCatching { RemoteDigest(1, "oops") }.isFailure)
        RemoteDigest(0, "0".repeat(64))
    }
}
