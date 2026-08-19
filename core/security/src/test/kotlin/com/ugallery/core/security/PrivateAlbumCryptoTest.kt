package com.ugallery.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator

class PrivateAlbumCryptoTest {

    private fun generateTestKey(): javax.crypto.SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    @Test
    fun encryptDecrypt_smallImage_roundTrip() {
        val plaintext = ByteArray(1024) { it.toByte() }
        val dataKey = generateTestKey()

        val encrypted = ByteArrayOutputStream()
        val metadata = PrivateAlbumCrypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encrypted,
            dataKey = dataKey,
            originalMimeType = "image/jpeg",
            chunkSize = 256, // small chunk to test multi-chunk
        )

        assertEquals(4, metadata.totalChunks) // 1024 / 256 = 4 chunks
        assertEquals("image/jpeg", metadata.originalMimeType)
        assertEquals(1024, metadata.totalEncryptedSize)

        val decrypted = ByteArrayOutputStream()
        val sha = PrivateAlbumCrypto.decryptStream(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
            dataKey = dataKey,
        )

        assertArrayEquals(plaintext, decrypted.toByteArray())
        assertArrayEquals(metadata.sha256, sha)
    }

    @Test
    fun encryptDecrypt_singleChunk_roundTrip() {
        val plaintext = "Hello Private Album".toByteArray()
        val dataKey = generateTestKey()

        val encrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encrypted,
            dataKey = dataKey,
            originalMimeType = "image/png",
            chunkSize = 1024 * 1024, // large chunk = single chunk
        )

        val decrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.decryptStream(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
            dataKey = dataKey,
        )

        assertArrayEquals(plaintext, decrypted.toByteArray())
    }

    @Test
    fun encryptDecrypt_emptyInput_throws() {
        val dataKey = generateTestKey()
        val encrypted = ByteArrayOutputStream()

        // Empty input should produce 0 chunks and the encrypt should still write a valid container
        val metadata = PrivateAlbumCrypto.encryptStream(
            input = ByteArrayInputStream(ByteArray(0)),
            output = encrypted,
            dataKey = dataKey,
            originalMimeType = "image/jpeg",
        )

        assertEquals(0, metadata.totalChunks)

        // Decryption of empty container should return empty
        val decrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.decryptStream(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
            dataKey = dataKey,
        )

        assertEquals(0, decrypted.size())
    }

    @Test
    fun encryptDecrypt_largeInput_multiChunk_roundTrip() {
        val plaintext = ByteArray(3 * 1024 * 1024 + 500) { (it % 256).toByte() } // 3.5 MB
        val dataKey = generateTestKey()

        val encrypted = ByteArrayOutputStream()
        val metadata = PrivateAlbumCrypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encrypted,
            dataKey = dataKey,
            originalMimeType = "video/mp4",
            chunkSize = 1024 * 1024, // 1 MB chunks
        )

        assertEquals(4, metadata.totalChunks) // 3 full + 1 partial
        assertEquals(plaintext.size.toLong(), metadata.totalEncryptedSize)

        val decrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.decryptStream(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
            dataKey = dataKey,
        )

        assertArrayEquals(plaintext, decrypted.toByteArray())
    }

    @Test(expected = Exception::class)
    fun decrypt_tamperedContainer_throws() {
        val plaintext = ByteArray(512) { it.toByte() }
        val dataKey = generateTestKey()

        val encrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encrypted,
            dataKey = dataKey,
            originalMimeType = "image/jpeg",
            chunkSize = 256,
        )

        // Tamper with a byte in the middle
        val tampered = encrypted.toByteArray()
        tampered[tampered.size - 50] = (tampered[tampered.size - 50].toInt() + 1).toByte()

        val decrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.decryptStream(
            input = ByteArrayInputStream(tampered),
            output = decrypted,
            dataKey = dataKey,
        )
    }

    @Test
    fun encryptDecrypt_dataKeyEncryption_roundTrip() {
        // We can't use Keystore in unit tests, so test with a regular key
        val masterKey = generateTestKey()
        val dataKey = generateTestKey()

        val encrypted = PrivateAlbumCrypto.encryptDataKey(dataKey, masterKey)
        val decrypted = PrivateAlbumCrypto.decryptDataKey(encrypted, masterKey)

        assertArrayEquals(dataKey.encoded, decrypted.encoded)
    }

    @Test
    fun encrypt_producesLargerOutput() {
        val plaintext = ByteArray(2048) { it.toByte() }
        val dataKey = generateTestKey()

        val encrypted = ByteArrayOutputStream()
        PrivateAlbumCrypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encrypted,
            dataKey = dataKey,
            originalMimeType = "image/jpeg",
            chunkSize = 512,
        )

        // Encrypted should be larger due to headers, nonces, tags
        assertTrue("Encrypted should be larger than plaintext", encrypted.size() > plaintext.size)
    }
}
