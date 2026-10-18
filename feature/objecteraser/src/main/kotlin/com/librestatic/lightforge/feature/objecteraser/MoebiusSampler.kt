package com.librestatic.lightforge.feature.objecteraser

import java.security.MessageDigest
import java.util.Random
import kotlin.math.sqrt

/** Where the sampled latent starts from: the clean photo's latent, or the latent of the photo with the hole blanked. */
enum class MoebiusInit { ORIGINAL, MASKED }

/** Defaults and model contract of the generative eraser; tune the defaults here. */
object MoebiusConfig {
    /** 6 schedule steps (5 sampled at the default strength): on brush-sized holes as good as 20, at a quarter of the cost. */
    const val Steps = 6
    const val Strength = 0.99
    const val NoiseOffset = 0.0357
    val Init = MoebiusInit.ORIGINAL

    /** Classifier-free guidance scale; each step runs the U-Net once per branch. Null runs the conditional only. */
    val Guidance: Double? = 2.5

    /** Fixed base of the per-pass seeds, so a re-run of the same erase reproduces the preview. */
    const val BaseSeed = 0x4C46_4D4F_4542_4955L

    /** Seed for pass [index] of an erase: deterministic, and different for each pass. */
    fun passSeed(index: Int): Long = BaseSeed xor (index + 1) * -0x61c8864680b583ebL

    const val Signature = "serving_default"
    const val UnetInput = "x"
    const val UnetTimestep = "t"
    const val UnetCondition = "cond"
    const val UnetOutput = "output_0"
    const val EncoderInput = "rgb"
    const val EncoderOutput = "output_0"
    const val DecoderInput = "latent"
    const val DecoderOutput = "output_0"

    /**
     * The fixed embeddings fed to the U-Net's [UnetCondition] input, one [ConditionTokens] x [ConditionWidth] set
     * per branch: the package's condition file holds the unconditional set, then the conditional one (float32 LE).
     */
    const val ConditionTokens = 10
    const val ConditionWidth = 3072

    const val LatentSize = 64
    const val LatentChannels = 4

    /** UNet input channels: noisy latent, fill mask, masked-image latent. */
    const val UnetChannels = 9
    const val TrainTimesteps = 1000
}

/**
 * The DDIM inpainting sampler of Moebius without any model: schedule, timesteps, noise, latent mask, input
 * assembly and the update step. All tensors are NHWC float arrays. Kept free of Android and LiteRT types so
 * it is unit tested on the JVM.
 */
