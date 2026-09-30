package com.librestatic.lightforge.feature.petrecognition

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

/** Only pinned public model bytes enter this private model directory; source photos never leave device. */
class PetModelStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "pet-models")
    private val directory = File(root, PetModelCatalog.Fingerprint)
    private val partial = File(root, ".download-${PetModelCatalog.Fingerprint}")
    fun installed(): Boolean = synchronized(lock) {
        File(directory, "detector.tflite").length() == PetModelCatalog.DetectorBytes &&
            File(directory, "recognition.tflite").length() == PetModelCatalog.RecognitionBytes
    }
    fun openEngine(signal: CancellationSignal = CancellationSignal()): PetRecognitionEngine = synchronized(lock) {
        PetRecognitionEngine(app, files(directory), signal).also {
            sessions.getOrPut(directory.absolutePath) { mutableListOf() }.apply {
                removeAll { reference -> reference.get() == null }
                add(WeakReference(it))
            }
        }
    }
    fun delete() = synchronized(lock) {
        epochs[directory.absolutePath] = (epochs[directory.absolutePath] ?: 0L) + 1L
        closeSessions()
        check(!directory.exists() || directory.deleteRecursively()) { "Pet model deletion failed" }
        partial.deleteRecursively()
    }
    fun importPack(uri: Uri, signal: CancellationSignal = CancellationSignal(), progress: (Float) -> Unit = {}) {
        prepare(signal) { staging ->
            var total = 0L
            val seen = mutableSetOf<String>()
            val descriptor = requireNotNull(app.contentResolver.openAssetFileDescriptor(uri, "r", signal))
            descriptor.use { asset ->
                val input = asset.createInputStream()
                signal.setOnCancelListener { runCatching { input.close() } }
                try {
                    signal.throwIfCanceled()
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            signal.throwIfCanceled()
                            val entry = zip.nextEntry ?: break
                            require(!entry.isDirectory && entry.name in setOf("detector.tflite", "recognition.tflite") && seen.add(entry.name)) { "Unexpected or duplicate pet package entry" }
                            val limit = if (entry.name == "detector.tflite") PetModelCatalog.DetectorBytes else PetModelCatalog.RecognitionBytes
                            copy(zip, File(staging, entry.name), limit, signal) { bytes ->
                                progress(((total + bytes).toDouble() / PackageBytes).toFloat().coerceIn(0f, .95f))
                            }
                            total += limit
                            zip.closeEntry()
                        }
                    }
                } finally { signal.setOnCancelListener(null) }
            }
            require(seen == setOf("detector.tflite", "recognition.tflite")) { "Incomplete pet model package" }
        }
        progress(1f)
    }
    /** Bytes already on disk from an interrupted download; they are resumed, not fetched again. */
    fun partialBytes(): Long = sources().sumOf { (name, _, size) -> File(partial, name).length().coerceAtMost(size) }

    /** Drops a paused download's partial bytes, for example after the user cancels it. */
    fun discardPartialDownload() = synchronized(lock) { partial.deleteRecursively() }

    /** True when an older model generation is installed; the new one downloads under the model download policy. */
    fun needsUpdate(): Boolean = !installed() && File(directory, "recognition.onnx").isFile

    /**
     * Resumable download: each file continues from its partial bytes with a Range request, so a dropped
     * connection or a paused worker never restarts it. [checkpoint] may throw to pause between reads.
     */
    fun download(signal: CancellationSignal = CancellationSignal(), checkpoint: () -> Unit = {}, progress: (Long) -> Unit = {}) {
        check(partial.isDirectory || partial.mkdirs())
        var completed = 0L
        for ((name, url, size) in sources()) {
            val file = File(partial, name)
            if (file.length() > size) check(file.delete())
            if (file.length() < size) fetch(url, file, size, signal, checkpoint) { bytes -> progress(completed + bytes) }
            completed += size
            progress(completed)
        }
        for ((name, _, size) in sources()) {
            val file = File(partial, name)
            // A corrupt file cannot be resumed; drop it so the next attempt fetches it again.
            runCatching { PetModelCatalog.verify(file, size, sha(name), signal) }
                .onFailure { if (it is IllegalArgumentException) file.delete(); throw it }
        }
        prepare(signal) { staging ->
            for ((name, _, _) in sources()) check(File(partial, name).renameTo(File(staging, name))) { "Pet model staging failed" }
        }
        partial.deleteRecursively()
        progress(PackageBytes)
    }
    private fun fetch(url: String, file: File, size: Long, signal: CancellationSignal, checkpoint: () -> Unit, progress: (Long) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            var target = URL(url)
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
                require(code == 200 || code == 206 && offset > 0L) { "Pet model download failed: $code" }
                // A server that ignores Range answers 200 with the whole file; start over instead of appending.
                val append = code == 206
                var total = if (append) offset else 0L
                current.inputStream.use { input ->
                    java.io.FileOutputStream(file, append).use { output ->
                        val bytes = ByteArray(128 * 1024)
                        while (true) {
                            signal.throwIfCanceled()
                            checkpoint()
                            val read = input.read(bytes)
                            if (read < 0) break
                            total += read
                            require(total <= size) { "Pet model file exceeds pinned length" }
                            output.write(bytes, 0, read); progress(total)
                        }
                        output.fd.sync()
                    }
                }
                if (total < size) throw java.io.IOException("Pet model connection closed early")
                return
            }
        } finally { signal.setOnCancelListener(null); connection?.disconnect() }
    }
    private fun sources() = listOf(
        Triple("detector.tflite", PetModelCatalog.DetectorUrl, PetModelCatalog.DetectorBytes),
        Triple("recognition.tflite", PetModelCatalog.RecognitionUrl, PetModelCatalog.RecognitionBytes),
    )
    private fun sha(name: String) = if (name == "detector.tflite") PetModelCatalog.DetectorSha256 else PetModelCatalog.RecognitionSha256
    private fun prepare(signal: CancellationSignal, write: (File) -> Unit) {
        val expectedEpoch = synchronized(lock) { epochs[directory.absolutePath] ?: 0L }
        check(root.isDirectory || root.mkdirs())
        val staging = File(root, ".install-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            write(staging)
            files(staging).verify(signal)
            // Validate actual tensors/native compatibility before the directory can become installed.
            PetRecognitionEngine(app, files(staging), signal).use { engine ->
                val probe = android.graphics.Bitmap.createBitmap(224, 224, android.graphics.Bitmap.Config.ARGB_8888)
                try { engine.embedCrop(probe, signal) } finally { probe.recycle() }
            }
            synchronized(lock) {
                signal.throwIfCanceled()
                check((epochs[directory.absolutePath] ?: 0L) == expectedEpoch) { "Pet model installation superseded by deletion or another installation" }
                closeSessions()
                val previous = File(root, ".previous-${UUID.randomUUID()}")
                val hadPrevious = directory.exists()
                if (hadPrevious) check(directory.renameTo(previous))
                try { check(staging.renameTo(directory)) { "Pet model publication failed" } }
                catch (failure: Throwable) {
                    if (hadPrevious && !previous.renameTo(directory)) failure.addSuppressed(IllegalStateException("Previous pet model restoration failed"))
                    throw failure
                }
                epochs[directory.absolutePath] = expectedEpoch + 1L
                if (hadPrevious) previous.deleteRecursively()
            }
        } finally { staging.deleteRecursively() }
    }
    private fun closeSessions() {
        sessions.remove(directory.absolutePath)?.forEach { it.get()?.close() }
    }
    private fun copy(input: InputStream, destination: File, limit: Long, signal: CancellationSignal, progress: (Long) -> Unit) {
        var total = 0L
        destination.outputStream().use { output ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                signal.throwIfCanceled()
                val read = input.read(bytes)
                if (read < 0) break
                total += read
                require(total <= limit) { "Pet model file exceeds pinned length" }
                output.write(bytes, 0, read); progress(total)
            }
            output.fd.sync()
        }
        require(total == limit) { "Incomplete pet model file" }
    }
    private fun files(at: File) = PetModelFiles(File(at, "detector.tflite"), File(at, "recognition.tflite"))
    companion object {
        private val lock = Any()
        private val epochs = mutableMapOf<String, Long>()
        private val sessions = mutableMapOf<String, MutableList<WeakReference<PetRecognitionEngine>>>()
        const val PackageBytes = PetModelCatalog.DetectorBytes + PetModelCatalog.RecognitionBytes
    }
}
