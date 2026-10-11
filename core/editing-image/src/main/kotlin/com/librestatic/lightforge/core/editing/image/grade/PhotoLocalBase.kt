package com.librestatic.lightforge.core.editing.image.grade

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The blurred linear-luma base of ADR-045 (long side at most [MaxSide] px, sigma 4 px at that
 * scale, clamped edges) that drives highlights, shadows and tone.
 */
class PhotoLocalBase(val width: Int, val height: Int, val luma: FloatArray) {
    init {
        require(width > 0 && height > 0 && luma.size == width * height)
    }

    /**
     * Bilinear lookup at frame UV ([u], [v]), matching a linear-filtered texture: texel centres sit
     * at `(i + 0.5) / size` and coordinates clamp to the edge.
     */
    fun sample(u: Float, v: Float): Float {
        val x = (u * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (v * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = floor(x).toInt().coerceAtMost(width - 1)
        val y0 = floor(y).toInt().coerceAtMost(height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = x - x0
        val ty = y - y0
        val top = luma[y0 * width + x0] * (1f - tx) + luma[y0 * width + x1] * tx
        val bottom = luma[y1 * width + x0] * (1f - tx) + luma[y1 * width + x1] * tx
        return top * (1f - ty) + bottom * ty
    }

    companion object {
        const val MaxSide = 256
        private const val Sigma = 4f

        /** Builds the base of the whole [bitmap] frame, downscaling it with filtering first. */
        fun from(bitmap: Bitmap): PhotoLocalBase {
            var source = bitmap
            var owned = false
            while (max(source.width, source.height) > MaxSide) {
                val longSide = max(source.width, source.height)
                val factor = maxOf(MaxSide.toFloat() / longSide, 0.5f)
                val next = Bitmap.createScaledBitmap(
                    source,
                    (source.width * factor).roundToInt().coerceAtLeast(1),
                    (source.height * factor).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (owned && next !== source) source.recycle()
                owned = next !== bitmap
                source = next
            }
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            val base = from(pixels, source.width, source.height)
            if (owned) source.recycle()
            return base
        }

        /** Builds the base from ARGB premultiplied [pixels]; box-averages first if the long side exceeds [MaxSide]. */
        fun from(pixels: IntArray, width: Int, height: Int): PhotoLocalBase {
            require(width > 0 && height > 0 && pixels.size >= width * height)
            val raw = FloatArray(width * height)
            for (i in raw.indices) raw[i] = PhotoGradeMath.linearLuma(pixels[i])
            val longSide = max(width, height)
            val (small, w, h) = if (longSide > MaxSide) boxDownscale(raw, width, height) else Triple(raw, width, height)
            return PhotoLocalBase(w, h, blur(small, w, h))
        }

        private fun boxDownscale(src: FloatArray, width: Int, height: Int): Triple<FloatArray, Int, Int> {
            val scale = MaxSide.toFloat() / max(width, height)
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                val y0 = (y.toLong() * height / h).toInt()
                val y1 = (((y + 1).toLong() * height + h - 1) / h).toInt().coerceIn(y0 + 1, height)
                for (x in 0 until w) {
                    val x0 = (x.toLong() * width / w).toInt()
                    val x1 = (((x + 1).toLong() * width + w - 1) / w).toInt().coerceIn(x0 + 1, width)
                    var sum = 0f
                    for (yy in y0 until y1) for (xx in x0 until x1) sum += src[yy * width + xx]
                    out[y * w + x] = sum / ((y1 - y0) * (x1 - x0))
                }
            }
            return Triple(out, w, h)
        }

        private fun kernel(): FloatArray {
            val radius = (3 * Sigma).toInt()
            val k = FloatArray(radius * 2 + 1) { exp(-((it - radius) * (it - radius)) / (2 * Sigma * Sigma)) }
            val sum = k.sum()
            for (i in k.indices) k[i] /= sum
            return k
        }

        private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
            val k = kernel()
            val radius = k.size / 2
            val tmp = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (i in k.indices) sum += k[i] * src[y * w + (x + i - radius).coerceIn(0, w - 1)]
                tmp[y * w + x] = sum
            }
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (i in k.indices) sum += k[i] * tmp[(y + i - radius).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum
            }
            return out
        }
    }
}
