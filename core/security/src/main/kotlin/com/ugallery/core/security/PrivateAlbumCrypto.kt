package com.ugallery.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.*
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

/** v3 authenticates plaintext size; v2 remains a compatibility/staging format and v1 is read-only. */
object PrivateAlbumCrypto {
    private const val MAGIC = "UGPC"
    private const val VERSION: Byte = 2
    private const val SIZED_VERSION: Byte = 3
    const val DEFAULT_CHUNK_SIZE = 1024 * 1024
    private const val MAX_CHUNK_SIZE = 16 * 1024 * 1024
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val KEYSTORE_NAME = "AndroidKeyStore"
    const val MASTER_KEY_ALIAS = "ugallery.privatealbum.master"
    const val MASTER_KEY_SIZE = 256

    data class ContainerMetadata(
        val chunkSize: Int,
        /** Logical plaintext blocks; not the internal Tink segment count. */
        val totalChunks: Int,
        /** Legacy nonce base, or v2 authenticated random container identifier. */
        val ivBase: ByteArray,
        /** Plaintext size retained for source/API compatibility. */
        val totalEncryptedSize: Long,
        val originalMimeType: String,
        val sha256: ByteArray,
    )
    data class EncryptedDataKey(val encryptedKey: ByteArray, val iv: ByteArray)

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

