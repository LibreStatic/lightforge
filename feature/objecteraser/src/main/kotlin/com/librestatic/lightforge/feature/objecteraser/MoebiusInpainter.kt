package com.librestatic.lightforge.feature.objecteraser

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.Closeable
import java.io.File

/** The three compiled Moebius networks; closed together. */
private class MoebiusModels(
    val environment: Environment,
    val unet: CompiledModel,
    val encoder: CompiledModel,
    val decoder: CompiledModel,
    val accelerator: Accelerator,
) : Closeable {
    fun closeNetworks() {
        runCatching { unet.close() }
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }

    override fun close() {
        closeNetworks()
        runCatching { environment.close() }
    }
}

/**
 * Moebius diffusion inpainting on LiteRT: VAE encoder, DDIM sampling with the UNet, VAE decoder, on the GPU (the CPU
 * takes minutes per erase, so [open] refuses it and the session falls back to MI-GAN). The sampling math lives in
 * [MoebiusSampler]. Keep one instance on one thread ([InpaintingSession] does); finished passes are memoized.
 */
class MoebiusInpainter private constructor(
    private val models: MoebiusModels,
    /** The unconditional then the conditional U-Net embeddings, [MoebiusConfig.ConditionTokens] x width each. */
    private val conditions: Pair<FloatArray, FloatArray>,
    private val sampler: MoebiusSampler = MoebiusSampler(),
    private val init: MoebiusInit = MoebiusConfig.Init,
    private val guidance: Double? = MoebiusConfig.Guidance,
) : Inpainter {
    override val accelerator: Accelerator get() = models.accelerator
    private val buffers = mutableListOf<TensorBuffer>()
    private val unetX: TensorBuffer
    private val unetT: TensorBuffer
    private val unetCondition: TensorBuffer
    private val unetOut: TensorBuffer
    private val encoderIn: TensorBuffer
    private val encoderOut: TensorBuffer
    private val decoderIn: TensorBuffer
    private val decoderOut: TensorBuffer
    private val memo = InpaintMemo()
    private var closed = false

    init {
        try {
            val size = InpaintPlan.ModelSize
            val latent = MoebiusConfig.LatentSize
            val channels = MoebiusConfig.LatentChannels
            fun input(model: CompiledModel, name: String, vararg shape: Int) =
                requireShape(model.getInputTensorType(name, MoebiusConfig.Signature), shape.toList())
                    .let { model.createInputBuffer(name, MoebiusConfig.Signature) }.also { buffers += it }
            fun output(model: CompiledModel, name: String, vararg shape: Int) =
                requireShape(model.getOutputTensorType(name, MoebiusConfig.Signature), shape.toList())
                    .let { model.createOutputBuffer(name, MoebiusConfig.Signature) }.also { buffers += it }
            unetX = input(models.unet, MoebiusConfig.UnetInput, 1, latent, latent, MoebiusConfig.UnetChannels)
            unetT = input(models.unet, MoebiusConfig.UnetTimestep, 1)
            unetCondition = input(
                models.unet, MoebiusConfig.UnetCondition, 1, MoebiusConfig.ConditionTokens, MoebiusConfig.ConditionWidth,
            )
            unetOut = output(models.unet, MoebiusConfig.UnetOutput, 1, latent, latent, channels)
            encoderIn = input(models.encoder, MoebiusConfig.EncoderInput, 1, size, size, 3)
            encoderOut = output(models.encoder, MoebiusConfig.EncoderOutput, 1, latent, latent, channels)
            decoderIn = input(models.decoder, MoebiusConfig.DecoderInput, 1, latent, latent, channels)
            decoderOut = output(models.decoder, MoebiusConfig.DecoderOutput, 1, size, size, 3)
        } catch (failure: Throwable) {
            buffers.forEach { runCatching { it.close() } }
            models.closeNetworks()
            throw failure
        }
    }

    private fun requireShape(type: TensorType, shape: List<Int>) {
        require(type.elementType == TensorType.ElementType.FLOAT && requireNotNull(type.layout).dimensions == shape) {
            "Unexpected Moebius tensor $type, expected float $shape"
        }
    }

    @Synchronized
    override fun inpaint(argb: IntArray, keep: BooleanArray, seed: Long, ensureActive: () -> Unit): IntArray {
        check(!closed) { "Inpainter closed" }
        return memo.getOrPut(argb, keep, seed) { sample(argb, keep, seed, ensureActive) }
    }

    private fun sample(argb: IntArray, keep: BooleanArray, seed: Long, ensureActive: () -> Unit): IntArray {
        val maskedLatent = encode(sampler.rgbInput(argb, keep, blankHole = true))
        ensureActive()
        val latent = if (init == MoebiusInit.ORIGINAL) encode(sampler.rgbInput(argb, keep, blankHole = false)) else maskedLatent
        val mask = sampler.latentMask(keep)
        var x = sampler.start(latent, sampler.noise(seed))
        for (t in sampler.timesteps) {
            ensureActive()
            unetX.writeFloat(sampler.unetInput(x, mask, maskedLatent))
            unetT.writeFloat(floatArrayOf(t.toFloat()))
            val conditional = predictNoise(conditions.second)
            val eps = guidance?.let { scale ->
                ensureActive()
                sampler.guide(predictNoise(conditions.first), conditional, scale)
            } ?: conditional
            x = sampler.step(eps, t, x)
        }
        ensureActive()
        decoderIn.writeFloat(x)
        models.decoder.run(
            mapOf(MoebiusConfig.DecoderInput to decoderIn), mapOf(MoebiusConfig.DecoderOutput to decoderOut), MoebiusConfig.Signature,
        )
        return sampler.toArgb(decoderOut.readFloat())
    }

    /** One U-Net branch on the input already written to [unetX] and [unetT]. */
    private fun predictNoise(condition: FloatArray): FloatArray {
        unetCondition.writeFloat(condition)
        models.unet.run(
            mapOf(MoebiusConfig.UnetInput to unetX, MoebiusConfig.UnetTimestep to unetT, MoebiusConfig.UnetCondition to unetCondition),
            mapOf(MoebiusConfig.UnetOutput to unetOut),
            MoebiusConfig.Signature,
        )
        return unetOut.readFloat()
    }

    private fun encode(rgb: FloatArray): FloatArray {
        encoderIn.writeFloat(rgb)
        models.encoder.run(
            mapOf(MoebiusConfig.EncoderInput to encoderIn), mapOf(MoebiusConfig.EncoderOutput to encoderOut), MoebiusConfig.Signature,
        )
        return encoderOut.readFloat()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        memo.clear()
        buffers.forEach { runCatching { it.close() } }
        models.close()
    }

    companion object {
        /**
         * Compiles the networks (U-Net, VAE encoder, VAE decoder, then the condition file; in that order in [files])
         * on one environment, with GPU shader caches under [cacheDir], one key per file derived from [cacheKey].
         * Throws when the GPU is unavailable: on the CPU an erase takes minutes, so only tests ask for [Accelerator.CPU].
         */
        fun open(
            context: Context,
            files: List<File>,
            cacheKey: String,
            cacheDir: File,
            accelerator: Accelerator = Accelerator.GPU,
            sampler: MoebiusSampler = MoebiusSampler(),
        ): MoebiusInpainter {
            require(files.size == 4)
            val conditions = readConditions(files[3])
            val environment = Environment.create(context.applicationContext)
            try {
                if (accelerator == Accelerator.GPU) {
                    check(Accelerator.GPU in environment.getAvailableAccelerators()) { "The generative eraser needs the GPU" }
                    check(cacheDir.isDirectory || cacheDir.mkdirs())
                }
                return MoebiusInpainter(compile(environment, files.subList(0, 3), accelerator, cacheKey, cacheDir), conditions, sampler)
            } catch (failure: Throwable) {
                runCatching { environment.close() }
                throw failure
            }
        }

        private fun readConditions(file: File): Pair<FloatArray, FloatArray> {
            val size = MoebiusConfig.ConditionTokens * MoebiusConfig.ConditionWidth
            val buffer = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            require(buffer.remaining() == 2 * size) { "Unexpected condition file size" }
            val unconditional = FloatArray(size).also { buffer.get(it) }
            val conditional = FloatArray(size).also { buffer.get(it) }
            return unconditional to conditional
        }

        private fun compile(environment: Environment, files: List<File>, accelerator: Accelerator, cacheKey: String, cacheDir: File): MoebiusModels {
            val compiled = mutableListOf<CompiledModel>()
            try {
                files.forEachIndexed { index, file ->
                    val options = CompiledModel.Options(accelerator)
                    if (accelerator == Accelerator.GPU) {
                        options.gpuOptions = CompiledModel.GpuOptions(
                            // The VAE's 256 and 512 px stages carry activations in the thousands: fp16 overflows there
                            // on real photos (NaN, a black fill). Full precision costs the decoder ~0.3 s; the U-Net
                            // stays fp16.
                            precision = if (index == 0) null else CompiledModel.GpuOptions.Precision.FP32,
                            serializationDir = cacheDir.absolutePath,
                            // "p2": the VAE programs cached before the precision change were fp16.
                            modelCacheKey = "$cacheKey-$index-p2",
                            serializeProgramCache = true,
                        )
                    }
                    compiled += CompiledModel.create(file.absolutePath, options, environment)
                }
            } catch (failure: Throwable) {
                compiled.forEach { runCatching { it.close() } }
                throw failure
            }
            return MoebiusModels(environment, compiled[0], compiled[1], compiled[2], accelerator)
        }
    }
}
