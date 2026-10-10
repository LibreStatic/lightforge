package com.librestatic.lightforge.feature.details

import androidx.annotation.StringRes
import com.librestatic.lightforge.core.model.ColorStandard
import com.librestatic.lightforge.core.model.ColorTransfer
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure formatting for the Details panel. EXIF stores raw strings (a double for the f-number, a
 * decimal or rational for exposure and focal length), so every value is parsed defensively and
 * a value that cannot be read is dropped instead of shown raw.
 */
object DetailsFormatting {
    private val exifDateTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Parses "1.8", "18/10" or "4300/1000"; null for blank, zero, negative or garbage values. */
    fun parseNumber(raw: String?): Double? {
        val text = raw?.trim()?.removePrefix("f/")?.removePrefix("F/")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value =
            if ('/' in text) {
                val (numerator, denominator) = text.split('/', limit = 2).map { it.trim().toDoubleOrNull() }
                if (numerator == null || denominator == null || denominator == 0.0) return null
                numerator / denominator
            } else text.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    /** "1.8" -> "f/1.8", "2.0" -> "f/2", "1.68" -> "f/1.68" (phone lenses quote two decimals). */
    fun aperture(raw: String?, locale: Locale): String? =
        parseNumber(raw)?.let { "f/" + decimal(it, 2, locale) }

    /**
     * Exposure time in seconds. Short exposures read as photographers write them ("1/120 s");
     * long ones, or values that are not a clean reciprocal, as decimals ("0.4 s", "2 s").
     */
    fun exposure(raw: String?, locale: Locale): String? {
        val seconds = parseNumber(raw) ?: return null
        if (seconds < 1.0) {
            val reciprocal = 1.0 / seconds
            val rounded = reciprocal.roundToLong()
            if (rounded >= 2 && abs(reciprocal - rounded) / reciprocal < 0.03) return "1/$rounded s"
        }
        return decimal(seconds, if (seconds < 1.0) 2 else 1, locale) + " s"
    }

    /** "4300/1000" -> "4.3 mm", "26" -> "26 mm". */
    fun focalLength(raw: String?, locale: Locale): String? =
        parseNumber(raw)?.let { decimal(it, 1, locale) + " mm" }

    fun iso(value: Int?): String? = value?.takeIf { it > 0 }?.let { "ISO $it" }

    /**
     * Joins maker and model without repeating the maker ("Google" + "Pixel 8" -> "Google Pixel 8",
     * "Apple" + "Apple iPhone 15" -> "Apple iPhone 15"). Upper-case makers keep their casing.
     */
    fun cameraName(make: String?, model: String?): String? {
        val maker = make?.trim().orEmpty()
            .split(' ')
            .filter { it.isNotEmpty() && it.trimEnd('.', ',').uppercase(Locale.ROOT) !in CorporateSuffixes }
            .joinToString(" ")
        val product = model?.trim().orEmpty()
        val makerHead = maker.substringBefore(' ')
        return when {
            maker.isEmpty() && product.isEmpty() -> null
            maker.isEmpty() -> product
            product.isEmpty() -> maker
            product.startsWith(makerHead, ignoreCase = true) -> product
            else -> "$maker $product"
        }
    }

    private val CorporateSuffixes = setOf("CORPORATION", "CORP", "IMAGING", "CO", "LTD", "INC", "COMPANY")

    /** "image/jpeg" -> "JPEG"; falls back to the file extension, then to null. */
    fun formatName(mimeType: String?, displayName: String?): String? {
        val mime = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        KnownFormats[mime]?.let { return it }
        val subtype = mime.substringAfter('/', "").substringBefore(';').substringBefore('+').removePrefix("x-")
        if (subtype.isNotEmpty() && subtype != "*" && subtype != "octet-stream") {
            return subtype.uppercase(Locale.ROOT)
        }
        val extension = displayName?.substringAfterLast('.', "")?.trim().orEmpty()
        return extension.takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase(Locale.ROOT)
    }

    /** "4000 × 3000 · 12 MP"; null when either side is unknown. */
    fun dimensions(width: Int, height: Int, locale: Locale): String? {
        if (width <= 0 || height <= 0) return null
        val megapixels = width.toLong() * height / 1_000_000.0
        val size = "$width × $height"
        return if (megapixels >= 0.1) "$size · ${decimal(megapixels, 1, locale)} MP" else size
    }

    /** "1:05", "1:02:03". */
    fun duration(millis: Long): String? {
        if (millis <= 0) return null
        val total = millis / 1_000
        val hours = total / 3_600
        val minutes = total % 3_600 / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        else "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }

    /** Coordinates stay locale-neutral so the separator never collides with a decimal comma. */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

    /** A capture moment: local wall-clock time plus the offset it was recorded with, if known. */
    data class Moment(val local: LocalDateTime, val offset: ZoneOffset?)

    fun exifMoment(dateTimeOriginal: String?, offsetTimeOriginal: String?): Moment? {
        val text = dateTimeOriginal?.trim()?.takeIf { it.length >= 19 } ?: return null
        val local = runCatching { LocalDateTime.parse(text.substring(0, 19), exifDateTime) }.getOrNull() ?: return null
        val offset = offsetTimeOriginal?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        return Moment(local, offset)
    }

    fun fileMoment(epochMillis: Long, zone: ZoneId): Moment? =
        if (epochMillis <= 0) null
        else Moment(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone), null)

    fun date(moment: Moment, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(moment.local)

    /** Local time; the recorded offset is appended ("15:56 · UTC−03:00") when EXIF has one. */
    fun time(moment: Moment, locale: Locale): String {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(moment.local)
        val offset = moment.offset ?: return time
        val id = if (offset == ZoneOffset.UTC) "" else offset.id.replace('-', '−')
        return "$time · UTC$id"
    }

    /** "12.4 Mb/s", "128 kb/s", "800 b/s"; null for unknown or non-positive rates. */
    fun bitrate(bitsPerSecond: Long?, locale: Locale): String? {
        val bps = bitsPerSecond?.takeIf { it > 0 } ?: return null
        return when {
            bps >= 1_000_000 -> decimal(bps / 1_000_000.0, 1, locale) + " Mb/s"
            bps >= 1_000 -> decimal(bps / 1_000.0, 0, locale) + " kb/s"
            else -> "$bps b/s"
        }
    }

    /** "29.97 fps", "30 fps". */
    fun frameRate(fps: Float?, locale: Locale): String? =
        fps?.takeIf { it > 0f && it.isFinite() }?.let { decimal(it.toDouble(), 2, locale) + " fps" }

    /** "48 kHz", "44.1 kHz". */
    fun sampleRate(hz: Int?, locale: Locale): String? =
        hz?.takeIf { it > 0 }?.let { decimal(it / 1_000.0, 1, locale) + " kHz" }

    /** "H.264 / AVC", "AAC"; unknown codecs fall back to the upper-cased subtype. */
    fun codecName(mime: String?): String? {
        val key = mime?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        KnownCodecs[key]?.let { return it }
        val subtype = key.substringAfter('/', "").removePrefix("x-").removePrefix("vnd.")
        return subtype.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT) ?: key
    }

    /** Human label for subtitle or metadata tracks; the raw MIME type when unknown. */
    fun otherStreamName(mime: String?): String? {
        val key = mime?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return OtherStreamNames[key] ?: codecName(key)
    }

    fun isSubtitleMime(mime: String?): Boolean {
        val key = mime?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return key.startsWith("text/") || key in SubtitleMimes
    }

    enum class ChannelLayout { Mono, Stereo, Surround51, Surround71, Other }

    fun channelLayout(count: Int): ChannelLayout = when (count) {
        1 -> ChannelLayout.Mono
        2 -> ChannelLayout.Stereo
        6 -> ChannelLayout.Surround51
        8 -> ChannelLayout.Surround71
        else -> ChannelLayout.Other
    }

    fun colorStandardName(standard: ColorStandard): String = when (standard) {
        ColorStandard.Bt601 -> "BT.601"
        ColorStandard.Bt709 -> "BT.709"
        ColorStandard.Bt2020 -> "BT.2020"
    }

    /** Null for [ColorTransfer.Linear], which has a localized label. */
    fun colorTransferName(transfer: ColorTransfer): String? = when (transfer) {
        ColorTransfer.Sdr -> "SDR"
        ColorTransfer.Hlg -> "HLG"
        ColorTransfer.Pq -> "PQ (HDR10)"
        ColorTransfer.Linear -> null
    }

    /** Display language for an ISO 639 code, or the code itself when the platform cannot name it. */
    fun languageName(code: String?, locale: Locale): String? {
        val tag = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(locale)
        return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: tag
    }

    /** "+0.7 EV", "0 EV", "−1.3 EV". */
    fun exposureBias(ev: Double?, locale: Locale): String? {
        val value = ev?.takeIf { it.isFinite() } ?: return null
        val rounded = Math.round(value * 10) / 10.0
        val sign = when {
            rounded > 0 -> "+"
            rounded < 0 -> "−"
            else -> ""
        }
        return sign + decimal(abs(rounded), 1, locale) + " EV"
    }

    /** "26 mm" for the 35 mm equivalent focal length. */
    fun focalLength35(mm: Int?): String? = mm?.takeIf { it > 0 }?.let { "$it mm" }

    /** "1.5×"; a ratio of about 1 means no digital zoom was applied, so it is dropped. */
    fun digitalZoom(ratio: Double?, locale: Locale): String? =
        ratio?.takeIf { it.isFinite() && it > 1.05 }?.let { decimal(it, 1, locale) + "×" }

    /** "72 dpi", "300 × 150 dpi", "118 dpcm"; EXIF unit 3 is centimetres, anything else inches. */
    fun printResolution(x: Double?, y: Double?, unit: Int?, locale: Locale): String? {
        val horizontal = x?.takeIf { it > 0 && it.isFinite() } ?: return null
        val vertical = y?.takeIf { it > 0 && it.isFinite() } ?: horizontal
        val suffix = if (unit == 3) " dpcm" else " dpi"
        val first = decimal(horizontal, 0, locale)
        val second = decimal(vertical, 0, locale)
        return (if (first == second) first else "$first × $second") + suffix
    }

    /** "35 m"; GPS altitude below sea level is shown with a minus sign. */
    fun altitude(meters: Double?, locale: Locale): String? {
        val value = meters?.takeIf { it.isFinite() } ?: return null
        val text = decimal(abs(value), 0, locale)
        return (if (value < 0 && text != "0") "−" else "") + text + " m"
    }

    /** "HH:mm:ss.SSS" from an EXIF wall-clock moment and its subsecond digits. */
    fun preciseTime(moment: Moment?, subsecond: String?): String? {
        val digits = subsecond?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val local = moment?.local ?: return null
        val millis = digits.padEnd(3, '0').take(3)
        return "%02d:%02d:%02d.%s".format(Locale.ROOT, local.hour, local.minute, local.second, millis)
    }

    /** Medium date plus short time, for secondary timestamps such as the digitized date. */
    fun dateTime(moment: Moment, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(moment.local)

    /** Degrees for EXIF orientations that are pure rotations or flips; null for undefined values. */
    data class OrientationInfo(val rotationDegrees: Int, val mirrored: Boolean)

    fun orientation(value: Int?): OrientationInfo? = when (value) {
        1 -> OrientationInfo(0, false)
        2 -> OrientationInfo(0, true)
        3 -> OrientationInfo(180, false)
        4 -> OrientationInfo(180, true)
        5 -> OrientationInfo(270, true)
        6 -> OrientationInfo(90, false)
        7 -> OrientationInfo(90, true)
        8 -> OrientationInfo(270, false)
        else -> null
    }

    @StringRes
    fun flashLabel(value: Int?): Int? {
        if (value == null || value < 0) return null
        if (value and 0x20 != 0) return R.string.details_flash_none
        val fired = value and 0x01 != 0
        return when ((value shr 3) and 0x03) {
            1 -> if (fired) R.string.details_flash_fired_forced else null
            2 -> R.string.details_flash_off_forced
            3 -> if (fired) R.string.details_flash_fired_auto else R.string.details_flash_off_auto
            else -> if (fired) R.string.details_flash_fired else R.string.details_flash_off
        }
    }

    @StringRes
    fun whiteBalanceLabel(value: Int?): Int? = when (value) {
        0 -> R.string.details_wb_auto
        1 -> R.string.details_wb_manual
        else -> null
    }

    @StringRes
    fun meteringLabel(value: Int?): Int? = when (value) {
        1 -> R.string.details_metering_average
        2 -> R.string.details_metering_center
        3 -> R.string.details_metering_spot
        4 -> R.string.details_metering_multi_spot
        5 -> R.string.details_metering_pattern
        6 -> R.string.details_metering_partial
        else -> null
    }

    @StringRes
    fun exposureProgramLabel(value: Int?): Int? = when (value) {
        1 -> R.string.details_program_manual
        2 -> R.string.details_program_normal
        3 -> R.string.details_program_aperture
        4 -> R.string.details_program_shutter
        5 -> R.string.details_program_creative
        6 -> R.string.details_program_action
        7 -> R.string.details_program_portrait
        8 -> R.string.details_program_landscape
        else -> null
    }

    @StringRes
    fun sceneCaptureLabel(value: Int?): Int? = when (value) {
        0 -> R.string.details_scene_standard
        1 -> R.string.details_scene_landscape
        2 -> R.string.details_scene_portrait
        3 -> R.string.details_scene_night
        else -> null
    }

    /** JPEG variants all read as "JPEG"; null for values that carry no useful label. */
    fun compressionName(value: Int?): String? = when (value) {
        6, 7, 34892 -> "JPEG"
        else -> null
    }

    @StringRes
    fun compressionLabel(value: Int?): Int? = if (value == 1) R.string.details_compression_none else null

    private val SubtitleMimes = setOf(
        "application/x-subrip", "application/x-quicktime-tx3g", "application/ttml+xml",
        "application/x-media3-cues", "application/cea-608", "application/cea-708", "application/dvbsubs",
    )

    private val OtherStreamNames = mapOf(
        "text/vtt" to "WebVTT",
        "text/3gpp-tt" to "3GPP Timed Text",
        "application/x-subrip" to "SubRip",
        "application/x-quicktime-tx3g" to "QuickTime Text",
        "application/ttml+xml" to "TTML",
        "application/x-camera-motion" to "Camera motion",
        "application/x-android-camera-motion" to "Camera motion",
        "application/gyro" to "Gyroscope",
        "application/x-gyro" to "Gyroscope",
        "application/mp4" to "MP4 metadata",
        "application/x-mpegurl" to "HLS",
        "application/id3" to "ID3",
        "application/x-emsg" to "Event messages",
    )

    private val KnownCodecs = mapOf(
        "video/avc" to "H.264 / AVC",
        "video/hevc" to "H.265 / HEVC",
        "video/dolby-vision" to "Dolby Vision",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/mp4v-es" to "MPEG-4 Visual",
        "video/mpeg2" to "MPEG-2",
        "video/3gpp" to "H.263",
        "video/mjpeg" to "Motion JPEG",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/raw" to "PCM",
        "audio/3gpp" to "AMR-NB",
        "audio/amr-wb" to "AMR-WB",
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/eac3-joc" to "E-AC-3 JOC (Atmos)",
        "audio/ac4" to "AC-4",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/g711-alaw" to "G.711 A-law",
        "audio/g711-mlaw" to "G.711 µ-law",
        "audio/alac" to "ALAC",
    )

    private fun decimal(value: Double, maxFractionDigits: Int, locale: Locale): String =
        DecimalFormat("0", DecimalFormatSymbols.getInstance(locale))
            .apply { maximumFractionDigits = maxFractionDigits }
            .format(value)

    private val KnownFormats = mapOf(
        "image/jpeg" to "JPEG",
        "image/jpg" to "JPEG",
        "image/png" to "PNG",
        "image/webp" to "WebP",
        "image/gif" to "GIF",
        "image/heic" to "HEIC",
        "image/heif" to "HEIF",
        "image/heic-sequence" to "HEIC",
        "image/heif-sequence" to "HEIF",
        "image/avif" to "AVIF",
        "image/bmp" to "BMP",
        "image/x-ms-bmp" to "BMP",
        "image/tiff" to "TIFF",
        "image/svg+xml" to "SVG",
        "image/x-adobe-dng" to "DNG",
        "image/dng" to "DNG",
        "image/x-canon-cr2" to "CR2",
        "image/x-canon-cr3" to "CR3",
        "image/x-nikon-nef" to "NEF",
        "image/x-sony-arw" to "ARW",
        "image/x-fuji-raf" to "RAF",
        "image/x-olympus-orf" to "ORF",
        "image/x-panasonic-rw2" to "RW2",
        "video/mp4" to "MP4",
        "video/quicktime" to "MOV",
        "video/webm" to "WebM",
        "video/x-matroska" to "MKV",
        "video/3gpp" to "3GP",
        "video/3gpp2" to "3G2",
        "video/avi" to "AVI",
        "video/x-msvideo" to "AVI",
        "video/mp2t" to "MPEG-TS",
        "video/mpeg" to "MPEG",
    )
}
