package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds the tilt of a photo from its dominant near-horizontal and near-vertical edges. The result
 * is the clockwise `Straighten` angle that levels them (same sign as [com.librestatic.lightforge.core.model.EditOperation.Straighten]).
 */
object AutoStraighten {
    private const val MaxSide = 512
    private const val RangeDegrees = 15f
    private const val BinsPerDegree = 4
    private const val Bins = (2 * RangeDegrees * BinsPerDegree).toInt() + 1
    private const val PeakWindowDegrees = 1f
    private const val MinPeakFraction = 0.22f
    private const val MinSignalPixels = 200
    private const val RivalRatio = 0.7f

    /** Returns the clockwise correction in degrees, or null when no dominant tilt is evident. */
    fun detect(bitmap: Bitmap): Float? {
        var source = bitmap
        if (max(bitmap.width, bitmap.height) > MaxSide) {
            val scale = MaxSide.toFloat() / max(bitmap.width, bitmap.height)
            source = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val w = source.width
        val h = source.height
        if (source !== bitmap) source.recycle()
        val luma = FloatArray(pixels.size) {
            val p = pixels[it]
            (0.2126f * ((p shr 16) and 0xff) + 0.7152f * ((p shr 8) and 0xff) + 0.0722f * (p and 0xff)) / 255f
        }
        return detect(luma, w, h)
    }

    /** Core on a row-major luma plane in 0..1; box-downscales first when the long side exceeds 512. */
    fun detect(luma: FloatArray, width: Int, height: Int): Float? {
        require(luma.size >= width * height)
        if (width < 8 || height < 8) return null
        if (max(width, height) > MaxSide) {
            val factor = (max(width, height) + MaxSide - 1) / MaxSide
            val w = width / factor
            val h = height / factor
            val small = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (yy in 0 until factor) for (xx in 0 until factor) sum += luma[(y * factor + yy) * width + x * factor + xx]
                small[y * w + x] = sum / (factor * factor)
            }
            return detect(small, w, h)
        }

        // Sobel gradients; keep edges strong enough relative to the strongest one.
        val mag = FloatArray(width * height)
        val phi = FloatArray(width * height)
        var maxMag = 0f
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val gx = (luma[i - width + 1] + 2 * luma[i + 1] + luma[i + width + 1]) -
                (luma[i - width - 1] + 2 * luma[i - 1] + luma[i + width - 1])
            val gy = (luma[i + width - 1] + 2 * luma[i + width] + luma[i + width + 1]) -
                (luma[i - width - 1] + 2 * luma[i - width] + luma[i - width + 1])
            val m = sqrt(gx * gx + gy * gy)
            mag[i] = m
            if (m > maxMag) maxMag = m
            // Edge direction is perpendicular to the gradient; y points down, so positive is clockwise.
            var deg = atan2(gx, -gy) * (180f / PI.toFloat())
            if (deg > 90f) deg -= 180f else if (deg <= -90f) deg += 180f
            phi[i] = deg
        }
        if (maxMag < 0.05f) return null
        val threshold = 0.25f * maxMag

        val histogram = FloatArray(Bins)
        var signal = 0
        var total = 0f
        for (i in mag.indices) {
            val m = mag[i]
            if (m < threshold) continue
            val deg = phi[i]
            val deviation = when {
                abs(deg) <= RangeDegrees -> deg
                deg > 90f - RangeDegrees -> deg - 90f
                deg < -90f + RangeDegrees -> deg + 90f
                else -> continue
            }
            val bin = ((deviation + RangeDegrees) * BinsPerDegree).roundToInt().coerceIn(0, Bins - 1)
            histogram[bin] += m
            total += m
            signal++
        }
        if (signal < MinSignalPixels || total <= 0f) return null

        val window = (PeakWindowDegrees * BinsPerDegree).toInt()
        fun mass(centre: Int): Float {
            var sum = 0f
            for (b in max(0, centre - window)..minOf(Bins - 1, centre + window)) sum += histogram[b]
            return sum
        }
        var peak = 0
        var peakMass = 0f
        for (b in 0 until Bins) {
            val m = mass(b)
            if (m > peakMass) { peakMass = m; peak = b }
        }
        if (peakMass / total < MinPeakFraction) return null
        // A second, separate cluster of similar weight makes the tilt ambiguous.
        var rival = 0f
        for (b in 0 until Bins) {
            if (abs(b - peak) > 2 * window) rival = max(rival, mass(b))
        }
        if (rival > RivalRatio * peakMass) return null

        var weighted = 0f
        var weights = 0f
        for (b in max(0, peak - window)..minOf(Bins - 1, peak + window)) {
            weighted += histogram[b] * b
            weights += histogram[b]
        }
        val deviation = weighted / weights / BinsPerDegree - RangeDegrees
        // Lines tilted clockwise by `deviation` are levelled by rotating the photo the other way.
        return -deviation
    }
}
