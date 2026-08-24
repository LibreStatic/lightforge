package com.ugallery.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked authenticated encryption container for private album media.
 *
 * Format:
 *   [Magic(4)] [Version(1)] [ChunkSize(4)] [IVBase(12)]
 *   [Chunk 0: Nonce(12) | CipherLen(4) | Ciphertext(N) | Tag(16)]
 *   [Chunk 1: ...]
 *   ...
 *   [Footer: TotalEncryptedSize(8) | MimeTypeLen(2) | MimeType(N) | SHA256(32)]
 *
 * Each chunk stores its own ciphertext length so the last (partial) chunk
 * is handled correctly without seeking. Each chunk is independently
 * decryptable for video seek.
 */
object PrivateAlbumCrypto {

    private const val MAGIC = "UGPC"
    private const val VERSION: Byte = 1
    const val DEFAULT_CHUNK_SIZE = 1 * 1024 * 1024 // 1 MiB
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val FOOTER_SHA256_SIZE = 32
    private const val KEYSTORE_NAME = "AndroidKeyStore"
    const val MASTER_KEY_ALIAS = "ugallery.privatealbum.master"
    const val MASTER_KEY_SIZE = 256

    data class ContainerMetadata(
        val chunkSize: Int,
        val totalChunks: Int,
        val ivBase: ByteArray,
        val totalEncryptedSize: Long,
        val originalMimeType: String,
        val sha256: ByteArray,
    )

    data class EncryptedDataKey(
        val encryptedKey: ByteArray,
        val iv: ByteArray,
    )

