package com.ugallery.feature.petrecognition

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
    fun installed(): Boolean = synchronized(lock) {
        File(directory, "detector.tflite").length() == PetModelCatalog.DetectorBytes &&
            File(directory, "recognition.onnx").length() == PetModelCatalog.RecognitionBytes
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
                            require(!entry.isDirectory && entry.name in setOf("detector.tflite", "recognition.onnx") && seen.add(entry.name)) { "Unexpected or duplicate pet package entry" }
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
            require(seen == setOf("detector.tflite", "recognition.onnx")) { "Incomplete pet model package" }
        }
        progress(1f)
    }
    fun download(signal: CancellationSignal = CancellationSignal(), progress: (Float) -> Unit = {}) {
        prepare(signal) { staging ->
            var total = 0L
            for ((name, url, size) in listOf(Triple("detector.tflite", PetModelCatalog.DetectorUrl, PetModelCatalog.DetectorBytes),
                Triple("recognition.onnx", PetModelCatalog.RecognitionUrl, PetModelCatalog.RecognitionBytes))) {
                signal.throwIfCanceled()
                var connection: HttpURLConnection? = null
                try {
                    var target = URL(url)
                    var redirects = 0
                    while (true) {
                        require(target.protocol == "https")
                        val current = (target.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
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
                        require(code == 200) { "Pet model download failed: $code" }
                        current.inputStream.use { stream -> copy(stream, File(staging, name), size, signal) { bytes ->
                            progress(((total + bytes).toDouble() / PackageBytes).toFloat().coerceIn(0f, .95f))
                        } }
                        break
                    }
                } finally { signal.setOnCancelListener(null); connection?.disconnect() }
                total += size
            }
        }
        progress(1f)
    }
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
    private fun files(at: File) = PetModelFiles(File(at, "detector.tflite"), File(at, "recognition.onnx"))
    private companion object {
        val lock = Any()
        val epochs = mutableMapOf<String, Long>()
        val sessions = mutableMapOf<String, MutableList<WeakReference<PetRecognitionEngine>>>()
        const val PackageBytes = PetModelCatalog.DetectorBytes + PetModelCatalog.RecognitionBytes
    }
}
