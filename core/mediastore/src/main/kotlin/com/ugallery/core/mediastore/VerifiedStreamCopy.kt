package com.ugallery.core.mediastore

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded-memory copy and readback; checks run before and after every blocking chunk. */
internal object VerifiedStreamCopy {
    data class Fingerprint(val bytes: Long, val sha256: String)

    fun copy(input: InputStream, output: OutputStream, checkActive: () -> Unit): Fingerprint =
        read(input, output, checkActive)

    fun verify(input: InputStream, expected: Fingerprint, checkActive: () -> Unit) {
        check(read(input, null, checkActive) == expected) { "Copy verification failed" }
        checkActive()
    }

    private fun read(input: InputStream, output: OutputStream?, checkActive: () -> Unit): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            checkActive()
            if (count < 0) break
            if (count == 0) continue
            output?.write(buffer, 0, count)
            checkActive()
            digest.update(buffer, 0, count)
            total = Math.addExact(total, count.toLong())
        }
        return Fingerprint(total, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
