package com.librestatic.lightforge.core.editing.image

/**
 * Converter-style options for a still export: file format, quality, output size, what capture
 * metadata to keep and the GIF palette. The defaults keep today's behaviour (same format family
 * as the source, quality 95, full size, capture EXIF kept), so an untouched editor saves exactly
 * as before.
 */
data class PhotoOutputSettings(
    val format: PhotoOutputFormat = PhotoOutputFormat.Original,
    /** Encoder quality for the lossy formats, 1..100; lossless formats ignore it. */
    val quality: Int = DefaultQuality,
    val resize: PhotoOutputResize = PhotoOutputResize.Original,
    val metadata: PhotoOutputMetadata = PhotoOutputMetadata.Keep,
    val gif: GifOutputOptions = GifOutputOptions(),
) {
    init { require(quality in 1..100) }

    val isDefault: Boolean get() = this == PhotoOutputSettings()

    companion object {
        const val DefaultQuality: Int = 95
    }
}

/** [Original] follows the source: PNG and WebP keep their type, everything else becomes JPEG. */
enum class PhotoOutputFormat {
    Original,
    Jpeg,
    Png,
    WebpLossy,
    WebpLossless,
    Gif,
    Heic,
    Avif,
    ;

    /** True when the quality setting changes the encoded result. */
    val usesQuality: Boolean get() = this == Jpeg || this == WebpLossy || this == Heic || this == Avif
}

sealed interface PhotoOutputResize {
    data object Original : PhotoOutputResize

    /** Scales so the longer side is at most [pixels]; never upscales. */
    data class LongSide(val pixels: Int) : PhotoOutputResize {
        init { require(pixels in MinPixels..MaxPixels) }
    }

    /** Scales both sides to [percent] of the edited size. */
    data class Scale(val percent: Int) : PhotoOutputResize {
        init { require(percent in 1..99) }
    }

    companion object {
        const val MinPixels: Int = 16
        const val MaxPixels: Int = 16_384
        val CommonLongSides: List<LongSide> = listOf(4096, 2048, 1080, 720).map(::LongSide)
        val CommonScales: List<Scale> = listOf(75, 50, 25).map(::Scale)
    }
}

enum class PhotoOutputMetadata {
    /** Copies the capture EXIF (date, location, camera) when the format can hold it. */
    Keep,
    /** Copies the capture EXIF without the GPS tags. */
    RemoveLocation,
    /** Writes no capture metadata at all. */
    RemoveAll,
}

data class GifOutputOptions(
    /** Palette size, 2..256; fewer colours give smaller, flatter files. */
    val colors: Int = 256,
    /** Floyd–Steinberg error diffusion: smoother gradients, slightly larger files. */
    val dither: Boolean = true,
) {
    init { require(colors in 2..256) }

    companion object {
        val CommonColors: List<Int> = listOf(256, 128, 64, 32, 16)
    }
}

/** Which optional encoders this device has. JPEG, PNG, WebP and GIF are always available. */
data class PhotoOutputCapabilities(val heic: Boolean, val avif: Boolean) {
    fun supports(format: PhotoOutputFormat): Boolean = when (format) {
        PhotoOutputFormat.Heic -> heic
        PhotoOutputFormat.Avif -> avif
        else -> true
    }

    /** The formats the format picker offers on this device, in display order. */
    val formats: List<PhotoOutputFormat> get() = PhotoOutputFormat.entries.filter(::supports)

    companion object {
        val Basic = PhotoOutputCapabilities(heic = false, avif = false)
    }
}
