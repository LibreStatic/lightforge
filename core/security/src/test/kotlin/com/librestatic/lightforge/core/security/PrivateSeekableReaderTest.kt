package com.librestatic.lightforge.core.security

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PrivateSeekableReaderTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 13).toByte() }, "AES")
    private val plain = ByteArray(240_017) { (it * 31 + it / 257).toByte() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun encrypted(bytes: ByteArray = plain) = ByteArrayOutputStream().also {
        PrivateAlbumCrypto.encryptStream(bytes.inputStream(), it, key, "video/mp4", chunkSize = 4096)
    }.toByteArray()
    private fun legacy(bytes: ByteArray = plain): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = 1024
        val base = ByteArray(12) { (it + 8).toByte() }
        out.write(ByteBuffer.allocate(21).put(byteArrayOf(85,71,80,67,1)).putInt(chunk).put(base).array())
        var count = 0
        for (start in bytes.indices step chunk) {
            val nonce = base.copyOf()
            for (i in 0..3) nonce[8+i] = (nonce[8+i].toInt() xor (count ushr (24-i*8))).toByte()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val body = cipher.doFinal(bytes.copyOfRange(start, minOf(start+chunk, bytes.size)))
            out.write(nonce); out.write(ByteBuffer.allocate(4).putInt(body.size).array()); out.write(body)
            count++
        }
        out.write(ByteBuffer.allocate(44).putInt(count).putLong(bytes.size.toLong()).put(digest(bytes)).array())
        return out.toByteArray()
    }
    private fun fixture(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("lightforge-reader-", ".ugpc")
        try { file.writeBytes(bytes); block(file) } finally { check(file.delete()) }
    }
    private fun read(reader: PrivateSeekableReader, start: Int, length: Int): ByteArray {
        val result = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = reader.readAt(start.toLong() + done, result, done, length - done)
            check(n > 0); done += n
        }
        return result
    }
    @Test fun v2RandomSeekCrossesPayloadSegmentsAndPreservesCiphertext() = fixture(encrypted()) { file ->
        val before = digest(file.readBytes())
        PrivateSeekableReader.open(file, key, digest(plain)).use { r ->
            assertEquals(plain.size.toLong(), r.plaintextBytes)
            for ((start, size) in listOf(0 to 1, 4000 to 10000, 210001 to 20000, 9 to 170001, 239999 to 18))
                assertArrayEquals(plain.copyOfRange(start,start+size), read(r,start,size))
            assertEquals(-1, r.readAt(plain.size.toLong(), ByteArray(1),0,1))
            assertEquals(-1, r.readAt(Long.MAX_VALUE, ByteArray(1),0,1))
            assertEquals(0, r.readAt(0,ByteArray(0),0,0))
        }
        assertArrayEquals(before,digest(file.readBytes()))
    }
    @Test fun v1RandomSeekUsesVerifiedFixedRecordsIncludingShortTail() = fixture(legacy()) { file ->
        val before = digest(file.readBytes())
        PrivateSeekableReader.open(file,key,digest(plain)).use { r ->
            for ((start,size) in listOf(238991 to 1026, 1023 to 8000, 0 to 100000, 13 to 3))
                assertArrayEquals(plain.copyOfRange(start,start+size),read(r,start,size))
        }
        assertArrayEquals(before,digest(file.readBytes()))
    }
    @Test fun emptyV1AndV2VerifyTerminalData() {
        for (bytes in listOf(encrypted(ByteArray(0)),legacy(ByteArray(0)))) fixture(bytes) { file ->
            PrivateSeekableReader.open(file,key,digest(ByteArray(0))).use {
                assertEquals(0L,it.plaintextBytes); assertEquals(-1,it.readAt(0,ByteArray(1),0,1))
            }
        }
    }
    @Test fun headerBodyFooterTruncationAppendAndWrongDigestRejectedBeforeOpenReturns() {
        for (original in listOf(encrypted(),legacy())) {
            for (changed in listOf(
                original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() },
                original.copyOf().also { it[3000] = (it[3000].toInt() xor 1).toByte() },
                original.copyOf(original.size-1), original + byteArrayOf(1),
            )) fixture(changed) { file ->
                assertThrows(Exception::class.java) { PrivateSeekableReader.open(file,key,digest(plain)).close() }
            }
            fixture(original) { file ->
                assertThrows(Exception::class.java) { PrivateSeekableReader.open(file,key,ByteArray(32)).close() }
                assertThrows(Exception::class.java) { PrivateSeekableReader.open(file,SecretKeySpec(ByteArray(32),"AES"),digest(plain)).close() }
            }
        }
    }
    @Test fun postOpenTamperNeverChangesCallerBuffer() {
        for (original in listOf(encrypted(),legacy())) fixture(original) { file ->
            PrivateSeekableReader.open(file,key,digest(plain)).use { r ->
                RandomAccessFile(file,"rw").use { f -> f.seek(8500); val b=f.readByte();f.seek(8500);f.writeByte(b.toInt() xor 1) }
                val target=ByteArray(5000){77}
                assertThrows(Exception::class.java) { r.readAt(8192,target,0,target.size) }
                assertArrayEquals(ByteArray(5000){77},target)
            }
        }
    }
    @Test fun cancellationBeforeOpenAndBeforeDeliveryRejectsBytes() = fixture(encrypted()) { file ->
        assertThrows(CancellationException::class.java) {
            PrivateSeekableReader.open(file,key,digest(plain)) { throw CancellationException() }
        }
        var checks=0; var armed=false
        PrivateSeekableReader.open(file,key,digest(plain)) {
            if (armed && ++checks >= 3) throw CancellationException()
        }.use { r ->
            val target=ByteArray(100){55}; armed=true
            assertThrows(CancellationException::class.java) { r.readAt(4000,target,0,target.size) }
            assertArrayEquals(ByteArray(100){55},target)
        }
    }
    @Test fun closedAndInvalidRangesNeverRead() = fixture(encrypted()) { file ->
        val r=PrivateSeekableReader.open(file,key,digest(plain))
        for (call in listOf<() -> Unit>({r.readAt(-1,ByteArray(1),0,1)}, {r.readAt(0,ByteArray(1),Int.MAX_VALUE,1)}, {r.readAt(0,ByteArray(1),0,-1)}))
            assertThrows(IllegalArgumentException::class.java) { call() }
        r.close();r.close()
        assertThrows(IllegalStateException::class.java) { r.readAt(0,ByteArray(1),0,1) }
    }
}
