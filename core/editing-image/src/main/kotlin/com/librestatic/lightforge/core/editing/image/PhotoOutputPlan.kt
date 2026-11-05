package com.librestatic.lightforge.core.editing.image

import kotlin.math.max
import kotlin.math.roundToInt

/** A concrete encoder with its published MIME type and file extension. */
enum class PhotoEncoding(val mimeType: String, val extension: String) {
    Jpeg("image/jpeg", "jpg"),
    Png("image/png", "png"),
    WebpLossy("image/webp", "webp"),
    WebpLossless("image/webp", "webp"),
    Gif("image/gif", "gif"),
    Heic("image/heic", "heic"),
    Avif("image/avif", "avif"),
    ;

    /** ExifInterface can write capture metadata into these containers. */
    val holdsExif: Boolean get() = this == Jpeg || this == Png || this == WebpLossy || this == WebpLossless

    val usesQuality: Boolean get() = this == Jpeg || this == WebpLossy || this == Heic || this == Avif

    /** Formats without an alpha channel; transparent pixels are composited over white. */
    val isOpaque: Boolean get() = this == Jpeg || this == Heic || this == Avif

    companion object {
        fun of(format: PhotoOutputFormat): PhotoEncoding? = when (format) {
            PhotoOutputFormat.Original -> null
            PhotoOutputFormat.Jpeg -> Jpeg
            PhotoOutputFormat.Png -> Png
            PhotoOutputFormat.WebpLossy -> WebpLossy
            PhotoOutputFormat.WebpLossless -> WebpLossless
            PhotoOutputFormat.Gif -> Gif
            PhotoOutputFormat.Heic -> Heic
            PhotoOutputFormat.Avif -> Avif
        }
    }
}

/** Why the plan differs from what the settings asked for; the UI shows each as a short note. */
enum class PhotoOutputAdjustment {
    /** The chosen format has no encoder on this device; JPEG is written instead. */
    FormatUnavailable,
    /** The requested size is larger than the edited photo; photos are never upscaled. */
    NoUpscale,
    /** The output is too large for one bitmap, so it is streamed as a full-resolution PNG. */
    TooLargeUsesPng,
    /** The format cannot carry capture metadata; the copy has none. */
    MetadataUnsupported,
}

/**
 * The resolved output of a still export: the encoder, the exact pixel size and how metadata is
 * handled. Pure, so the exporter and the UI summary agree on what Save copy will write.
 */
data class PhotoOutputPlan(
    val encoding: PhotoEncoding,
    /** The cropped, rotated frame before resizing. */
    val editedWidth: Int,
    val editedHeight: Int,
    val width: Int,
    val height: Int,
    val quality: Int,
    val copyMetadata: Boolean,
    val removeLocation: Boolean,
    val gif: GifOutputOptions,
    /**
     * True when the decode or the output exceeds the bitmap budget, so the export goes through
     * the streaming full-resolution PNG path (which cannot resize).
     */
    val tiled: Boolean,
    /** Power-of-two decode subsample that still yields at least the output size. */
    val sample: Int,
    val adjustments: List<PhotoOutputAdjustment>,
) {
    val mimeType: String get() = encoding.mimeType
    val extension: String get() = encoding.extension
    val pixels: Long get() = width.toLong() * height.toLong()

    val resizes: Boolean get() = width != editedWidth || height != editedHeight

    companion object {
        /**
         * Resolves [settings] for a source of [sourceMimeType] whose edited (cropped, rotated)
         * frame is [editedWidth] x [editedHeight] and whose decoded source is [sourceWidth] x
         * [sourceHeight]. A decode or output above [maxBitmapPixels] can only be written by the
         * streaming PNG encoder.
         */
        fun resolve(
            settings: PhotoOutputSettings,
            sourceMimeType: String?,
            editedWidth: Int,
            editedHeight: Int,
            capabilities: PhotoOutputCapabilities,
            maxBitmapPixels: Long,
            sourceWidth: Int = editedWidth,
            sourceHeight: Int = editedHeight,
        ): PhotoOutputPlan {
            require(editedWidth > 0 && editedHeight > 0)
            val adjustments = mutableListOf<PhotoOutputAdjustment>()
            var encoding = when {
                settings.format == PhotoOutputFormat.Original -> originalEncoding(sourceMimeType)
                capabilities.supports(settings.format) -> PhotoEncoding.of(settings.format)!!
                else -> {
                    adjustments += PhotoOutputAdjustment.FormatUnavailable
                    PhotoEncoding.Jpeg
                }
            }
            val (width, height) = targetSize(settings.resize, editedWidth, editedHeight)
            if (settings.resize is PhotoOutputResize.LongSide &&
                settings.resize.pixels > max(editedWidth, editedHeight)
            ) {
                adjustments += PhotoOutputAdjustment.NoUpscale
            }
            val sample = decodeSample(width.toDouble() / editedWidth)
            val decodePixels = sourceWidth.toLong() * sourceHeight / (sample.toLong() * sample)
            val tiled = width.toLong() * height.toLong() > maxBitmapPixels || decodePixels > maxBitmapPixels
            if (tiled) {
                if (encoding != PhotoEncoding.Png) adjustments += PhotoOutputAdjustment.TooLargeUsesPng
                encoding = PhotoEncoding.Png
            }
            val wantsMetadata = settings.metadata != PhotoOutputMetadata.RemoveAll
            if (wantsMetadata && !encoding.holdsExif) adjustments += PhotoOutputAdjustment.MetadataUnsupported
            return PhotoOutputPlan(
                encoding = encoding,
                editedWidth = editedWidth,
                editedHeight = editedHeight,
                // The streaming path writes the edited frame at full size.
                width = if (tiled) editedWidth else width,
                height = if (tiled) editedHeight else height,
                quality = settings.quality,
                copyMetadata = wantsMetadata && encoding.holdsExif,
                removeLocation = settings.metadata == PhotoOutputMetadata.RemoveLocation,
                gif = settings.gif,
                tiled = tiled,
                sample = if (tiled) 1 else sample,
                adjustments = adjustments,
            )
        }

        /** The pre-existing "same as source" mapping: PNG and WebP keep their type, all else is JPEG. */
        fun originalEncoding(sourceMimeType: String?): PhotoEncoding = when (sourceMimeType?.lowercase()) {
            "image/png" -> PhotoEncoding.Png
            "image/webp", "image/webp-lossless" -> PhotoEncoding.WebpLossless
            else -> PhotoEncoding.Jpeg
        }

        /** The largest power of two not above 1 / [scale], so the decode never drops below the output. */
        fun decodeSample(scale: Double): Int {
            var sample = 1
            while (scale * sample * 2 <= 1.0 && sample < 64) sample *= 2
            return sample
        }

        /** Output size for [resize]; keeps the aspect ratio, never upscales and never drops below 1 px. */
        fun targetSize(resize: PhotoOutputResize, width: Int, height: Int): Pair<Int, Int> {
            val scale = when (resize) {
                PhotoOutputResize.Original -> 1.0
                is PhotoOutputResize.LongSide -> (resize.pixels.toDouble() / max(width, height)).coerceAtMost(1.0)
                is PhotoOutputResize.Scale -> resize.percent / 100.0
            }
            if (scale >= 1.0) return width to height
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }
    }
}