    /** Compatibility writer for existing v2 fixtures and encrypted-only unknown-length staging. */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        dataKey: SecretKey,
        originalMimeType: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        checkActive: () -> Unit = {},
    ): ContainerMetadata {
        return encryptFrom(output, dataKey, originalMimeType, chunkSize, checkActive) { plaintext ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val n = readSome(input, buffer)
                if (n < 0) break
                plaintext.write(buffer, 0, n)
            }
        }
    }

    /** Size is part of the complete Tink AAD; a caller never receives success for short/long input. */
    fun encryptSizedStream(
        input: InputStream, output: OutputStream, dataKey: SecretKey, originalMimeType: String,
        plaintextBytes: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE, checkActive: () -> Unit = {},
    ): ContainerMetadata = encryptFrom(output, dataKey, originalMimeType, chunkSize, checkActive, plaintextBytes) { plaintext ->
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                checkActive()
                val n = readSome(input, buffer)
                if (n < 0) break
                plaintext.write(buffer, 0, n)
            }
        } finally { buffer.fill(0) }
    }

    /** Trusted size is checked again while authenticating ciphertext; no plaintext staging. */
    fun reencryptSizedStream(
        input: InputStream, output: OutputStream, sourceKey: SecretKey, destinationKey: SecretKey,
        originalMimeType: String, expectedSha256: ByteArray, plaintextBytes: Long, checkActive: () -> Unit = {},
    ): ContainerMetadata = encryptFrom(output, destinationKey, originalMimeType, DEFAULT_CHUNK_SIZE, checkActive, plaintextBytes) { plaintext ->
        decryptStream(input, plaintext, sourceKey, expectedSha256, checkActive)
    }

    /** Existing archive inputs are seekable ciphertext. Verify/count first, then emit sized v3. */
    fun reencryptStream(
        input: InputStream, output: OutputStream, sourceKey: SecretKey, destinationKey: SecretKey,
        originalMimeType: String, expectedSha256: ByteArray, checkActive: () -> Unit = {},
    ): ContainerMetadata {
        val start = (input as? FileInputStream)?.channel?.position()
        require(start != null || input is ByteArrayInputStream) { "Sized transcode requires seekable ciphertext" }
        if (input is ByteArrayInputStream) input.mark(0)
        val plaintextBytes = countVerifiedPlaintext(input, sourceKey, expectedSha256, checkActive)
        if (start != null) (input as FileInputStream).channel.position(start) else (input as ByteArrayInputStream).reset()
        checkActive()
        return reencryptSizedStream(input, output, sourceKey, destinationKey, originalMimeType,
            expectedSha256, plaintextBytes, checkActive)
    }

    private fun countVerifiedPlaintext(input: InputStream, key: SecretKey, expected: ByteArray, checkActive: () -> Unit): Long {
        var count = 0L
        decryptStream(input, object : OutputStream() {
            override fun write(b: Int) { count = Math.incrementExact(count) }
            override fun write(b: ByteArray, off: Int, len: Int) { count = Math.addExact(count, len.toLong()) }
        }, key, expected, checkActive)
        return count
    }

    private fun encryptFrom(
        output: OutputStream, dataKey: SecretKey, originalMimeType: String, chunkSize: Int,
        checkActive: () -> Unit, plaintextBytes: Long? = null, writePlaintext: (OutputStream) -> Unit,
    ): ContainerMetadata {
        require(chunkSize in 64..MAX_CHUNK_SIZE)
        require(plaintextBytes == null || plaintextBytes >= 0) { "Invalid plaintext size" }
        checkActive()
        val mime = originalMimeType.toByteArray(Charsets.UTF_8)
        require(mime.size in 1..255 && !originalMimeType.any { it.isISOControl() })
        val id = ByteArray(12).also(SecureRandom()::nextBytes)
        val fields = ByteBuffer.allocate(4 + 1 + 4 + 12 + 2 + mime.size + if (plaintextBytes == null) 0 else 8)
            .put(MAGIC.toByteArray(Charsets.US_ASCII)).put(if (plaintextBytes == null) VERSION else SIZED_VERSION).putInt(chunkSize)
            .put(id).putShort(mime.size.toShort()).put(mime)
        if (plaintextBytes != null) fields.putLong(plaintextBytes)
        val header = fields.array()
        output.write(header)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        primitive(dataKey, chunkSize).newEncryptingStream(nonClosing(output), header).use { encrypted ->
            val plaintext = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    checkActive()
                    require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                    val next = Math.addExact(total, length.toLong())
                    require(plaintextBytes == null || next <= plaintextBytes) { "Plaintext exceeds authenticated size" }
                    encrypted.write(bytes, offset, length)
                    digest.update(bytes, offset, length)
                    total = next
                }
            }
            writePlaintext(plaintext)
            checkActive()
            require(plaintextBytes == null || total == plaintextBytes) { "Plaintext is shorter than authenticated size" }
        }
        val count = if (total == 0L) 0 else Math.toIntExact((total - 1) / chunkSize + 1)
        return ContainerMetadata(chunkSize, count, id, total, originalMimeType, digest.digest())
    }

    /** Caller publishes only after success/EOF. Legacy files are fully prevalidated without emitting bytes. */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        dataKey: SecretKey,
        expectedSha256: ByteArray? = null,
        checkActive: () -> Unit = {},
    ): ByteArray {
        expectedSha256?.let { require(it.size == 32) }
        val position = (input as? FileInputStream)?.channel?.position()
        if (input is ByteArrayInputStream) input.mark(0)
        val prefix = readExact(input, 5)
        require(prefix.copyOfRange(0, 4).contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) { "Invalid container magic" }
        fun rewind() {
            when {
                position != null -> (input as FileInputStream).channel.position(position)
                input is ByteArrayInputStream -> input.reset()
                else -> throw IllegalArgumentException("Legacy container requires a seekable private ciphertext staging file")
            }
        }
        return when (prefix[4].toInt()) {
            1 -> {
                rewind()
                val verified = decryptLegacy(input, sink, dataKey, checkActive)
                verifyDigest(verified, expectedSha256)
                rewind()
                val emitted = decryptLegacy(input, output, dataKey, checkActive)
                require(MessageDigest.isEqual(verified, emitted)) { "Legacy source changed during decryption" }
                emitted
            }
            2, 3 -> {
                val fields = readExact(input, 18)
                val segmentSize = ByteBuffer.wrap(fields, 0, 4).int
                require(segmentSize in 64..MAX_CHUNK_SIZE) { "Invalid segment size" }
                val mimeSize = ByteBuffer.wrap(fields, 16, 2).short.toInt() and 0xffff
                require(mimeSize in 1..255)
                val metadata = prefix + fields + readExact(input, mimeSize)
                val sizeBytes = if (prefix[4].toInt() == 3) readExact(input, 8) else ByteArray(0)
                val expectedSize = if (sizeBytes.isNotEmpty()) ByteBuffer.wrap(sizeBytes).long else null
                require(expectedSize == null || expectedSize >= 0) { "Invalid authenticated plaintext size" }
                val header = metadata + sizeBytes
                var total = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                primitive(dataKey, segmentSize).newDecryptingStream(nonClosing(input), header).use { decrypted ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val n = readSome(decrypted, buffer)
                        if (n < 0) break
                        total = Math.addExact(total, n.toLong())
                        require(expectedSize == null || total <= expectedSize) { "Plaintext exceeds authenticated size" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
                checkActive()
                require(expectedSize == null || total == expectedSize) { "Plaintext is shorter than authenticated size" }
                digest.digest().also { verifyDigest(it, expectedSha256) }
            }
            else -> throw IllegalArgumentException("Unsupported container version")
        }
    }

    private fun decryptLegacy(input: InputStream, output: OutputStream, key: SecretKey, checkActive: () -> Unit): ByteArray {
        val header = readExact(input, 21)
        require(header.copyOfRange(0, 5).contentEquals(byteArrayOf(85, 71, 80, 67, 1)))
        val chunkSize = ByteBuffer.wrap(header, 5, 4).int
        require(chunkSize in 1..MAX_CHUNK_SIZE)
        val ivBase = header.copyOfRange(9, 21)
        val stream = PushbackInputStream(input, 45)
        val digest = MessageDigest.getInstance("SHA-256")
        var chunks = 0
        var size = 0L
        var previousSize = chunkSize
        while (true) {
            checkActive()
            val probe = ByteArray(45)
            var count = 0
            while (count < probe.size) {
                val n = stream.read(probe, count, probe.size - count)
                if (n < 0) break
                if (n == 0) { val one = stream.read(); if (one < 0) break; probe[count++] = one.toByte() }
                else count += n
            }
            require(count >= 44) { "Truncated legacy container" }
            if (count == 44) {
                val footer = ByteBuffer.wrap(probe, 0, 44)
                require(footer.int == chunks) { "Legacy chunk count mismatch" }
                require(footer.long == size) { "Legacy plaintext size mismatch" }
                val expected = ByteArray(32).also(footer::get)
                val actual = digest.digest()
                require(MessageDigest.isEqual(expected, actual)) { "Legacy digest mismatch" }
                return actual
            }
            stream.unread(probe)
            require(previousSize == chunkSize) { "Nonfinal short legacy chunk" }
            val nonce = readExact(stream, 12)
            require(nonce.contentEquals(computeNonce(ivBase, chunks))) { "Legacy nonce position mismatch" }
            val cipherSize = ByteBuffer.wrap(readExact(stream, 4)).int
            require(cipherSize in 17..(chunkSize + 16)) { "Invalid legacy cipher length" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val plaintext = cipher.doFinal(readExact(stream, cipherSize))
            digest.update(plaintext)
            output.write(plaintext)
            size = Math.addExact(size, plaintext.size.toLong())
            chunks = Math.incrementExact(chunks)
            previousSize = plaintext.size
        }
    }

    /** Legacy-only chunk-body API. No v2 caller should use unauthenticated header arguments. */
    @Deprecated("Legacy v1 only; use authenticated full-stream decryption")
    fun decryptChunk(input: InputStream, dataKey: SecretKey, chunkIndex: Int, chunkSize: Int, ivBase: ByteArray): ByteArray {
        require(chunkIndex >= 0 && chunkSize in 1..MAX_CHUNK_SIZE && ivBase.size == 12)
        for (index in 0..chunkIndex) {
            val nonce = readExact(input, 12)
            require(nonce.contentEquals(computeNonce(ivBase, index)))
            val size = ByteBuffer.wrap(readExact(input, 4)).int
            require(size in 17..(chunkSize + 16))
            val ciphertext = readExact(input, size)
            if (index == chunkIndex) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(128, nonce))
                return cipher.doFinal(ciphertext)
            }
        }
        error("Invalid chunk")
    }

    internal fun primitive(key: SecretKey, segmentSize: Int = DEFAULT_CHUNK_SIZE): StreamingAead {
        val bytes = requireNotNull(key.encoded) { "Exportable per-item data key required, never a Keystore master" }
        require(bytes.size == 32)
        return try { AesGcmHkdfStreaming(bytes, "HmacSha256", 32, segmentSize, 0) } finally { bytes.fill(0) }
    }
    internal fun nonClosing(output: OutputStream): OutputStream = object : FilterOutputStream(output) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }
    internal fun nonClosing(input: InputStream): InputStream = object : FilterInputStream(input) { override fun close() = Unit }
    internal fun readExact(input: InputStream, size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val n = input.read(bytes, offset, size - offset)
            if (n < 0) throw EOFException("Truncated encrypted container")
            if (n == 0) { val one = input.read(); if (one < 0) throw EOFException(); bytes[offset++] = one.toByte() }
            else offset += n
        }
    }
    private fun readSome(input: InputStream, bytes: ByteArray): Int {
        val n = input.read(bytes)
        if (n != 0) return n
        val one = input.read()
        if (one < 0) return -1
        bytes[0] = one.toByte()
        return 1
    }
    private fun verifyDigest(actual: ByteArray, expected: ByteArray?) {
        require(expected == null || MessageDigest.isEqual(actual, expected)) { "Trusted plaintext digest mismatch" }
    }
    private val sink = object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
    private fun computeNonce(iv: ByteArray, counter: Int): ByteArray {
        val nonce = iv.copyOf()
        val c = ByteBuffer.allocate(4).putInt(counter).array()
        for (i in 0..3) nonce[8+i] = (nonce[8+i].toInt() xor c[i].toInt()).toByte()
        return nonce
    }

    /** Existing vaults must never fall through to key generation after loss or invalidation. */
    fun requireExistingMasterKey(alias: String): SecretKey {
        require(alias.startsWith("ugallery.privatealbum.") && alias.length in 1..200) { "Invalid private master alias" }
        return (KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }.getKey(alias, null) as? SecretKey)
            ?: error("Private master key is missing")
    }

    /** Legacy, unauthenticated key creation for pre-migration test fixtures only; production uses [createAuthenticatedMasterKey]. */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    fun getOrCreateMasterKey(alias: String = MASTER_KEY_ALIAS): SecretKey {
        require(alias.startsWith("ugallery.privatealbum.") && alias.length in 1..200) { "Invalid private master alias" }
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME)
        keyStore.load(null)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            alias,
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

    const val AUTHENTICATED_MASTER_PREFIX = "ugallery.privatealbum.auth.v1."

    /** A new alias only. Missing committed or in-progress aliases never enter this creator. */
    fun createAuthenticatedMasterKey(alias: String, strongBox: Boolean = false): SecretKey {
        require(alias.startsWith(AUTHENTICATED_MASTER_PREFIX) && alias.length <= 200)
        val store = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        check(!store.containsAlias(alias)) { "Private authenticated key already exists" }
        fun generate(hardware: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setIsStrongBoxBacked(hardware).build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME).run {
                init(spec); generateKey()
            }
        }
        return try { generate(strongBox) }
        catch (missing: android.security.keystore.StrongBoxUnavailableException) {
            if (!strongBox) throw missing
            generate(false)
        }.also { check(isAuthenticationBound(alias)) { "Private key authentication policy mismatch" } }
    }

    fun isAuthenticationBound(alias: String): Boolean {
        val key = requireExistingMasterKey(alias)
        val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, KEYSTORE_NAME)
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        return info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds == 30 &&
            info.userAuthenticationType == (KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
    }

    fun requiresAuthentication(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .take(12).any { it is android.security.keystore.UserNotAuthenticatedException }

    /** Called only after a durable metadata switch, or for an explicitly cancelled owned target. */
    fun retireMasterKey(alias: String) {
        require(alias.startsWith("ugallery.privatealbum.") && alias.length <= 200)
        val store = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        store.deleteEntry(alias)
        check(!store.containsAlias(alias)) { "Private master retirement is incomplete" }
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

}
