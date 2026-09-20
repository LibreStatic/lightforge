package com.ugallery.core.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.CancellationException

class PrivatePortableArchiveTest {
    @get:Rule val directory = TemporaryFolder()
    private val password get() = "Correct horse private album".toCharArray()
    private val plaintext = "A private original that must never appear in the public backup".toByteArray()
    private fun source(): PrivatePortableArchive.Source {
        val file = directory.newFile()
        val key = PrivateAlbumCrypto.generateDataKey()
        val metadata = FileOutputStream(file).use { PrivateAlbumCrypto.encryptStream(plaintext.inputStream(), it, key, "image/jpeg") }
        return PrivatePortableArchive.Source(PrivatePortableArchive.Metadata("secret-cat-name.jpg", "image/jpeg", "image", 32, 48, 0, 1234), file, key, metadata.sha256)
    }
    private fun archive(source: PrivatePortableArchive.Source): ByteArray = ByteArrayOutputStream().also {
        PrivatePortableArchive.write(listOf(source), it, password)
    }.toByteArray()
    @Test fun realPasswordArchiveRestoresUsingNewItemKeyWithoutMasterKeyOrPlaintextDisclosure() {
        val source = source()
        val before = PrivatePortableArchive.hash(source.ciphertext)
        val encrypted = archive(source)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("secret-cat-name"))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(String(plaintext)))
        val restored = PrivatePortableArchive.read(encrypted.inputStream(), password, directory.newFolder())
        assertEquals(1, restored.size)
        assertEquals(source.metadata, restored.single().metadata)
        assertFalse(source.dataKey.encoded.contentEquals(restored.single().dataKey.encoded))
        val output = ByteArrayOutputStream()
        FileInputStream(restored.single().ciphertext).use { PrivateAlbumCrypto.decryptStream(it, output, restored.single().dataKey, source.expectedSha256) }
        assertArrayEquals(plaintext, output.toByteArray())
        assertArrayEquals(before, PrivatePortableArchive.hash(source.ciphertext))
    }
    @Test fun wrongPasswordCorruptionAndTruncationLeaveNoStagedFiles() {
        val encrypted = archive(source())
        for ((bytes, pass) in listOf(encrypted to "This is the wrong password".toCharArray(), encrypted.copyOf(encrypted.size - 1) to password,
            encrypted.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() } to password)) {
            val staging = directory.newFolder()
            assertThrows(Exception::class.java) { PrivatePortableArchive.read(bytes.inputStream(), pass, staging) }
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun invalidKdfParametersAreRejectedBeforeExpensiveWork() {
        val bytes = archive(source())
        java.nio.ByteBuffer.wrap(bytes, 5, 4).putInt(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { PrivatePortableArchive.read(bytes.inputStream(), password, directory.newFolder()) }
    }
    @Test fun trustedSourceDigestMismatchDoesNotProduceSuccessfulBackup() {
        val source = source().copy(expectedSha256 = ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) { archive(source) }
    }
    @Test fun cancellationDuringRestoreRemovesEveryOwnedStagingFileAndPreservesSource() {
        val source = source()
        val encrypted = archive(source)
        val before = PrivatePortableArchive.hash(source.ciphertext)
        val staging = directory.newFolder()
        var count = 0
        assertThrows(CancellationException::class.java) {
            PrivatePortableArchive.read(encrypted.inputStream(), password, staging, { if (++count >= 7) throw CancellationException("test") })
        }
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertArrayEquals(before, PrivatePortableArchive.hash(source.ciphertext))
    }
}
