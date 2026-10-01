package com.librestatic.lightforge.core.ml

import android.os.CancellationSignal
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A public model file pinned by its HTTPS URL, exact length and SHA-256; any other bytes are rejected. */
data class PinnedModelFile(val url: String, val bytes: Long, val sha256: String) {
    init {
        require(url.startsWith("https://"))
        require(bytes > 0L)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Resumable, verified transfer of [PinnedModelFile]s. Only model bytes are fetched; nothing is uploaded. */
object PinnedModelDownload {
    /** Throws [IllegalArgumentException] unless [file] has exactly the pinned length and hash. */
    fun verify(file: File, pinned: PinnedModelFile, signal: CancellationSignal = CancellationSignal()) {
        require(file.isFile && file.length() == pinned.bytes) { "Model length mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                signal.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == pinned.sha256) {
            "Model integrity mismatch"
        }
    }

    /**
     * Continues [file] from its partial bytes with a Range request, so a dropped connection or a paused worker
     * never restarts the transfer, then verifies it. A file that fails verification is deleted so the next
     * attempt fetches it again. [checkpoint] may throw (for example [ModelDownloadPausedException]) between reads.
     */
    fun fetch(
        pinned: PinnedModelFile,
        file: File,
        signal: CancellationSignal = CancellationSignal(),
        checkpoint: () -> Unit = {},
        progress: (Long) -> Unit = {},
    ) {
        if (file.length() > pinned.bytes) check(file.delete())
        if (file.length() < pinned.bytes) transfer(pinned, file, signal, checkpoint, progress)
        progress(pinned.bytes)
        runCatching { verify(file, pinned, signal) }
            .onFailure { if (it is IllegalArgumentException) file.delete(); throw it }
    }

    private fun transfer(
        pinned: PinnedModelFile,
        file: File,
        signal: CancellationSignal,
        checkpoint: () -> Unit,
        progress: (Long) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        try {
            var target = URL(pinned.url)
            var redirects = 0
            while (true) {
                require(target.protocol == "https")
                val offset = file.length()
                val current = (target.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                    if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                }
                connection = current
                signal.setOnCancelListener { current.disconnect() }
                signal.throwIfCanceled()
                val code = current.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    require(++redirects <= 5)
                    target = URL(target, requireNotNull(current.getHeaderField("Location")))
                    current.disconnect()
                    continue
                }
                if (code >= 500) throw IOException("Model download failed: $code")
                require(code == 200 || code == 206 && offset > 0L) { "Model download failed: $code" }
                // A server that ignores Range answers 200 with the whole file; start over instead of appending.
                val append = code == 206
                var total = if (append) offset else 0L
                current.inputStream.use { input ->
                    FileOutputStream(file, append).use { output ->
                        val bytes = ByteArray(128 * 1024)
                        while (true) {
                            signal.throwIfCanceled()
                            checkpoint()
                            val read = input.read(bytes)
                            if (read < 0) break
                            total += read
                            require(total <= pinned.bytes) { "Model file exceeds pinned length" }
                            output.write(bytes, 0, read); progress(total)
                        }
                        output.fd.sync()
                    }
                }
                if (total < pinned.bytes) throw IOException("Model connection closed early")
                return
            }
        } finally {
            signal.setOnCancelListener(null)
            connection?.disconnect()
        }
    }
}
