package com.ugallery.core.remotestorage.smb

import com.ugallery.core.remotestorage.*
import org.junit.Assert.*
import org.junit.Test

class SmbPathsTest {
    @Test
    fun rootAndChildRemainWithinConfiguredDirectory() {
        assertEquals("photos\\2026", SmbPaths.root("/photos/2026/"))
        assertEquals("photos\\2026\\Málaga.zip", SmbPaths.child("photos\\2026", "Málaga.zip"))
        assertEquals("", SmbPaths.root("/"))
    }

    @Test
    fun traversalUncAdsAndAmbiguousNamesAreRejected() {
        listOf("a/../b", "a/./b", "\\\\server\\share", "C:/photo", "a/trailing.").forEach {
            assertThrows(IllegalArgumentException::class.java) { SmbPaths.root(it) }
        }
        listOf("../a", "\\host", "data:stream", "a?", "a ", "a.", "a/b", "a\\b").forEach {
            assertThrows(IllegalArgumentException::class.java) { SmbPaths.child("owned", it) }
        }
    }

    @Test
    fun cancellationBeforeConnectClearsPasswordAndNeverContactsHost() {
        val cancellation = RemoteCancellation().apply { cancel() }
        val secret = RemoteCredentials.Password("fixture".toCharArray())
        val error =
            assertThrows(RemoteStorageException::class.java) {
                SmbRemoteConnectionFactory()
                    .connect(
                        RemoteProfile(
                            name = "test",
                            protocol = RemoteProtocol.SMB,
                            host = "127.0.0.1",
                            port = 9,
                            username = "test",
                            root = "",
                            share = "backup",
                        ),
                        secret,
                        cancellation,
                    )
            }
        assertEquals(RemoteFailure.CANCELLED, error.failure)
        assertTrue(secret.value.all { it == '\u0000' })
    }
}
