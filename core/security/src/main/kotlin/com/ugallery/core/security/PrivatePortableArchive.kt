package com.ugallery.core.security

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected local backup. No master key, names or media plaintext is written outside AEAD. */
object PrivatePortableArchive {
    private const val ITERATIONS = 600_000
    private const val MAX_ITEMS = 2_000
    private const val MAX_ITEM_BYTES = 128L * 1024 * 1024 * 1024
    private val magic = byteArrayOf(85, 71, 80, 66, 1) // UGPB, envelope v1
    private const val MANIFEST_VERSION = 1
    data class Metadata(
        val displayName: String, val mimeType: String, val mediaKind: String,
        val width: Int, val height: Int, val durationMillis: Long, val addedAtMillis: Long,
    ) {
        init {
            require(displayName.isNotBlank() && displayName.length <= 255 && !displayName.any { it.isISOControl() })
            require(mimeType.length in 1..255 && !mimeType.any { it.isISOControl() })
            require(mediaKind == "image" || mediaKind == "video")
            require(width >= 0 && height >= 0 && durationMillis >= 0 && addedAtMillis >= 0)
        }
    }
    data class Source(val metadata: Metadata, val ciphertext: File, val dataKey: SecretKey, val expectedSha256: ByteArray)
    data class Restored(val metadata: Metadata, val ciphertext: File, val dataKey: SecretKey, val container: PrivateAlbumCrypto.ContainerMetadata)

    /** Fully validates each source against the trusted local digest; writes only encrypted bytes. */
    fun write(
        sources: List<Source>, output: OutputStream, password: CharArray,
        checkActive: () -> Unit = {}, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        require(sources.size in 1..MAX_ITEMS)
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val id = ByteArray(16).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(57).put(magic).putInt(ITERATIONS).put(salt).put(id).array()
        val key = derive(password, salt, checkActive)
        output.write(header)
        PrivateAlbumCrypto.primitive(key).newEncryptingStream(PrivateAlbumCrypto.nonClosing(output), header).use { encrypted ->
            val data = DataOutputStream(encrypted)
            data.writeInt(MANIFEST_VERSION)
            data.writeInt(sources.size)
            sources.forEachIndexed { index, source ->
                checkActive()
                require(source.expectedSha256.size == 32)
                require(source.ciphertext.isFile && source.ciphertext.length() in 1..MAX_ITEM_BYTES)
                val size = source.ciphertext.length()
                val initialCipherDigest = hash(source.ciphertext, checkActive)
                FileInputStream(source.ciphertext).use {
                    PrivateAlbumCrypto.decryptStream(it, sink, source.dataKey, source.expectedSha256, checkActive)
                }
                writeMetadata(data, source.metadata)
                data.write(source.expectedSha256)
                val bytes = requireNotNull(source.dataKey.encoded)
                try { require(bytes.size == 32); data.write(bytes) } finally { bytes.fill(0) }
                data.writeLong(size)
                val copiedDigest = MessageDigest.getInstance("SHA-256")
                FileInputStream(source.ciphertext).use { input ->
                    copyExactly(input, data, size, checkActive, copiedDigest)
                    require(input.read() == -1) { "Private source size changed" }
                }
                require(MessageDigest.isEqual(initialCipherDigest, copiedDigest.digest())) { "Private source changed during backup" }
                onProgress(index + 1, sources.size)
            }
            data.writeInt(0x454e4421) // authenticated manifest end marker, not a substitute for AEAD EOF
            data.flush()
            checkActive()
        }
    }

