package com.ugallery.feature.semanticsearch

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import com.google.ai.edge.litert.TensorType

interface SemanticEmbeddingInference : Closeable {
    fun embedImage(bitmap: Bitmap): FloatArray
    fun embedText(text: String): FloatArray
}

class LiteRtSemanticEmbeddingInference(
    context: Context,
    model: InstalledSemanticModel,
    accelerator: Accelerator = Accelerator.CPU,
) : SemanticEmbeddingInference {
    private val lease = SemanticModelAccess.acquire(model.directory)
    private var closed = false
    private val resources = mutableListOf<AutoCloseable>()
    private val environment: Environment
    private val imageModel: CompiledModel
    private val textModel: CompiledModel
    private val imageInput: TensorBuffer
    private val imageOutput: TensorBuffer
    private val textInput: TensorBuffer
    private val textOutput: TensorBuffer
    private val tokenizer: ClipTokenizer
    init {
        try {
            SemanticModelIntegrity.verify(model)
            environment = Environment.create(context.applicationContext).also { resources += it }
            imageModel = CompiledModel.create(model.imageModel.absolutePath, CompiledModel.Options(accelerator), environment).also { resources += it }
            textModel = CompiledModel.create(model.textModel.absolutePath, CompiledModel.Options(accelerator), environment).also { resources += it }
            fun validate(compiled: CompiledModel, dimensions: List<Int>, type: TensorType.ElementType) {
                val input = compiled.getInputTensorType("args_0", "serving_default")
                val output = compiled.getOutputTensorType("output_0", "serving_default")
                require(requireNotNull(input.layout).dimensions == dimensions && input.elementType == type) { "Incompatible semantic input tensor" }
                require(requireNotNull(output.layout).dimensions == listOf(1, 512) && output.elementType == TensorType.ElementType.FLOAT) { "Incompatible semantic output tensor" }
            }
            validate(imageModel, listOf(1, 224, 224, 3), TensorType.ElementType.FLOAT)
            validate(textModel, listOf(1, 77), TensorType.ElementType.INT)
            fun input(compiled: CompiledModel) = compiled.createInputBuffers().also { resources.addAll(it) }.single()
            fun output(compiled: CompiledModel) = compiled.createOutputBuffers().also { resources.addAll(it) }.single()
            imageInput = input(imageModel); imageOutput = output(imageModel)
            textInput = input(textModel); textOutput = output(textModel)
            tokenizer = ClipTokenizer(model.vocabulary, model.merges)
            lease.onRevoked { close() }
            lease.checkCurrent()
        } catch (error: Throwable) {
            resources.asReversed().forEach { runCatching { it.close() } }
            lease.close()
            throw error
        }
    }

    @Synchronized
    override fun embedImage(bitmap: Bitmap): FloatArray {
        lease.checkCurrent()
        check(!closed)
        val scaled = centerCrop(bitmap)
        try {
            val pixels = IntArray(ImagePixels * ImagePixels)
            scaled.getPixels(pixels, 0, ImagePixels, 0, 0, ImagePixels, ImagePixels)
            val input = FloatArray(pixels.size * 3)
            pixels.forEachIndexed { index, pixel ->
                val offset = index * 3
                input[offset] = (((pixel ushr 16) and 0xff) / 255f - Mean[0]) / Std[0]
                input[offset + 1] = (((pixel ushr 8) and 0xff) / 255f - Mean[1]) / Std[1]
                input[offset + 2] = ((pixel and 0xff) / 255f - Mean[2]) / Std[2]
            }
            imageInput.writeFloat(input)
            imageModel.run(listOf(imageInput), listOf(imageOutput))
            return imageOutput.readFloat().validated()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun centerCrop(source: Bitmap): Bitmap {
        if (source.width == ImagePixels && source.height == ImagePixels) return source
        val scale = maxOf(ImagePixels.toFloat() / source.width, ImagePixels.toFloat() / source.height)
        val width = kotlin.math.ceil(source.width * scale).toInt()
        val height = kotlin.math.ceil(source.height * scale).toInt()
        val resized = Bitmap.createScaledBitmap(source, width, height, true)
        if (width == ImagePixels && height == ImagePixels) return resized
        val cropped = Bitmap.createBitmap(
            resized,
            (width - ImagePixels) / 2,
            (height - ImagePixels) / 2,
            ImagePixels,
            ImagePixels,
        )
        resized.recycle()
        return cropped
    }

    @Synchronized
    override fun embedText(text: String): FloatArray {
        lease.checkCurrent()
        check(!closed)
        textInput.writeInt(tokenizer.encode(text))
        textModel.run(listOf(textInput), listOf(textOutput))
        return textOutput.readFloat().validated()
    }

    private fun FloatArray.validated() = also {
        require(size == CompactSemanticEmbedding.Dimensions)
        require(all(Float::isFinite))
        require(any { kotlin.math.abs(it) > 1e-8f })
        lease.checkCurrent()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
        lease.close()
    }

    private companion object {
        const val ImagePixels = 224
        val Mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val Std = floatArrayOf(0.26862954f, 0.2613026f, 0.2757771f)
    }
}
