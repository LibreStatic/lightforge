package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.graphics.Color
import com.librestatic.lightforge.core.editing.image.grade.PhotoGradeMath
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditOperation
import kotlin.math.ln
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PhotoAutoEnhancementSuggestions(
    val enhance: EditOperation.Tone,
    val dynamic: EditOperation.Tone,
    /** The same two suggestions expressed as ADR-045 [EditOperation.Adjust] values. */
    val enhanceAdjust: EditOperation.Adjust = EditOperation.Adjust(emptyMap()),
    val dynamicAdjust: EditOperation.Adjust = EditOperation.Adjust(emptyMap()),
)

/** Deterministic, local-only tone suggestions derived from a bounded preview sample. */
object PhotoAutoEnhancementAnalyzer {
    private const val MaxSamples = 16_384
    private const val MinimumSamples = 32
    private const val FitSteps = 8

    fun analyze(bitmap: Bitmap): PhotoAutoEnhancementSuggestions {
        if (bitmap.width <= 0 || bitmap.height <= 0) return identitySuggestions()
        val stride = ceil(sqrt(bitmap.width.toDouble() * bitmap.height / MaxSamples))
            .toInt()
            .coerceAtLeast(1)
        val luminance = FloatArray(
            ((bitmap.width + stride - 1) / stride) * ((bitmap.height + stride - 1) / stride),
        )
        var sampleCount = 0
        var saturationSum = 0f
        var redSum = 0f
        var blueSum = 0f
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) >= 128) {
                    val red = Color.red(color) / 255f
                    val green = Color.green(color) / 255f
                    val blue = Color.blue(color) / 255f
                    luminance[sampleCount++] = 0.2126f * red + 0.7152f * green + 0.0722f * blue
                    val maximum = max(red, max(green, blue))
                    val minimum = min(red, min(green, blue))
                    redSum += red
                    blueSum += blue
                    saturationSum += if (maximum <= 0f) 0f else (maximum - minimum) / maximum
                }
                x += stride
            }
            y += stride
        }
        if (sampleCount < MinimumSamples) return identitySuggestions()

        val sorted = luminance.copyOf(sampleCount).apply { sort() }
        val p05 = percentile(sorted, 0.05f)
        val p50 = percentile(sorted, 0.50f)
        val p95 = percentile(sorted, 0.95f)
        val range = p95 - p05
        if (!range.isFinite()) return identitySuggestions()
        val meanSaturation = saturationSum / sampleCount

        val enhanceContrast = if (range <= 0.001f) 1f else (0.80f / range).coerceIn(0.90f, 1.25f)
        val (enhanceFit, enhanceBrightness) = fitTone(enhanceContrast, p05, p50, p95)
        val enhance = EditOperation.Tone(
            brightness = enhanceBrightness,
            contrast = enhanceFit,
            saturation = saturationFor(meanSaturation, target = 0.35f, minimum = 0.95f, maximum = 1.18f),
        )
        val dynamicContrast = (1f + (enhanceContrast - 1f) * 1.25f + 0.04f)
            .coerceIn(0.95f, 1.35f)
        val (dynamicFit, dynamicBrightness) = fitTone(dynamicContrast, p05, p50, p95)
        val dynamic = EditOperation.Tone(
            brightness = dynamicBrightness,
            contrast = dynamicFit,
            saturation = saturationFor(meanSaturation, target = 0.42f, minimum = 1f, maximum = 1.30f),
        )
        val levels = Levels(p05, p50, p95, redSum / sampleCount, blueSum / sampleCount)
        return PhotoAutoEnhancementSuggestions(
            enhance = enhance,
            dynamic = dynamic,
            enhanceAdjust = adjustFor(levels, strength = 1f, contrast = 0f, saturation = enhance.saturation),
            dynamicAdjust = adjustFor(levels, strength = 1.4f, contrast = 0.15f, saturation = dynamic.saturation),
        )
    }

    /** Percentiles and channel means of the encoded sample, all 0..1. */
    private class Levels(val p05: Float, val p50: Float, val p95: Float, val meanRed: Float, val meanBlue: Float)

    /**
     * Black/white point from the percentiles, highlights/shadows from how crowded the extremes
     * are, brightness from the median, warmth from the gray-world red/blue balance.
     */
    private fun adjustFor(levels: Levels, strength: Float, contrast: Float, saturation: Float): EditOperation.Adjust {
        val black = PhotoGradeMath.lin(levels.p05)
        val white = PhotoGradeMath.lin(levels.p95)
        val blackPoint = (black / 0.05f).coerceIn(0f, 1f) * 0.8f
        val whitePoint = if (white > 0.05f) ((1f - white / 0.9f) / 0.35f).coerceIn(0f, 0.8f) * 0.7f else 0f
        val shadows = ((0.18f - levels.p05) / 0.18f).coerceIn(0f, 1f) * 0.5f
        val highlights = -((levels.p95 - 0.85f) / 0.15f).coerceIn(0f, 1f) * 0.5f
        // Y^(2^(-0.8b)) lands the median on mid-grey.
        val median = PhotoGradeMath.lin(levels.p50).coerceIn(0.01f, 0.99f)
        val target = PhotoGradeMath.lin(0.5f)
        val brightness = (-ln(ln(target) / ln(median)) / ln(2f) / 0.8f) * 0.6f
        val warmth = if (levels.meanRed > 0.02f && levels.meanBlue > 0.02f) {
            ((levels.meanBlue / levels.meanRed - 1f) / 0.2f) * 0.5f
        } else {
            0f
        }
        fun scaled(value: Float, limit: Float) = (value * strength).takeIf { it.isFinite() }?.coerceIn(-limit, limit) ?: 0f
        return EditOperation.Adjust(
            mapOf(
                AdjustKey.Brightness to scaled(brightness, 0.4f),
                AdjustKey.Contrast to contrast,
                AdjustKey.WhitePoint to scaled(whitePoint, 0.8f),
                AdjustKey.Highlights to scaled(highlights, 0.7f),
                AdjustKey.Shadows to scaled(shadows, 0.7f),
                AdjustKey.BlackPoint to scaled(blackPoint, 0.8f),
                AdjustKey.Saturation to (saturation - 1f).coerceIn(-1f, 1f),
                AdjustKey.Warmth to scaled(warmth, 0.4f),
            ),
        )
    }

    private fun percentile(sorted: FloatArray, fraction: Float): Float {
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    /**
     * Pairs a contrast with a brightness that keeps the 5th/95th percentiles inside the displayable range.
     * Contrast pivots around mid-grey, so on a dark or bright photo the requested contrast can leave no
     * feasible brightness; the contrast is then eased toward 1 until one exists instead of giving up on
     * brightness (which made dark photos darker).
     */
    internal fun fitTone(contrast: Float, p05: Float, p50: Float, p95: Float): Pair<Float, Float> {
        for (step in 0..FitSteps) {
            val candidate = contrast + (1f - contrast) * step / FitSteps
            val brightness = boundedBrightness(candidate, p05, p50, p95)
            if (brightness != null) return candidate to brightness
        }
        return 1f to 0f
    }

    private fun boundedBrightness(contrast: Float, p05: Float, p50: Float, p95: Float): Float? {
        val desired = contrast * (0.50f - p50)
        val translation = (1f - contrast) * 0.50f
        val lower = max(-0.15f, 0.02f - (contrast * p05 + translation))
        val upper = min(0.15f, 0.98f - (contrast * p95 + translation))
        return if (lower <= upper) desired.coerceIn(lower, upper) else null
    }

    private fun saturationFor(mean: Float, target: Float, minimum: Float, maximum: Float): Float =
        if (!mean.isFinite() || mean < 0.05f) 1f else (target / mean).coerceIn(minimum, maximum)

    private fun identitySuggestions() = PhotoAutoEnhancementSuggestions(
        enhance = EditOperation.Tone(),
        dynamic = EditOperation.Tone(),
    )
}
