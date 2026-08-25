package com.ugallery.feature.semanticsearch

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable

interface SemanticEmbeddingInference : Closeable {
    fun embedImage(bitmap: Bitmap): FloatArray
    fun embedText(text: String): FloatArray
}

class LiteRtSemanticEmbeddingInference(
    context: Context,
    model: InstalledSemanticModel,
    accelerator: Accelerator = Accelerator.CPU,
) : SemanticEmbeddingInference {
    private val environment = Environment.create(context.applicationContext)
    private val imageModel = CompiledModel.create(model.imageModel.absolutePath, CompiledModel.Options(accelerator), environment)
    private val textModel = CompiledModel.create(model.textModel.absolutePath, CompiledModel.Options(accelerator), environment)
    private val imageInput = imageModel.createInputBuffers().single()
    private val imageOutput = imageModel.createOutputBuffers().single()
    private val textInput = textModel.createInputBuffers().single()
    private val textOutput = textModel.createOutputBuffers().single()
    private val tokenizer = ClipTokenizer(model.vocabulary, model.merges)

    @Synchronized
    override fun embedImage(bitmap: Bitmap): FloatArray {
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
        textInput.writeInt(tokenizer.encode(text))
        textModel.run(listOf(textInput), listOf(textOutput))
        return textOutput.readFloat().validated()
    }

    private fun FloatArray.validated() = also {
        require(size == CompactSemanticEmbedding.Dimensions)
        require(all(Float::isFinite))
    }

    override fun close() {
        listOf<TensorBuffer>(imageOutput, imageInput, textOutput, textInput).forEach(TensorBuffer::close)
        imageModel.close()
        textModel.close()
        environment.close()
    }

    private companion object {
        const val ImagePixels = 224
        val Mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val Std = floatArrayOf(0.26862954f, 0.2613026f, 0.2757771f)
    }
}