class MoebiusSampler(
    val steps: Int = MoebiusConfig.Steps,
    val strength: Double = MoebiusConfig.Strength,
    val noiseOffset: Double = MoebiusConfig.NoiseOffset,
) {
    init {
        require(steps in 1..MoebiusConfig.TrainTimesteps)
        require(strength in 0.0..1.0 && (steps * strength).toInt() >= 1) { "Strength leaves no denoising step" }
    }

    private val stride = MoebiusConfig.TrainTimesteps / steps

    /** Timesteps to denoise, from the noisiest, after skipping the share of steps that [strength] leaves out. */
    val timesteps: IntArray = run {
        val all = IntArray(steps) { (steps - 1 - it) * stride }
        val start = steps - minOf((steps * strength).toInt(), steps)
        all.copyOfRange(start, steps)
    }

    /** The 64x64x1 latent fill mask (1 = fill): the full-size [keep] mask sampled at every 8th pixel. */
    fun latentMask(keep: BooleanArray, imageSize: Int = InpaintPlan.ModelSize): FloatArray {
        require(keep.size == imageSize * imageSize)
        val factor = imageSize / MoebiusConfig.LatentSize
        val size = MoebiusConfig.LatentSize
        return FloatArray(size * size) { if (keep[(it / size) * factor * imageSize + (it % size) * factor]) 0f else 1f }
    }

    /** Standard normal noise per element plus [noiseOffset] times one normal draw shared by each channel. */
    fun noise(seed: Long): FloatArray {
        val random = Random(seed)
        val channels = MoebiusConfig.LatentChannels
        val noise = FloatArray(MoebiusConfig.LatentSize * MoebiusConfig.LatentSize * channels) { random.nextGaussian().toFloat() }
        val offsets = DoubleArray(channels) { noiseOffset * random.nextGaussian() }
        for (i in noise.indices) noise[i] = (noise[i] + offsets[i % channels]).toFloat()
        return noise
    }

    /** Noises the clean [latent] to the first timestep. */
    fun start(latent: FloatArray, noise: FloatArray): FloatArray {
        val alpha = AlphasCumprod[timesteps[0]]
        val signal = sqrt(alpha)
        val spread = sqrt(1.0 - alpha)
        return FloatArray(latent.size) { (signal * latent[it] + spread * noise[it]).toFloat() }
    }

    /** Concatenates the noisy latent, the latent mask and the masked-image latent on the channel axis. */
    fun unetInput(x: FloatArray, mask: FloatArray, maskedLatent: FloatArray): FloatArray {
        val channels = MoebiusConfig.LatentChannels
        val pixels = mask.size
        require(x.size == pixels * channels && maskedLatent.size == x.size)
        val out = FloatArray(pixels * MoebiusConfig.UnetChannels)
        for (p in 0 until pixels) {
            val base = p * MoebiusConfig.UnetChannels
            for (c in 0 until channels) {
                out[base + c] = x[p * channels + c]
                out[base + channels + 1 + c] = maskedLatent[p * channels + c]
            }
            out[base + channels] = mask[p]
        }
        return out
    }

    /** One DDIM update of [x] at timestep [t] from the predicted noise [eps]; the last step returns the clean estimate. */
    fun step(eps: FloatArray, t: Int, x: FloatArray): FloatArray {
        val previous = t - stride
        val alpha = AlphasCumprod[t]
        val alphaPrevious = if (previous >= 0) AlphasCumprod[previous] else 1.0
        val root = sqrt(alpha)
        val rootNoise = sqrt(1.0 - alpha)
        val rootPrevious = sqrt(alphaPrevious)
        val rootNoisePrevious = sqrt(1.0 - alphaPrevious)
        return FloatArray(x.size) {
            val clean = (x[it] - rootNoise * eps[it]) / root
            (rootPrevious * clean + rootNoisePrevious * eps[it]).toFloat()
        }
    }

    /** Classifier-free guidance: the unconditional prediction pushed [scale] times its distance toward the conditional. */
    fun guide(unconditional: FloatArray, conditional: FloatArray, scale: Double): FloatArray =
        FloatArray(conditional.size) { (unconditional[it] + scale * (conditional[it] - unconditional[it])).toFloat() }

    /** The encoder input in [-1, 1], RGB; with [blankHole] the pixels where [keep] is false are zeroed. */
    fun rgbInput(argb: IntArray, keep: BooleanArray, blankHole: Boolean): FloatArray {
        require(argb.size == keep.size)
        val out = FloatArray(argb.size * 3)
        for (i in argb.indices) {
            if (blankHole && !keep[i]) continue
            val c = argb[i]
            out[i * 3] = ((c shr 16) and 0xFF) / 127.5f - 1f
            out[i * 3 + 1] = ((c shr 8) and 0xFF) / 127.5f - 1f
            out[i * 3 + 2] = (c and 0xFF) / 127.5f - 1f
        }
        return out
    }

    /** Opaque ARGB pixels from decoder output in [0, 1]. */
    fun toArgb(rgb: FloatArray): IntArray = IntArray(rgb.size / 3) {
        fun channel(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (channel(rgb[it * 3]) shl 16) or (channel(rgb[it * 3 + 1]) shl 8) or channel(rgb[it * 3 + 2])
    }

    companion object {
        /** The scaled-linear beta schedule's cumulative product of (1 - beta), in double precision. */
        val AlphasCumprod: DoubleArray by lazy {
            val start = Math.sqrt(0.00085)
            val end = Math.sqrt(0.012)
            val n = MoebiusConfig.TrainTimesteps
            var product = 1.0
            DoubleArray(n) {
                val beta = (start + (end - start) * it / (n - 1)).let { root -> root * root }
                product *= 1.0 - beta
                product
            }
        }
    }
}

/** Small LRU of finished passes, so erases that re-run every mark from the clean preview only pay for new ones. */
class InpaintMemo(private val capacity: Int = 8) {
    private val entries = object : LinkedHashMap<String, IntArray>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>) = size > capacity
    }

    @Synchronized
    fun getOrPut(argb: IntArray, keep: BooleanArray, seed: Long, compute: () -> IntArray): IntArray {
        val key = key(argb, keep, seed)
        entries[key]?.let { return it }
        return compute().also { entries[key] = it }
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun key(argb: IntArray, keep: BooleanArray, seed: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            bytes[i * 4] = (argb[i] ushr 24).toByte(); bytes[i * 4 + 1] = (argb[i] ushr 16).toByte()
            bytes[i * 4 + 2] = (argb[i] ushr 8).toByte(); bytes[i * 4 + 3] = argb[i].toByte()
        }
        digest.update(bytes)
        digest.update(ByteArray(keep.size) { if (keep[it]) 1 else 0 })
        for (shift in 0 until 64 step 8) digest.update((seed ushr shift).toByte())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
