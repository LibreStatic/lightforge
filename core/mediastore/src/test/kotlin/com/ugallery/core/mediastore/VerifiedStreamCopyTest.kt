package com.ugallery.core.mediastore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class VerifiedStreamCopyTest {
    @Test fun copyAndReadbackPreserveEmptyAndMultichunkBytes() {
        for (size in listOf(0, 1, 131073)) {
            val bytes = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            val fingerprint = VerifiedStreamCopy.copy(bytes.inputStream(), output) {}
            assertEquals(size.toLong(), fingerprint.bytes)
            assertArrayEquals(bytes, output.toByteArray())
            VerifiedStreamCopy.verify(output.toByteArray().inputStream(), fingerprint) {}
        }
    }
    @Test fun truncateCorruptAndChangedSourceReject() {
        val bytes = ByteArray(100) { it.toByte() }
        val fingerprint = VerifiedStreamCopy.copy(bytes.inputStream(), ByteArrayOutputStream()) {}
        for (changed in listOf(bytes.copyOf(99), bytes + byteArrayOf(1), bytes.copyOf().also { it[50] = 0 })) {
            assertThrows(IllegalStateException::class.java) { VerifiedStreamCopy.verify(changed.inputStream(), fingerprint) {} }
        }
    }
    @Test fun cancellationAfterBlockingReadPreventsWrite() {
        var cancelled = false
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { cancelled = true }
        }
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            VerifiedStreamCopy.copy(source, output) { if (cancelled) throw CancellationException() }
        }
        assertEquals(0, output.size())
    }
}
