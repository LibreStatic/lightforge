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

/** One file of a model package: its [name] on disk, the pinned download and its GPU shader-cache key. */
class InpaintModelFile(val name: String, val pinned: PinnedModelFile, val cacheKey: String)

/**
 * A downloadable set of model files that are installed, compiled and deleted together.
 * [open] receives the installed files in the order of [files].
 */
class InpaintModelPackage(
    val id: String,
    val fingerprint: String,
    val workName: String,
    val gpuCacheName: String,
    val files: List<InpaintModelFile>,
    val open: (Context, List<File>, File) -> Inpainter,
) {
    val bytes: Long = files.sumOf { it.pinned.bytes }

    /** False while the pinned sizes and hashes are still placeholders, so nothing is offered to download. */
    val published: Boolean get() = files.none { it.pinned.sha256 == Unpublished }

    companion object {
        const val Unpublished = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

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

    /** The MI-GAN package; its paths, fingerprint and work name are what existing installs already use. */
    val Migan = InpaintModelPackage(
        id = "migan",
        fingerprint = Fingerprint,
        workName = "inpaint-model-download",
        gpuCacheName = "inpaint-gpu",
        files = listOf(InpaintModelFile("migan-512.tflite", Model, Fingerprint)),
        open = { context, files, cache -> MiganInpainter.open(context, files.single(), Fingerprint, cache) },
    )

    const val GenerativeFingerprint = "moebius-512-places2-fp16-v1"
    private const val GenerativeRelease =
        "https://github.com/LibreStatic/lightforge-models/releases/download/moebius-512-tflite-v1/"

    // TODO(moebius-release): these hashes are of the local export; publish exactly these bytes as the release assets.
    /** Moebius (hustvl, code Apache-2.0, weights MIT): U-Net, VAE encoder, VAE decoder and the fixed U-Net embeddings. */
    val Generative = InpaintModelPackage(
        id = "generative",
        fingerprint = GenerativeFingerprint,
        workName = "inpaint-generative-model-download",
        gpuCacheName = "inpaint-gpu-generative",
        files = listOf(
            InpaintModelFile(
                "moebius-unet.tflite",
                PinnedModelFile(
                    GenerativeRelease + "moebius-unet-fp16.tflite",
                    bytes = 511_391_568L,
                    sha256 = "d5d765103c607c82e55431a643b9d0462296eb00c4b898b1531443722e935cab",
                ),
                "$GenerativeFingerprint-unet",
            ),
            InpaintModelFile(
                "moebius-vae-encoder.tflite",
                PinnedModelFile(
                    GenerativeRelease + "moebius-vae-encoder-fp16.tflite",
                    bytes = 69_351_072L,
                    sha256 = "3b13a7b7a629a0204e3957d1664ab2d1e369d861c5572dfd274fb544b056365f",
                ),
                "$GenerativeFingerprint-vae-encoder",
            ),
            InpaintModelFile(
                "moebius-vae-decoder.tflite",
                PinnedModelFile(
                    GenerativeRelease + "moebius-vae-decoder-fp16.tflite",
                    bytes = 100_119_664L,
                    sha256 = "d2e1232775f0b03eb663b69ec767408bd8ddeb834207809f68ceee84f0591c99",
                ),
                "$GenerativeFingerprint-vae-decoder",
            ),
            InpaintModelFile(
                "moebius-cond.f32",
                PinnedModelFile(
                    GenerativeRelease + "moebius-cond.f32",
                    bytes = 245_760L,
                    sha256 = "1b46b6251205fd7473f9edb834073848df00fe83e3224ee1f4a2f1bc4bb52c8e",
                ),
                "$GenerativeFingerprint-cond",
            ),
        ),
        open = { context, files, cache -> MoebiusInpainter.open(context, files, GenerativeFingerprint, cache) },
    )

    val All = listOf(Migan, Generative)

    /** The package a worker was enqueued for; work enqueued before packages existed carries no id and is MI-GAN. */
    fun byId(id: String?): InpaintModelPackage = All.firstOrNull { it.id == id } ?: Migan
}

/** A downloaded inpainting model package in private app storage; only the pinned public model bytes are fetched. */
class InpaintModelStore(context: Context, val spec: InpaintModelPackage = InpaintModelCatalog.Migan) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "inpaint-models")
    private val directory = File(root, spec.fingerprint)
    private val partial = File(root, ".download-${spec.fingerprint}")
    private val gpuCache = File(app.cacheDir, spec.gpuCacheName)

    val packageBytes: Long get() = spec.bytes

    fun installed(): Boolean = spec.files.all { File(directory, it.name).length() == it.pinned.bytes }

    /** Bytes already on disk from an interrupted download; they are resumed, not fetched again. */
    fun partialBytes(): Long = spec.files.sumOf { File(partial, it.name).length().coerceAtMost(it.pinned.bytes) }

    /** Verifies the installed files and compiles them; [InpaintingSession] keeps the result on one thread. */
    fun openInpainter(signal: CancellationSignal = CancellationSignal()): Inpainter = synchronized(lock) {
        // Corrupt or tampered bytes never reach the native model parser.
        spec.files.forEach { PinnedModelDownload.verify(File(directory, it.name), it.pinned, signal) }
        spec.open(app, spec.files.map { File(directory, it.name) }, gpuCache).also { opened ->
            sessions.removeAll { it.second.get() == null }
            sessions += spec.id to WeakReference(opened)
        }
    }

    /**
     * Resumable download: continues each file from its partial bytes, verifies the pinned hashes, checks that
     * LiteRT can compile the package (which also warms the GPU shader cache), then publishes it atomically.
     * [progress] reports the bytes of the whole package.
     */
    fun download(signal: CancellationSignal = CancellationSignal(), checkpoint: () -> Unit = {}, progress: (Long) -> Unit = {}) {
        check(partial.isDirectory || partial.mkdirs())
        var completed = 0L
        for (file in spec.files) {
            val base = completed
            PinnedModelDownload.fetch(file.pinned, File(partial, file.name), signal, checkpoint) { progress(base + it) }
            completed += file.pinned.bytes
        }
        spec.open(app, spec.files.map { File(partial, it.name) }, gpuCache).close()
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
        // Only this package's sessions: the other package's model must stay usable.
        sessions.filter { it.first == spec.id }.forEach { it.second.get()?.close() }
        sessions.removeAll { it.first == spec.id }
    }

    companion object {
        private val lock = Any()
        private val sessions = mutableListOf<Pair<String, WeakReference<Inpainter>>>()
        private val revisions = MutableStateFlow(0)

        /** Changes whenever a model is installed or deleted in this process. */
        val changes: StateFlow<Int> get() = revisions
    }
}
