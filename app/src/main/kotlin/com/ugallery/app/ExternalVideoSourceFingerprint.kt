package com.ugallery.app

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ExternalVideoFingerprint(val sizeBytes: Long, val sha256: String)

/** Caller uses IO dispatcher. Bounded memory, actual bytes and guaranteed close; never a partial proof. */
internal suspend fun externalVideoFingerprint(open: () -> InputStream): ExternalVideoFingerprint {
    currentCoroutineContext().ensureActive()
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    open().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            size = Math.addExact(size, count.toLong())
            digest.update(buffer, 0, count)
        }
    }
    currentCoroutineContext().ensureActive()
    require(size > 0) { "Empty external video" }
    return ExternalVideoFingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
}
