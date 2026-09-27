package com.librestatic.lightforge.core.security

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.channels.NonWritableChannelException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Read-only v1/v2/v3 viewer handle. Opening verifies the complete plaintext against the
 * trusted encrypted-index digest before exposing a length or any bytes. No plaintext
 * file is created. The same descriptor is retained for subsequent authenticated reads.
 * v2 size() alone is not authentication; v1's header is not an authenticated envelope.
 * Call open/read/close off main. The session adapter owns synchronous delivery admission.
 */
class PrivateSeekableReader private constructor(
    private val input: FileInputStream,
    private val key: SecretKey,
    private val header: ByteArray,
    val plaintextBytes: Long,
    private val chunkSize: Int,
    private val decrypted: SeekableByteChannel?,
    private val checkActive: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private val ciphertextBytes = input.channel.size()

    @Synchronized
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
        check(!closed) { "Private reader is closed" }
        checkActive()
        check(input.channel.size() == ciphertextBytes) { "Private ciphertext length changed" }
        if (length == 0) return 0
        if (position >= plaintextBytes) return -1
        val wanted = minOf(length.toLong(), plaintextBytes - position, 64L * 1024).toInt()
        // Never put unverified or revoked bytes in the caller's buffer.
        val scratch = ByteArray(wanted)
        try {
            val count = if (decrypted != null) {
                decrypted.position(position)
                val buffer = ByteBuffer.wrap(scratch)
                while (buffer.hasRemaining()) {
                    checkActive()
                    val n = decrypted.read(buffer)
                    if (n < 0) throw EOFException("Verified private plaintext was truncated")
                    if (n == 0) throw EOFException("Private reader made no progress")
                }
                wanted
            } else {
                val index = Math.toIntExact(position / chunkSize)
                val within = (position % chunkSize).toInt()
                val plainSize = minOf(chunkSize.toLong(), plaintextBytes - index.toLong() * chunkSize).toInt()
                input.channel.position(Math.addExact(21L, Math.multiplyExact(index.toLong(), chunkSize.toLong() + 32)))
                val nonce = PrivateAlbumCrypto.readExact(input, 12)
                val expectedNonce = header.copyOfRange(9, 21)
                for (i in 0..3) expectedNonce[8 + i] =
                    (expectedNonce[8 + i].toInt() xor (index ushr (24 - i * 8))).toByte()
                check(MessageDigest.isEqual(nonce, expectedNonce)) { "Legacy nonce position changed" }
                val cipherSize = ByteBuffer.wrap(PrivateAlbumCrypto.readExact(input, 4)).int
                check(cipherSize == plainSize + 16) { "Legacy chunk length changed" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                val plain = cipher.doFinal(PrivateAlbumCrypto.readExact(input, cipherSize))
                try {
                    minOf(wanted, plain.size - within).also { plain.copyInto(scratch, 0, within, within + it) }
                } finally { plain.fill(0) }
            }
            checkActive()
            scratch.copyInto(target, offset, 0, count)
            return count
        } finally { scratch.fill(0) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { decrypted?.close() } finally { input.close() }
    }

    companion object {
        fun open(file: File, key: SecretKey, expectedSha256: ByteArray, checkActive: () -> Unit = {}): PrivateSeekableReader {
            require(expectedSha256.size == 32)
            checkActive()
            val input = FileInputStream(file)
            try {
                val prefix = PrivateAlbumCrypto.readExact(input, 21)
                require(prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(85, 71, 80, 67)))
                val version = prefix[4].toInt()
                require(version in 1..3)
                val chunkSize = ByteBuffer.wrap(prefix, 5, 4).int
                require(chunkSize in (if (version == 1) 1 else 64)..(16 * 1024 * 1024))
                val header = if (version >= 2) {
                    val mimeLengthBytes = PrivateAlbumCrypto.readExact(input, 2)
                    val mimeLength = ByteBuffer.wrap(mimeLengthBytes).short.toInt() and 0xffff
                    require(mimeLength in 1..255)
                    val metadata = prefix + mimeLengthBytes + PrivateAlbumCrypto.readExact(input, mimeLength)
                    if (version == 3) metadata + PrivateAlbumCrypto.readExact(input, 8) else metadata
                } else prefix
                val cipherSize = input.channel.size()
                input.channel.position(0)
                var length = 0L
                PrivateAlbumCrypto.decryptStream(input, object : OutputStream() {
                    override fun write(b: Int) { length = Math.incrementExact(length) }
                    override fun write(b: ByteArray, off: Int, len: Int) { length = Math.addExact(length, len.toLong()) }
                }, key, expectedSha256.copyOf(), checkActive)
                checkActive()
                check(input.channel.size() == cipherSize) { "Private ciphertext changed during verification" }
                input.channel.position(0)
                check(MessageDigest.isEqual(header, PrivateAlbumCrypto.readExact(input, header.size))) {
                    "Private header changed during verification"
                }
                val channel = if (version >= 2) {
                    val payload = PayloadChannel(input.channel, header.size.toLong())
                    PrivateAlbumCrypto.primitive(key, chunkSize).newSeekableDecryptingChannel(payload, header).also {
                        try { check(it.size() == length) { "Verified private length mismatch" } }
                        catch (failure: Throwable) { it.close(); throw failure }
                    }
                } else null
                return PrivateSeekableReader(input, key, header, length, chunkSize, channel, checkActive)
            } catch (failure: Throwable) { input.close(); throw failure }
        }
    }
}

/** Tink's payload is an independent address space, not a channel merely positioned past UGPC. */
private class PayloadChannel(private val source: SeekableByteChannel, private val start: Long) : SeekableByteChannel {
    private var cursor = 0L
    override fun read(dst: ByteBuffer): Int {
        source.position(Math.addExact(start, cursor))
        return source.read(dst).also { if (it > 0) cursor = Math.addExact(cursor, it.toLong()) }
    }
    override fun position(): Long = cursor
    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0)
        cursor = newPosition
        return this
    }
    override fun size(): Long = source.size() - start
    override fun isOpen(): Boolean = source.isOpen
    override fun close() = source.close()
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
}
