package com.librestatic.lightforge.core.editing.image.grade

import android.graphics.Bitmap
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Per-channel CIELAB mean and standard deviation of a reference photo. */
data class LabStats(
    val meanL: Float, val stdL: Float,
    val meanA: Float, val stdA: Float,
    val meanB: Float, val stdB: Float,
)

class ColorTransferResult(val lut: PhotoLut, val stats: LabStats)

/**
 * "My filters": a Reinhard statistics transfer in CIELAB (D65) from a fixed neutral prior to the
 * reference photo's statistics, baked into a 33³ LUT. The source is the prior, not the edited
 * photo, so the LUT never depends on the photo being edited.
 */
object ColorTransferLutBuilder {
    private const val MaxSide = 256
    private const val PriorMeanL = 50f
    private const val PriorStdL = 20f
    private const val PriorStdAb = 12f
    private const val MinScale = 0.5f
    private const val MaxScale = 2f
    private const val MaxChromaShift = 20f
    private const val MaxLightnessShift = 15f

    /** Builds the LUT from [reference], downscaled so its long side is at most 256 px. */
    fun build(reference: Bitmap): ColorTransferResult {
        var source = reference
        if (max(reference.width, reference.height) > MaxSide) {
            val scale = MaxSide.toFloat() / max(reference.width, reference.height)
            source = Bitmap.createScaledBitmap(
                reference,
                (reference.width * scale).roundToInt().coerceAtLeast(1),
                (reference.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        if (source !== reference) source.recycle()
        // getPixels is straight alpha, which is what the statistics expect.
        return build(pixels)
    }

    /** Builds the LUT from straight-alpha ARGB [pixels]; fully transparent pixels are ignored. */
    fun build(pixels: IntArray, size: Int = PhotoLut.DefaultSize): ColorTransferResult {
        val stats = statsOf(pixels)
        return ColorTransferResult(bake(stats, size), stats)
    }

    internal fun statsOf(pixels: IntArray): LabStats {
        val lab = FloatArray(3)
        var n = 0
        val sum = DoubleArray(3)
        val sumSq = DoubleArray(3)
        for (p in pixels) {
            if (p ushr 24 < 128) continue
            Lab.fromSrgb(((p shr 16) and 0xff) / 255f, ((p shr 8) and 0xff) / 255f, (p and 0xff) / 255f, lab)
            for (c in 0..2) {
                sum[c] += lab[c]
                sumSq[c] += lab[c].toDouble() * lab[c]
            }
            n++
        }
        if (n == 0) return LabStats(PriorMeanL, PriorStdL, 0f, PriorStdAb, 0f, PriorStdAb)
        fun mean(c: Int) = (sum[c] / n).toFloat()
        fun std(c: Int) = sqrt(max(sumSq[c] / n - (sum[c] / n) * (sum[c] / n), 0.0)).toFloat()
        return LabStats(mean(0), std(0), mean(1), std(1), mean(2), std(2))
    }

    internal fun bake(stats: LabStats, size: Int): PhotoLut {
        val scaleL = (stats.stdL / PriorStdL).coerceIn(MinScale, MaxScale)
        val scaleA = (stats.stdA / PriorStdAb).coerceIn(MinScale, MaxScale)
        val scaleB = (stats.stdB / PriorStdAb).coerceIn(MinScale, MaxScale)
        val shiftA = stats.meanA.coerceIn(-MaxChromaShift, MaxChromaShift)
        val shiftB = stats.meanB.coerceIn(-MaxChromaShift, MaxChromaShift)
        val meanL = stats.meanL.coerceIn(PriorMeanL - MaxLightnessShift, PriorMeanL + MaxLightnessShift)
        val data = FloatArray(size * size * size * 3)
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            Lab.fromSrgb(r / (size - 1f), g / (size - 1f), b / (size - 1f), lab)
            lab[0] = ((lab[0] - PriorMeanL) * scaleL + meanL).coerceIn(0f, 100f)
            lab[1] = lab[1] * scaleA + shiftA
            lab[2] = lab[2] * scaleB + shiftB
            Lab.toSrgb(lab[0], lab[1], lab[2], rgb)
            data[i++] = rgb[0]
            data[i++] = rgb[1]
            data[i++] = rgb[2]
        }
        return PhotoLut(size, data)
    }
}

/** sRGB (encoded, D65) to and from CIELAB. */
internal object Lab {
    private const val Xn = 0.95047f
    private const val Zn = 1.08883f
    private const val Epsilon = 216f / 24389f
    private const val Kappa = 24389f / 27f

    fun fromSrgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr = PhotoGradeMath.lin(r)
        val lg = PhotoGradeMath.lin(g)
        val lb = PhotoGradeMath.lin(b)
        val x = (0.4124564f * lr + 0.3575761f * lg + 0.1804375f * lb) / Xn
        val y = 0.2126729f * lr + 0.7151522f * lg + 0.0721750f * lb
        val z = (0.0193339f * lr + 0.1191920f * lg + 0.9503041f * lb) / Zn
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    fun toSrgb(l: Float, a: Float, b: Float, out: FloatArray) {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        val x = finv(fx) * Xn
        val y = if (l > Kappa * Epsilon) fy * fy * fy else l / Kappa
        val z = finv(fz) * Zn
        val lr = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        val lg = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        val lb = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z
        out[0] = PhotoGradeMath.enc(lr.coerceIn(0f, 1f))
        out[1] = PhotoGradeMath.enc(lg.coerceIn(0f, 1f))
        out[2] = PhotoGradeMath.enc(lb.coerceIn(0f, 1f))
    }

    private fun f(t: Float) = if (t > Epsilon) cbrt(t) else (Kappa * t + 16f) / 116f
    private fun finv(t: Float): Float {
        val cubed = t * t * t
        return if (cubed > Epsilon) cubed else (116f * t - 16f) / Kappa
    }
}
