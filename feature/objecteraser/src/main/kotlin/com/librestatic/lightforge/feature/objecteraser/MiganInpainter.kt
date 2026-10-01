package com.librestatic.lightforge.feature.objecteraser

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.Closeable
import java.io.File

/**
 * MI-GAN 512 inpainting generator on LiteRT, on the GPU when the device has one and on the CPU otherwise.
 * Not thread-safe across accelerators' contexts: keep one instance on one thread ([InpaintingSession] does).
 */
class MiganInpainter private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    val accelerator: Accelerator,
) : Closeable {
    private val input: TensorBuffer
    private val output: TensorBuffer
    private var closed = false

    init {
        var created: TensorBuffer? = null
        try {
            fun dims(type: TensorType) = requireNotNull(type.layout).dimensions
            val inputType = model.getInputTensorType(Input, Signature)
            val outputType = model.getOutputTensorType(Output, Signature)
            val size = InpaintPlan.ModelSize
            require(inputType.elementType == TensorType.ElementType.FLOAT && dims(inputType) == listOf(1, size, size, 4))
            require(outputType.elementType == TensorType.ElementType.FLOAT && dims(outputType) == listOf(1, size, size, 3))
            input = model.createInputBuffer(Input, Signature).also { created = it }
            output = model.createOutputBuffer(Output, Signature)
        } catch (failure: Throwable) {
            runCatching { created?.close() }
            runCatching { model.close() }
            throw failure
        }
    }

    /** Fills the pixels of the 512x512 [argb] image where [keep] is false; returns the whole generated image. */
    @Synchronized
    fun inpaint(argb: IntArray, keep: BooleanArray): IntArray {
        check(!closed) { "Inpainter closed" }
        input.writeFloat(InpaintPlan.encode(argb, keep))
        model.run(mapOf(Input to input), mapOf(Output to output), Signature)
        return InpaintPlan.decode(output.readFloat())
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { model.close() }
        runCatching { environment.close() }
    }

    companion object {
        private const val Signature = "serving_default"
        private const val Input = "input"
        private const val Output = "output"

        /**
         * Compiles [model] for the GPU, caching the compiled shaders under [cacheDir] so later sessions start
         * fast, and falls back to the CPU when no GPU is available or compilation fails.
         */
        fun open(context: Context, model: File, cacheKey: String, cacheDir: File, preferGpu: Boolean = true): MiganInpainter {
            val environment = Environment.create(context.applicationContext)
            try {
                if (preferGpu && Accelerator.GPU in environment.getAvailableAccelerators()) {
                    val gpu = runCatching {
                        check(cacheDir.isDirectory || cacheDir.mkdirs())
                        val options = CompiledModel.Options(Accelerator.GPU).apply {
                            gpuOptions = CompiledModel.GpuOptions(
                                serializationDir = cacheDir.absolutePath,
                                modelCacheKey = cacheKey,
                                serializeProgramCache = true,
                            )
                        }
                        MiganInpainter(environment, CompiledModel.create(model.absolutePath, options, environment), Accelerator.GPU)
                    }.getOrNull()
                    if (gpu != null) return gpu
                }
                val cpu = CompiledModel.create(model.absolutePath, CompiledModel.Options(Accelerator.CPU), environment)
                return MiganInpainter(environment, cpu, Accelerator.CPU)
            } catch (failure: Throwable) {
                runCatching { environment.close() }
                throw failure
            }
        }
    }
}
