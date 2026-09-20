package com.ugallery.core.security

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Small synthetic fixtures. Only ciphertext reaches temporary files; all plaintext stays in RAM. */
class PrivateAlbumV3Test {
    private val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
    private val destinationKey = SecretKeySpec(ByteArray(32) { (it + 77).toByte() }, "AES")
    private val mime = "image/jpeg"
    private val segmentSize = 4096
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun plaintext(size: Int) = ByteArray(size) { (it * 31 + it / 173).toByte() }

    @Test
    fun emptySmallAndMultipleSegmentsBindBigEndianLengthAndRoundTrip() {
        for (size in listOf(0, 17, 20_017)) {
            val plain = plaintext(size)
            val output = ByteArrayOutputStream()
            val metadata = PrivateAlbumCrypto.encryptSizedStream(
                plain.inputStream(), output, key, mime, plain.size.toLong(), chunkSize = segmentSize,
            )
            val bytes = output.toByteArray()
            val header = inspectHeader(bytes)
            assertEquals(segmentSize, header.segmentSize)
            assertEquals(mime, header.mime)
            assertEquals(size.toLong(), header.size)
            assertTrue(bytes.size > header.bytes.size)
            assertEquals(size.toLong(), metadata.totalEncryptedSize)
            assertArrayEquals(digest(plain), metadata.sha256)
            val restored = ByteArrayOutputStream()
            assertArrayEquals(digest(plain), PrivateAlbumCrypto.decryptStream(
                bytes.inputStream(), restored, key, digest(plain),
            ))
            assertArrayEquals(plain, restored.toByteArray())
        }
    }

