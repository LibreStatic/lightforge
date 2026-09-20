package com.ugallery.core.remotestorage

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RemoteCredentialVaultDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun passwordAndKeyPersistAcrossInstancesWithoutPlaintext() {
        val id = UUID.randomUUID().toString()
        val vault = AndroidRemoteCredentialVault(context)
        try {
            RemoteCredentials.Password("secret-marker-é-123".toCharArray()).use {
                vault.save(id, it)
            }
            val bytes = File(context.noBackupFilesDir, "remote-credentials/$id.bin").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("secret-marker"))
            AndroidRemoteCredentialVault(context).load(id)!!.use {
                assertArrayEquals(
                    "secret-marker-é-123".toCharArray(),
                    (it as RemoteCredentials.Password).value,
                )
            }
            RemoteCredentials.PrivateKey(byteArrayOf(9, 8, 7), charArrayOf('ñ')).use {
                vault.save(id, it)
            }
            vault.load(id)!!.use {
                it as RemoteCredentials.PrivateKey
                assertArrayEquals(byteArrayOf(9, 8, 7), it.bytes)
                assertArrayEquals(charArrayOf('ñ'), it.passphrase)
            }
        } finally {
            vault.delete(id)
        }
        assertNull(vault.load(id))
    }

    @Test
    fun tamperingAndCrossProfileSubstitutionFailAuthentication() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val vault = AndroidRemoteCredentialVault(context)
        try {
            RemoteCredentials.Password(charArrayOf('x')).use { vault.save(a, it) }
            val source = File(context.noBackupFilesDir, "remote-credentials/$a.bin")
            val other = File(context.noBackupFilesDir, "remote-credentials/$b.bin")
            source.copyTo(other)
            assertTrue(runCatching { vault.load(b) }.isFailure)
            val bytes = source.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            source.writeBytes(bytes)
            assertTrue(runCatching { vault.load(a) }.isFailure)
        } finally {
            vault.delete(a)
            vault.delete(b)
        }
    }
}
