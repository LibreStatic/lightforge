package com.ugallery.feature.petrecognition

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.system.Os
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.ceil

/** Real local multi-animal detector + individual re-ID. Run on a worker thread, never the UI. */
class PetRecognitionEngine(context: Context, models: PetModelFiles, signal: CancellationSignal = CancellationSignal()) : Closeable {
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false
    private val detector: CompiledModel
    private val detectorInput: TensorBuffer
    private val detectorBoxes: TensorBuffer
    private val detectorScores: TensorBuffer
    private val recognition: OrtSession
    private val ort: OrtEnvironment
    private val labeler: ImageLabeler

    init {
        try {
            models.verify(signal) // Untrusted/corrupt bytes never reach a native parser.
            // Must precede ANY ORT environment/session/native initialization, including SessionOptions.
            Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
            check(Os.getenv("ORT_DISABLE_TELEMETRY") == "1")
            ort = OrtEnvironment.getEnvironment()
            ort.setTelemetry(false)
            val options = OrtSession.SessionOptions().also { resources += it }
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            recognition = ort.createSession(models.recognition.absolutePath, options).also { resources += it }
            val input = requireNotNull(recognition.inputInfo["input"]?.info as? TensorInfo)
            val output = requireNotNull(recognition.outputInfo["embedding"]?.info as? TensorInfo)
            require(input.type == OnnxJavaType.FLOAT && input.shape.drop(1) == listOf(3L, 224L, 224L))
            require(output.type == OnnxJavaType.FLOAT && output.shape.drop(1) == listOf(512L))
            val environment = Environment.create(context.applicationContext).also { resources += it }
            detector = CompiledModel.create(models.detector.absolutePath, CompiledModel.Options(Accelerator.CPU), environment).also { resources += it }
            fun checkTensor(name: String, dimensions: List<Int>, isInput: Boolean) {
                val type = if (isInput) detector.getInputTensorType(name, "serving_default") else detector.getOutputTensorType(name, "serving_default")
                require(type.elementType == TensorType.ElementType.FLOAT && requireNotNull(type.layout).dimensions == dimensions)
            }
            checkTensor("images", listOf(1, 320, 320, 3), true)
            checkTensor("output_0", listOf(1, PetDetectionDecoder.Anchors, 4), false)
            checkTensor("output_1", listOf(1, PetDetectionDecoder.Anchors, PetDetectionDecoder.Classes), false)
            detectorInput = detector.createInputBuffer("images", "serving_default").also { resources += it }
            detectorBoxes = detector.createOutputBuffer("output_0", "serving_default").also { resources += it }
            detectorScores = detector.createOutputBuffer("output_1", "serving_default").also { resources += it }
            labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.2f).build())
            resources += AutoCloseable { labeler.close() }
            signal.throwIfCanceled()
        } catch (failure: Throwable) {
            resources.asReversed().forEach { runCatching { it.close() } }
            throw failure
        }
    }

    @Synchronized
    fun analyze(bitmap: Bitmap, signal: CancellationSignal = CancellationSignal()): List<PetAnalyzedObservation> {
        check(!closed); signal.throwIfCanceled()
        val scaled = Bitmap.createScaledBitmap(bitmap, 320, 320, true)
        try {
            val pixels = IntArray(320 * 320)
            scaled.getPixels(pixels, 0, 320, 0, 0, 320, 320)
            val input = FloatArray(pixels.size * 3)
            pixels.forEachIndexed { i, p ->
                input[i * 3] = (((p ushr 16) and 255) - 127.5f) / 127.5f
                input[i * 3 + 1] = (((p ushr 8) and 255) - 127.5f) / 127.5f
                input[i * 3 + 2] = ((p and 255) - 127.5f) / 127.5f
            }
            detectorInput.writeFloat(input)
            detector.run(mapOf("images" to detectorInput), mapOf("output_0" to detectorBoxes, "output_1" to detectorScores), "serving_default")
        } finally { if (scaled !== bitmap) scaled.recycle() }
        signal.throwIfCanceled()
        return PetDetectionDecoder.decode(detectorBoxes.readFloat(), detectorScores.readFloat()).map { detected ->
            signal.throwIfCanceled()
            val crop = crop(bitmap, detected.box)
            try {
                val labels = Tasks.await(labeler.process(InputImage.fromBitmap(crop, 0)), 30, TimeUnit.SECONDS)
                signal.throwIfCanceled()
                fun score(name: String) = labels.filter { it.text.equals(name, ignoreCase = true) }.maxOfOrNull { it.confidence } ?: 0f
                val cat = score("Cat"); val dog = score("Dog")
                val classified = if (cat >= dog) PetSpecies.Cat else PetSpecies.Dog
                val species = if (maxOf(cat, dog) >= 0.7f && kotlin.math.abs(cat - dog) >= 0.2f &&
                    classified == detected.species && kotlin.math.abs(detected.catScore - detected.dogScore) >= 0.1f) classified
                    else PetSpecies.Uncertain
                PetAnalyzedObservation(UUID.randomUUID().toString(), detected.box, species, detected.score, embedCrop(crop, signal))
            } finally { if (crop !== bitmap) crop.recycle() }
        }
    }

    /** Exposed for fixed identity corpus evaluation; this is the same production crop encoder. */
    @Synchronized
    fun embedCrop(bitmap: Bitmap, signal: CancellationSignal = CancellationSignal()): FloatArray {
        check(!closed); signal.throwIfCanceled()
        val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        try {
            val pixels = IntArray(224 * 224)
            scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            val input = FloatArray(3 * 224 * 224)
            val means = floatArrayOf(.485f, .456f, .406f); val stds = floatArrayOf(.229f, .224f, .225f)
            pixels.forEachIndexed { index, pixel ->
                for (channel in 0..2) input[channel * pixels.size + index] =
                    ((((pixel ushr (16 - channel * 8)) and 255) / 255f) - means[channel]) / stds[channel]
            }
            OnnxTensor.createTensor(ort, FloatBuffer.wrap(input), longArrayOf(1, 3, 224, 224)).use { tensor ->
                OrtSession.RunOptions().use { options ->
                    signal.setOnCancelListener { options.setTerminate(true) }
                    try {
                        signal.throwIfCanceled()
                        recognition.run(mapOf("input" to tensor), options).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val vectors = result.get("embedding").orElseThrow().value as Array<FloatArray>
                            val vector = vectors.single().copyOf()
                            require(vector.size == 512 && vector.all(Float::isFinite))
                            require(kotlin.math.abs(vector.sumOf { (it * it).toDouble() } - 1.0) <= 0.02)
                            signal.throwIfCanceled()
                            return vector
                        }
                    } finally { signal.setOnCancelListener(null) }
                }
            }
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    companion object {
        fun crop(bitmap: Bitmap, box: PetBox): Bitmap {
            val left = floor(box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = floor(box.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = ceil(box.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = ceil(box.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }
    }
}
