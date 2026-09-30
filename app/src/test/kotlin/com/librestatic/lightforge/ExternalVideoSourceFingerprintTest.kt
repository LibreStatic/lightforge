package com.librestatic.lightforge

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExternalVideoSourceFingerprintTest {
    @Test fun exactBytesAndClose() = runBlocking {
        var closed = false
        val result = externalVideoFingerprint { object : ByteArrayInputStream("abc".toByteArray()) {
            override fun close() { closed = true; super.close() }
        } }
        assertEquals(3L, result.sizeBytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        assertTrue(closed)
    }
    @Test fun sameLengthReplacementHasDifferentIdentity() = runBlocking {
        val a = externalVideoFingerprint { ByteArrayInputStream("abc".toByteArray()) }
        val b = externalVideoFingerprint { ByteArrayInputStream("abd".toByteArray()) }
        assertEquals(a.sizeBytes,b.sizeBytes); assertNotEquals(a.sha256,b.sha256)
    }
    @Test fun emptySourceIsRejected() = runBlocking {
        try { externalVideoFingerprint { ByteArrayInputStream(byteArrayOf()) }; fail("Empty source accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun streamFailureClosesWithoutProof() = runBlocking {
        var closed=false
        try { externalVideoFingerprint { object : InputStream() {
            override fun read():Int = throw IOException("fixture")
            override fun close() { closed=true }
        } };fail("Partial proof accepted") } catch (_: IOException) { }
        assertTrue(closed)
    }
    @Test fun cancellationClosesWithoutProof() = runBlocking {
        var closed=false;var succeeded=false
        val job=launch(start=CoroutineStart.LAZY) {
            externalVideoFingerprint { object : InputStream() {
                override fun read():Int { cancel();return 1 }
                override fun read(bytes:ByteArray,offset:Int,len:Int):Int { cancel();bytes[offset]=1;return 1 }
                override fun close() { closed=true }
            } };succeeded=true
        }
        job.start();job.join();assertTrue(closed);assertFalse(succeeded)
    }
}