    @Test
    fun sizeHeaderTamperTruncationAndAppendFailDecryptAndViewerOpen() {
        val plain = plaintext(12_013)
        val original = sized(plain)
        val header = inspectHeader(original)
        val changedLength = original.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putLong(header.bytes.size - 8, plain.size.toLong() + 1)
        }
        for (changed in listOf(changedLength, original.copyOf(original.size - 1), original + byteArrayOf(1))) {
            assertThrows(Exception::class.java) {
                PrivateAlbumCrypto.decryptStream(changed.inputStream(), ByteArrayOutputStream(), key, digest(plain))
            }
            ciphertextFile(changed) { file ->
                assertThrows(Exception::class.java) {
                    PrivateSeekableReader.open(file, key, digest(plain)).close()
                }
            }
        }
        assertEquals(plain.size.toLong(), inspectHeader(original).size)
    }

    @Test
    fun validAeadWithIncorrectAuthenticatedDeclaredLengthIsRejected() {
        val plain = plaintext(10_017)
        for (declared in listOf(plain.size.toLong() - 1, plain.size.toLong() + 1)) {
            val bytes = handcrafted(plain, declared)
            val header = inspectHeader(bytes)
            // Prove the fixture has valid AEAD, including its deliberately wrong length in AAD.
            val raw = PrivateAlbumCrypto.primitive(key, segmentSize)
                .newDecryptingStream(bytes.copyOfRange(header.bytes.size, bytes.size).inputStream(), header.bytes)
                .use { it.readBytes() }
            assertArrayEquals(plain, raw)
            assertThrows(Exception::class.java) {
                PrivateAlbumCrypto.decryptStream(bytes.inputStream(), ByteArrayOutputStream(), key, digest(plain))
            }
            ciphertextFile(bytes) { file ->
                assertThrows(Exception::class.java) {
                    PrivateSeekableReader.open(file, key, digest(plain)).close()
                }
            }
        }
    }

    @Test
    fun sizedWriterRejectsShortLongAndNegativeDeclarationsWithoutReturningMetadata() {
        val plain = plaintext(73)
        for (declared in listOf(72L, 74L, -1L)) {
            val output = ByteArrayOutputStream()
            assertThrows(Exception::class.java) {
                PrivateAlbumCrypto.encryptSizedStream(plain.inputStream(), output, key, mime,
                    declared, chunkSize = segmentSize)
            }
            // Failed ciphertext is deliberately not treated as a committed publication.
        }
        for ((actual, declared) in listOf(0 to 1L, 1 to 0L)) {
            assertThrows(Exception::class.java) {
                PrivateAlbumCrypto.encryptSizedStream(plaintext(actual).inputStream(), ByteArrayOutputStream(),
                    key, mime, declared, chunkSize = segmentSize)
            }
        }
    }

    @Test
    fun reencryptV2ByteArrayAndFileSourcesProduceV3AndPreserveOriginalCiphertext() {
        val plain = plaintext(21_033)
        val original = ByteArrayOutputStream().also {
            PrivateAlbumCrypto.encryptStream(plain.inputStream(), it, key, mime, chunkSize = segmentSize)
        }.toByteArray()
        assertEquals(2, original[4].toInt())
        val before = digest(original)
        fun checkOutput(output: ByteArrayOutputStream, metadata: PrivateAlbumCrypto.ContainerMetadata) {
            assertEquals(plain.size.toLong(), inspectHeader(output.toByteArray()).size)
            assertEquals(plain.size.toLong(), metadata.totalEncryptedSize)
            assertArrayEquals(digest(plain), metadata.sha256)
            val restored = ByteArrayOutputStream()
            PrivateAlbumCrypto.decryptStream(output.toByteArray().inputStream(), restored, destinationKey, digest(plain))
            assertArrayEquals(plain, restored.toByteArray())
        }
        val memoryOutput = ByteArrayOutputStream()
        checkOutput(memoryOutput, PrivateAlbumCrypto.reencryptStream(
            original.inputStream(), memoryOutput, key, destinationKey, mime, digest(plain),
        ))
        ciphertextFile(original) { file ->
            val fileOutput = ByteArrayOutputStream()
            val metadata = FileInputStream(file).use {
                PrivateAlbumCrypto.reencryptStream(it, fileOutput, key, destinationKey, mime, digest(plain))
            }
            checkOutput(fileOutput, metadata)
            assertArrayEquals(before, digest(file.readBytes()))
        }
        assertArrayEquals(before, digest(original))
    }

    @Test
    fun reencryptRejectsWrongTrustedDigestBeforeWritingDestinationHeader() {
        val original = ByteArrayOutputStream().also {
            PrivateAlbumCrypto.encryptStream(plaintext(87).inputStream(), it, key, mime, chunkSize = segmentSize)
        }.toByteArray()
        val output = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            PrivateAlbumCrypto.reencryptStream(original.inputStream(), output, key, destinationKey, mime, ByteArray(32))
        }
        assertEquals(0, output.size())
    }

    @Test
    fun v3ViewerRandomSeekAndEofUseAuthenticatedLengthAndPreserveCiphertext() {
        val plain = plaintext(90_017)
        ciphertextFile(sized(plain)) { file ->
            val before = digest(file.readBytes())
            PrivateSeekableReader.open(file, key, digest(plain)).use { reader ->
                assertEquals(plain.size.toLong(), reader.plaintextBytes)
                for ((start, count) in listOf(0 to 1, 4_001 to 9_001, 89_999 to 18, 11 to 80_000, 4_095 to 2)) {
                    val actual = ByteArray(count)
                    var done = 0
                    while (done < count) {
                        val n = reader.readAt(start.toLong() + done, actual, done, count - done)
                        assertTrue("Forward progress in requested range", n > 0)
                        done += n
                    }
                    assertArrayEquals(plain.copyOfRange(start, start + count), actual)
                }
                val untouched = byteArrayOf(55)
                assertEquals(-1, reader.readAt(plain.size.toLong(), untouched, 0, 1))
                assertEquals(-1, reader.readAt(Long.MAX_VALUE, untouched, 0, 1))
                assertEquals(0, reader.readAt(0, ByteArray(0), 0, 0))
                assertArrayEquals(byteArrayOf(55), untouched)
            }
            assertThrows(Exception::class.java) {
                PrivateSeekableReader.open(file, key, ByteArray(32)).close()
            }
            assertArrayEquals(before, digest(file.readBytes()))
        }
    }

    @Test
    fun emptyV3ViewerAuthenticatesTerminalSegmentBeforeReturningZeroLength() {
        val bytes = sized(ByteArray(0))
        ciphertextFile(bytes) { file ->
            PrivateSeekableReader.open(file, key, digest(ByteArray(0))).use { reader ->
                assertEquals(0L, reader.plaintextBytes)
                assertEquals(-1, reader.readAt(0, ByteArray(1), 0, 1))
            }
        }
        val tampered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        ciphertextFile(tampered) { file ->
            assertThrows(Exception::class.java) {
                PrivateSeekableReader.open(file, key, digest(ByteArray(0))).close()
            }
        }
    }

    private fun sized(plain: ByteArray): ByteArray = ByteArrayOutputStream().also {
        PrivateAlbumCrypto.encryptSizedStream(plain.inputStream(), it, key, mime,
            plain.size.toLong(), chunkSize = segmentSize)
    }.toByteArray()

    private data class Header(val bytes: ByteArray, val mime: String, val segmentSize: Int, val size: Long)
    private fun inspectHeader(bytes: ByteArray): Header {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x55475043, buffer.int)
        assertEquals(3, buffer.get().toInt())
        val segment = buffer.int
        buffer.position(21)
        val mimeSize = buffer.short.toInt() and 0xffff
        assertTrue(mimeSize in 1..255)
        val mimeBytes = ByteArray(mimeSize).also(buffer::get)
        val length = buffer.long
        assertEquals(23 + mimeSize + 8, buffer.position())
        return Header(bytes.copyOfRange(0, buffer.position()), mimeBytes.toString(Charsets.UTF_8), segment, length)
    }

    private fun handcrafted(plain: ByteArray, declared: Long): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(23 + mimeBytes.size + 8).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x55475043).put(3.toByte()).putInt(segmentSize)
            .put(ByteArray(12) { (it + 19).toByte() }).putShort(mimeBytes.size.toShort())
            .put(mimeBytes).putLong(declared).array()
        val output = ByteArrayOutputStream()
        output.write(header)
        PrivateAlbumCrypto.primitive(key, segmentSize).newEncryptingStream(output, header).use { it.write(plain) }
        return output.toByteArray()
    }

    private fun ciphertextFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("ugallery-v3-test-", ".ugpc")
        try { file.writeBytes(bytes); block(file) }
        finally { check(file.delete()) { "Owned ciphertext fixture cleanup failed" } }
    }
}
