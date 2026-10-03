package com.librestatic.lightforge.feature.details

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
