package com.librestatic.lightforge.feature.objecteraser

import android.content.Context
import android.os.CancellationSignal
import com.librestatic.lightforge.core.ml.PinnedModelDownload
import com.librestatic.lightforge.core.ml.PinnedModelFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

object InpaintModelCatalog {
    /**
     * MI-GAN 512 Places2 generator (Picsart AI Research, MIT), converted to LiteRT float16 from
     * andraniksargsyan/migan migan.onnx (sha256 593eba0b…c5ae); its output is at least 50 dB PSNR from the ONNX generator's.
     */
    const val Fingerprint = "migan-512-places2-593eba0b-fp16-v1"
    val Model = PinnedModelFile(
        url = "https://github.com/LibreStatic/lightforge-models/releases/download/migan-512-tflite-v1/migan-512-fp16.tflite",
        bytes = 14_048_880L,
        sha256 = "a27126ba65208314b113ca82fa70d9a9e119a74237037cb4cc63609a3adc4eeb",
    )
}

/** The downloaded inpainting model in private app storage; only the pinned public model bytes are fetched. */
class InpaintModelStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "inpaint-models")
    private val directory = File(root, InpaintModelCatalog.Fingerprint)
    private val model = File(directory, ModelName)
    private val partial = File(root, ".download-${InpaintModelCatalog.Fingerprint}")
    private val gpuCache = File(app.cacheDir, "inpaint-gpu")

    fun installed(): Boolean = model.length() == InpaintModelCatalog.Model.bytes

    /** Bytes already on disk from an interrupted download; they are resumed, not fetched again. */
    fun partialBytes(): Long = File(partial, ModelName).length().coerceAtMost(InpaintModelCatalog.Model.bytes)

    /** Verifies the installed file and compiles it; [InpaintingSession] keeps the result on one thread. */
    fun openInpainter(signal: CancellationSignal = CancellationSignal()): MiganInpainter = synchronized(lock) {
        // Corrupt or tampered bytes never reach the native model parser.
        PinnedModelDownload.verify(model, InpaintModelCatalog.Model, signal)
        MiganInpainter.open(app, model, InpaintModelCatalog.Fingerprint, gpuCache).also { opened ->
            sessions.removeAll { it.get() == null }
            sessions += WeakReference(opened)
        }
    }

    /**
     * Resumable download: continues from the partial bytes, verifies the pinned hash, checks that LiteRT can
     * compile the model (which also warms the GPU shader cache), then publishes it atomically.
     */
    fun download(signal: CancellationSignal = CancellationSignal(), checkpoint: () -> Unit = {}, progress: (Long) -> Unit = {}) {
        check(partial.isDirectory || partial.mkdirs())
        val staged = File(partial, ModelName)
        PinnedModelDownload.fetch(InpaintModelCatalog.Model, staged, signal, checkpoint, progress)
        MiganInpainter.open(app, staged, InpaintModelCatalog.Fingerprint, gpuCache).close()
        signal.throwIfCanceled()
        synchronized(lock) {
            closeSessions()
            val previous = File(root, ".previous-${UUID.randomUUID()}")
            val hadPrevious = directory.exists()
            if (hadPrevious) check(directory.renameTo(previous))
            check(partial.renameTo(directory)) { "Inpainting model publication failed" }
            if (hadPrevious) previous.deleteRecursively()
            revisions.value++
        }
    }

    /** Drops a paused download's partial bytes, for example after the user cancels it. */
    fun discardPartialDownload() = synchronized(lock) { partial.deleteRecursively() }

    fun delete() = synchronized(lock) {
        closeSessions()
        check(!directory.exists() || directory.deleteRecursively()) { "Inpainting model deletion failed" }
        partial.deleteRecursively()
        gpuCache.deleteRecursively()
        revisions.value++
    }

    private fun closeSessions() {
        sessions.forEach { it.get()?.close() }
        sessions.clear()
    }

    companion object {
        private const val ModelName = "migan-512.tflite"
        private val lock = Any()
        private val sessions = mutableListOf<WeakReference<MiganInpainter>>()
        private val revisions = MutableStateFlow(0)

        /** Changes whenever a model is installed or deleted in this process. */
        val changes: StateFlow<Int> get() = revisions
        val PackageBytes get() = InpaintModelCatalog.Model.bytes
    }
}
