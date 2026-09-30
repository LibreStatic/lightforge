package com.librestatic.lightforge.feature.viewer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs

enum class PhotoPreviewTransition { Immediate, Crossfade }

internal object PhotoPreviewTransitionPolicy {
    const val FastPreviewMillis = 250L
    // The tiny descriptor intentionally discards sharpness-only changes. A 5.5% mean visible-channel
    // delta still catches framing/color changes while treating a normal low-to-high quality upgrade as subtle.
    private const val DescriptorSide = 12
    private const val SubtleDifferenceThreshold = 0.055f

    fun decide(
        loadDurationMillis: Long,
        thumbnail: Bitmap?,
        preview: Drawable,
    ): PhotoPreviewTransition {
        if (thumbnail == null) return PhotoPreviewTransition.Crossfade
        if (loadDurationMillis < FastPreviewMillis) return PhotoPreviewTransition.Immediate
        val previewBitmap = (preview as? BitmapDrawable)?.bitmap
        val difference = previewBitmap?.let { normalizedVisualDifference(thumbnail, it) }
        return decide(loadDurationMillis, difference)
    }

    internal fun decide(
        loadDurationMillis: Long,
        normalizedVisualDifference: Float?,
    ): PhotoPreviewTransition = when {
        loadDurationMillis < FastPreviewMillis -> PhotoPreviewTransition.Immediate
        normalizedVisualDifference != null && normalizedVisualDifference <= SubtleDifferenceThreshold ->
            PhotoPreviewTransition.Immediate
        else -> PhotoPreviewTransition.Crossfade
    }

    private fun normalizedVisualDifference(first: Bitmap, second: Bitmap): Float? {
        val firstPixels = descriptor(first) ?: return null
        val secondPixels = descriptor(second) ?: return null
        var difference = 0L
        for (index in firstPixels.indices) {
            val a = firstPixels[index]
            val b = secondPixels[index]
            difference += abs(visibleChannel(a, 16) - visibleChannel(b, 16))
            difference += abs(visibleChannel(a, 8) - visibleChannel(b, 8))
            difference += abs(visibleChannel(a, 0) - visibleChannel(b, 0))
        }
        return difference.toFloat() / (firstPixels.size * Channels * MaximumChannel)
    }

    private fun descriptor(source: Bitmap): IntArray? {
        if (source.isRecycled) return null
        var scaled: Bitmap? = null
        var readable: Bitmap? = null
        return try {
            scaled = Bitmap.createScaledBitmap(source, DescriptorSide, DescriptorSide, true)
            readable = if (scaled.config == Bitmap.Config.HARDWARE) {
                scaled.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                scaled
            }
            IntArray(DescriptorSide * DescriptorSide).also { pixels ->
                readable.getPixels(pixels, 0, DescriptorSide, 0, 0, DescriptorSide, DescriptorSide)
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            if (readable != null && readable !== scaled && readable !== source) readable.recycle()
            if (scaled != null && scaled !== source) scaled.recycle()
        }
    }

    private fun visibleChannel(color: Int, shift: Int): Int {
        val alpha = color ushr 24
        val channel = (color ushr shift) and MaximumChannel
        return channel * alpha / MaximumChannel
    }

    private const val Channels = 3
    private const val MaximumChannel = 255
}
