package com.librestatic.lightforge.core.editing.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** The optional encoder [encoding] is missing or failed on this device. */
class PhotoEncoderUnavailableException(val encoding: PhotoEncoding, cause: Throwable? = null) :
    IOException("No working ${encoding.name} encoder", cause)

/**
 * Writes a rendered, upright bitmap in the plan's format. JPEG, PNG and WebP use the platform
 * encoder, GIF the adaptive-palette [StillGifEncoder] and HEIC/AVIF androidx.heifwriter.
 */
object PhotoEncoders {
    private const val HeifTimeoutMillis = 60_000L

    /** Device encoders for the optional formats; queried once per process. */
    val capabilities: PhotoOutputCapabilities by lazy { detectCapabilities() }

    /**
     * Encodes [bitmap] (already at the plan size) into [destination]. Does not recycle [bitmap].
     * [checkpoint] is called between GIF row batches so a cancelled export stops early.
     */
    fun encode(bitmap: Bitmap, plan: PhotoOutputPlan, destination: File, checkpoint: () -> Unit = {}) {
        destination.parentFile?.mkdirs()
        val source = if (plan.encoding.isOpaque && bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
        try {
            when (plan.encoding) {
                PhotoEncoding.Heic, PhotoEncoding.Avif -> encodeHeif(source, plan, destination)
                else -> destination.outputStream().buffered().use { encode(source, plan, it, checkpoint) }
            }
        } finally {
            if (source !== bitmap) source.recycle()
        }
        if (!destination.isFile || destination.length() == 0L) throw IOException("Image export is empty")
    }

    /** Stream variant for the formats that do not need a file (everything but HEIC/AVIF). */
    fun encode(bitmap: Bitmap, plan: PhotoOutputPlan, output: OutputStream, checkpoint: () -> Unit = {}) {
        when (plan.encoding) {
            PhotoEncoding.Gif -> {
                val width = bitmap.width
                StillGifEncoder.encode(width, bitmap.height, plan.gif, output, checkpoint) { y, row ->
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                }
            }
            PhotoEncoding.Heic, PhotoEncoding.Avif -> error("${plan.encoding} needs a file destination")
            else -> check(bitmap.compress(compressFormat(plan.encoding), plan.quality, output)) {
                "Image encoder rejected output"
            }
        }
    }

    /**
     * Approximate size in bytes of the full export, extrapolated from encoding a small copy of
     * [preview] (the edited frame at any size). Lossy formats encode fewer bytes per pixel at
     * full size, so this errs on the large side. [scratch] holds the temporary HEIC/AVIF file.
     */
    fun estimateBytes(preview: Bitmap, plan: PhotoOutputPlan, scratch: File, sampleLongSide: Int = 768): Long {
        val scale = (sampleLongSide.toFloat() / max(preview.width, preview.height)).coerceAtMost(1f)
        val sample = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                preview,
                (preview.width * scale).roundToInt().coerceAtLeast(1),
                (preview.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else preview
        try {
            val samplePlan = plan.copy(width = sample.width, height = sample.height, tiled = false)
            val bytes = when (plan.encoding) {
                PhotoEncoding.Heic, PhotoEncoding.Avif -> {
                    try {
                        encode(sample, samplePlan, scratch)
                        scratch.length()
                    } finally {
                        scratch.delete()
                    }
                }
                else -> CountingStream().also { encode(sample, samplePlan, it) }.count
            }
            val ratio = plan.pixels.toDouble() / (sample.width.toLong() * sample.height)
            return (bytes * ratio).toLong()
        } finally {
            if (sample !== preview) sample.recycle()
        }
    }

    private fun compressFormat(encoding: PhotoEncoding): Bitmap.CompressFormat = when (encoding) {
        PhotoEncoding.Png -> Bitmap.CompressFormat.PNG
        PhotoEncoding.WebpLossy -> Bitmap.CompressFormat.WEBP_LOSSY
        PhotoEncoding.WebpLossless -> Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> Bitmap.CompressFormat.JPEG
    }

    private fun encodeHeif(bitmap: Bitmap, plan: PhotoOutputPlan, destination: File) {
        val path = destination.absolutePath
        try {
            when (plan.encoding) {
                PhotoEncoding.Heic -> HeifWriter.Builder(path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(plan.quality)
                    .setMaxImages(1)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(HeifTimeoutMillis)
                    }
                else -> AvifWriter.Builder(path, bitmap.width, bitmap.height, AvifWriter.INPUT_MODE_BITMAP)
                    .setQuality(plan.quality)
                    .setMaxImages(1)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(HeifTimeoutMillis)
                    }
            }
        } catch (failure: Exception) {
            destination.delete()
            throw PhotoEncoderUnavailableException(plan.encoding, failure)
        }
    }

    /** Opaque containers would turn transparent pixels black; composite them over white instead. */
    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        flat.setHasAlpha(false)
        return flat
    }

    private fun detectCapabilities(): PhotoOutputCapabilities = runCatching {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .map { it.lowercase() }
            .toSet()
        PhotoOutputCapabilities(
            heic = MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC in encoders || MediaFormat.MIMETYPE_VIDEO_HEVC in encoders,
            // AVIF muxing in the HEIF container landed in Android 14.
            avif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                MediaFormat.MIMETYPE_VIDEO_AV1 in encoders,
        )
    }.getOrDefault(PhotoOutputCapabilities.Basic)

    private class CountingStream : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }
}