    /** Stages only ciphertext, verifies the entire archive and every item, and returns uncommitted size-authenticated v3 files. */
    fun read(
        input: InputStream, password: CharArray, stagingDirectory: File,
        checkActive: () -> Unit = {}, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Restored> {
        require(stagingDirectory.isDirectory && stagingDirectory.listFiles().orEmpty().isEmpty()) { "Fresh owned staging directory required" }
        val owned = mutableListOf<File>()
        var complete = false
        var primary: Throwable? = null
        try {
            val header = PrivateAlbumCrypto.readExact(input, 57)
            require(header.copyOfRange(0, 5).contentEquals(magic)) { "Unsupported private backup" }
            require(ByteBuffer.wrap(header, 5, 4).int == ITERATIONS) { "Unsupported password KDF parameters" }
            val key = derive(password, header.copyOfRange(9, 41), checkActive)
            val result = mutableListOf<Restored>()
            PrivateAlbumCrypto.primitive(key).newDecryptingStream(PrivateAlbumCrypto.nonClosing(input), header).use { decrypted ->
                val data = DataInputStream(decrypted)
                require(data.readInt() == MANIFEST_VERSION)
                val count = data.readInt()
                require(count in 1..MAX_ITEMS)
                repeat(count) { index ->
                    checkActive()
                    val metadata = readMetadata(data)
                    val sha = ByteArray(32).also(data::readFully)
                    val keyBytes = ByteArray(32).also(data::readFully)
                    val sourceKey = try { SecretKeySpec(keyBytes, "AES") } finally { keyBytes.fill(0) }
                    val size = data.readLong()
                    require(size in 1..MAX_ITEM_BYTES)
                    val incoming = File(stagingDirectory, "incoming-$index.ugpc").also { require(it.createNewFile()); owned += it }
                    FileOutputStream(incoming).use { output ->
                        copyExactly(data, output, size, checkActive)
                        output.fd.sync()
                    }
                    val destination = File(stagingDirectory, "verified-$index.ugpc").also { require(it.createNewFile()); owned += it }
                    val newKey = PrivateAlbumCrypto.generateDataKey()
                    val container = FileInputStream(incoming).use { source ->
                        FileOutputStream(destination).use { output ->
                            PrivateAlbumCrypto.reencryptStream(source, output, sourceKey, newKey, metadata.mimeType, sha, checkActive)
                                .also { output.fd.sync() }
                        }
                    }
                    // Independent readback of the actual newly written private file, not just memory buffers.
                    FileInputStream(destination).use { PrivateAlbumCrypto.decryptStream(it, sink, newKey, sha, checkActive) }
                    require(incoming.delete()) { "Encrypted staging cleanup failed" }
                    result += Restored(metadata, destination, newKey, container)
                    onProgress(index + 1, count)
                }
                require(data.readInt() == 0x454e4421) { "Invalid private backup ending" }
                require(data.read() == -1) { "Trailing private backup plaintext" }
            }
            checkActive()
            complete = true
            return result
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            if (!complete) {
                var cleanup: IOException? = null
                owned.forEach { file ->
                    if (file.exists() && !file.delete()) {
                        val failure = IOException("Encrypted staging cleanup incomplete: ${file.name}")
                        if (cleanup == null) cleanup = failure else cleanup?.addSuppressed(failure)
                    }
                }
                cleanup?.let { if (primary != null) primary?.addSuppressed(it) else throw it }
            }
        }
    }

    fun hash(file: File, checkActive: () -> Unit = {}): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val n = input.read(bytes)
                if (n < 0) break
                if (n > 0) digest.update(bytes, 0, n)
            }
        }
        return digest.digest()
    }
    private fun derive(password: CharArray, salt: ByteArray, checkActive: () -> Unit): SecretKey {
        require(password.size in 12..1024) { "Use a password of at least 12 characters" }
        checkActive()
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { checkActive(); return SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    private fun writeMetadata(data: DataOutputStream, m: Metadata) {
        data.writeUTF(m.displayName); data.writeUTF(m.mimeType); data.writeUTF(m.mediaKind)
        data.writeInt(m.width); data.writeInt(m.height); data.writeLong(m.durationMillis); data.writeLong(m.addedAtMillis)
    }
    private fun readMetadata(data: DataInputStream) = Metadata(data.readUTF(), data.readUTF(), data.readUTF(), data.readInt(), data.readInt(), data.readLong(), data.readLong())
    private fun copyExactly(input: InputStream, output: OutputStream, size: Long, checkActive: () -> Unit, digest: MessageDigest? = null) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            checkActive()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Truncated private backup item")
            if (n == 0) continue
            output.write(buffer, 0, n)
            digest?.update(buffer, 0, n)
            remaining -= n
        }
    }
    private val sink = object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
