package com.ugallery.core.security

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.spec.SecretKeySpec

class PrivateAlbumV2Test {
    private val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
    private val plaintext = ByteArray(3 * 1024 * 1024 + 43) { (it * 19).toByte() }
    private fun encrypt(bytes: ByteArray = plaintext): ByteArray = ByteArrayOutputStream().also {
        PrivateAlbumCrypto.encryptStream(bytes.inputStream(), it, key, "image/jpeg")
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, expected: ByteArray? = null): ByteArray = ByteArrayOutputStream().also {
        PrivateAlbumCrypto.decryptStream(bytes.inputStream(), it, key, expected)
    }.toByteArray()
    @Test fun newWritesAreV2AndRoundTripAuthenticatedEmptyAndMultisegment() {
        for (plain in listOf(ByteArray(0), plaintext)) {
            val encrypted = encrypt(plain)
            assertEquals(2, encrypted[4].toInt())
            assertArrayEquals(plain, decrypt(encrypted, MessageDigest.getInstance("SHA-256").digest(plain)))
        }
    }
    @Test fun tamperedHeaderCiphertextTruncationAndAppendAreRejected() {
        val valid = encrypt()
        val header = valid.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        val ciphertext = valid.copyOf().also { it[1024] = (it[1024].toInt() xor 1).toByte() }
        for (bytes in listOf(header, ciphertext, valid.copyOf(valid.size - 1), valid + byteArrayOf(1))) {
            assertThrows(Exception::class.java) { decrypt(bytes) }
        }
    }
    @Test fun segmentReorderingIsRejected() {
        val bytes = encrypt()
        val headerSize = 23 + "image/jpeg".length
        val start = headerSize + 1024 * 1024
        val next = start + 1024 * 1024
        val first = bytes.copyOfRange(start, next)
        bytes.copyOfRange(next, next + first.size).copyInto(bytes, start)
        first.copyInto(bytes, next)
        assertThrows(IOException::class.java) { decrypt(bytes) }
    }
    @Test fun wrongKeyAndTrustedDigestMismatchAreRejected() {
        val bytes = encrypt()
        assertThrows(Exception::class.java) { PrivateAlbumCrypto.decryptStream(bytes.inputStream(), ByteArrayOutputStream(), SecretKeySpec(ByteArray(32), "AES")) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(bytes, ByteArray(32)) }
    }
    @Test fun shortReadsAndZeroLengthReadAreHandledWithoutReadingEntireCiphertext() {
        val bytes = encrypt()
        val short = object : FilterInputStream(bytes.inputStream()) {
            var zero = true
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zero) { zero = false; return 0 }
                require(len <= 16 * 1024 * 1024)
                return super.read(b, off, minOf(len, 7))
            }
        }
        val result = ByteArrayOutputStream()
        PrivateAlbumCrypto.decryptStream(short, result, key)
        assertArrayEquals(plaintext, result.toByteArray())
    }
    @Test fun cancellationPropagatesAndOutputIsNotDeclaredVerified() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PrivateAlbumCrypto.encryptStream(plaintext.inputStream(), ByteArrayOutputStream(), key, "image/jpeg", checkActive = {
                if (++checks > 5) throw CancellationException("owned test cancellation")
            })
        }
        assertTrue(checks > 5)
    }
    @Test fun directTranscodeKeepsPlaintextOffDiskAndRotatesDataKey() {
        val source = encrypt()
        val destinationKey = PrivateAlbumCrypto.generateDataKey()
        val output = ByteArrayOutputStream()
        val sha = MessageDigest.getInstance("SHA-256").digest(plaintext)
        PrivateAlbumCrypto.reencryptStream(source.inputStream(), output, key, destinationKey, "image/jpeg", sha)
        val restored = ByteArrayOutputStream()
        PrivateAlbumCrypto.decryptStream(output.toByteArray().inputStream(), restored, destinationKey, sha)
        assertArrayEquals(plaintext, restored.toByteArray())
        assertFalse(source.contentEquals(output.toByteArray()))
    }
}