    fun generateDataKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(MASTER_KEY_SIZE)
        return keyGen.generateKey()
    }

    fun encryptDataKey(dataKey: SecretKey, masterKey: SecretKey): EncryptedDataKey {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore AES-GCM keys require the provider to generate encryption nonces.
        // Supplying our own IV fails with CALLER_NONCE_PROHIBITED for the persisted master key.
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(dataKey.encoded)
        return EncryptedDataKey(encrypted, iv)
    }

    fun decryptDataKey(encryptedKey: EncryptedDataKey, masterKey: SecretKey): SecretKey {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, encryptedKey.iv))
        val decrypted = cipher.doFinal(encryptedKey.encryptedKey)
        return SecretKeySpec(decrypted, "AES")
    }

    /**
     * Encrypt an input stream into a container file using RandomAccessFile
     * to support updating the totalChunks field after all chunks are written.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        dataKey: SecretKey,
        originalMimeType: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): ContainerMetadata {
        val ivBase = ByteArray(GCM_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        var totalEncrypted = 0L
        var totalChunks = 0

        // Write header (totalChunks goes in footer to avoid seeking)
        val header = ByteBuffer.allocate(4 + 1 + 4 + GCM_NONCE_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC.toByteArray())
        header.put(VERSION)
        header.putInt(chunkSize)
        header.put(ivBase)
        output.write(header.array())

        // Encrypt and write chunks
        val buffer = ByteArray(chunkSize)
        while (true) {
            val read = readChunk(input, buffer)
            if (read <= 0) break
            val plaintext = if (read == chunkSize) buffer else buffer.copyOf(read)
            digest.update(plaintext)

            val nonce = computeNonce(ivBase, totalChunks)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(plaintext)

            // Write: nonce(12) | cipherLen(4) | ciphertext+tag
            output.write(nonce)
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ciphertext.size)
            output.write(lenBuf.array())
            output.write(ciphertext)

            totalEncrypted += plaintext.size
            totalChunks++
        }

        // Write footer: totalChunks(4) | totalEncryptedSize(8) | SHA256(32) = 44 bytes fixed
        val sha256 = digest.digest()
        val footer = ByteBuffer.allocate(4 + 8 + FOOTER_SHA256_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        footer.putInt(totalChunks)
        footer.putLong(totalEncrypted)
        footer.put(sha256)
        output.write(footer.array())

        return ContainerMetadata(
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            ivBase = ivBase,
            totalEncryptedSize = totalEncrypted,
            originalMimeType = originalMimeType,
            sha256 = sha256,
        )
    }

    /**
     * Decrypt a container file back to an output stream.
     * Returns the SHA-256 of the decrypted plaintext for verification.
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        dataKey: SecretKey,
    ): ByteArray {
        // Read header
        val magic = ByteArray(4).also { require(input.read(it) == 4) { "EOF reading magic" } }
        require(String(magic) == MAGIC) { "Invalid container magic" }
        val version = input.read().toByte()
        require(version == VERSION) { "Unsupported container version: $version" }
        val chunkSize = readInt(input)
        val ivBase = ByteArray(GCM_NONCE_SIZE).also { require(input.read(it) == GCM_NONCE_SIZE) { "EOF reading IV base" } }

        val digest = MessageDigest.getInstance("SHA-256")

        // Read all remaining bytes, split into chunks and footer
        // Footer is fixed 44 bytes: totalChunks(4) + totalEncryptedSize(8) + SHA256(32)
        val FOOTER_SIZE = 4 + 8 + FOOTER_SHA256_SIZE
        val remaining = input.readBytes()
        require(remaining.size >= FOOTER_SIZE) { "Container too small" }
        val footerStart = remaining.size - FOOTER_SIZE
        val totalChunks = ByteBuffer.wrap(remaining, footerStart, 4).order(ByteOrder.BIG_ENDIAN).int
        require(totalChunks >= 0) { "Invalid totalChunks" }
        val sha256 = remaining.copyOfRange(footerStart + 12, footerStart + 12 + FOOTER_SHA256_SIZE)

        // Chunk data is everything before the footer
        val chunkData = remaining.copyOfRange(0, footerStart)
        val chunkInput = java.io.ByteArrayInputStream(chunkData)

        // Decrypt chunks
        for (i in 0 until totalChunks) {
            val nonce = ByteArray(GCM_NONCE_SIZE).also { require(chunkInput.read(it) == GCM_NONCE_SIZE) { "EOF reading nonce" } }
            val cipherLen = readInt(chunkInput)
            require(cipherLen > 0) { "Invalid cipher length" }
            val ciphertext = ByteArray(cipherLen).also { require(chunkInput.read(it) == cipherLen) { "EOF reading ciphertext" } }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plaintext = cipher.doFinal(ciphertext)
            digest.update(plaintext)
            output.write(plaintext)
        }

        val computedSha = digest.digest()
        require(computedSha.contentEquals(sha256)) { "Container integrity check failed: SHA-256 mismatch" }
        return sha256
    }


    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME)
        keyStore.load(null)
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(MASTER_KEY_SIZE)
            .build()
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME)
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun isMasterKeyValid(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_NAME)
            keyStore.load(null)
            keyStore.getKey(MASTER_KEY_ALIAS, null) != null
        } catch (e: Exception) {
            false
        }
    }

    fun deleteMasterKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME)
        keyStore.load(null)
        keyStore.deleteEntry(MASTER_KEY_ALIAS)
    }

    /**
     * Decrypt a single chunk by index. Used for video seek.
     */
    fun decryptChunk(
        input: InputStream,
        dataKey: SecretKey,
        chunkIndex: Int,
        chunkSize: Int,
        ivBase: ByteArray,
    ): ByteArray {
        // Skip to the requested chunk
        for (i in 0 until chunkIndex) {
            // Skip nonce(12) + cipherLen(4) + ciphertext
            require(input.skip(12) == 12L) { "EOF skipping nonce" }
            val len = readInt(input)
            require(input.skip(len.toLong()) == len.toLong()) { "EOF skipping chunk $i" }
        }

        // Read the target chunk
        val nonce = ByteArray(GCM_NONCE_SIZE).also { require(input.read(it) == GCM_NONCE_SIZE) { "EOF reading nonce" } }
        val cipherLen = readInt(input)
        val ciphertext = ByteArray(cipherLen).also { require(input.read(it) == cipherLen) { "EOF reading ciphertext" } }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun computeNonce(ivBase: ByteArray, counter: Int): ByteArray {
        val nonce = ivBase.copyOf()
        val counterBytes = ByteBuffer.allocate(4).putInt(counter).array()
        for (i in 0 until 4) {
            nonce[nonce.size - 4 + i] = (nonce[nonce.size - 4 + i].toInt() xor counterBytes[i].toInt()).toByte()
        }
        return nonce
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return totalRead
    }

    private fun readInt(input: InputStream): Int {
        val bytes = ByteArray(4).also { require(input.read(it) == 4) { "EOF reading int" } }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readShort(input: InputStream): Short {
        val bytes = ByteArray(2).also { require(input.read(it) == 2) { "EOF reading short" } }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).short
    }

    private fun readLong(input: InputStream): Long {
        val bytes = ByteArray(8).also { require(input.read(it) == 8) { "EOF reading long" } }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).long
    }
}
