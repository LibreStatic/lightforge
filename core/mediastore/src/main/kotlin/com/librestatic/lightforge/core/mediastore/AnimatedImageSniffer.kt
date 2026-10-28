package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import com.librestatic.lightforge.core.model.MediaKind
import java.io.IOException

/**
 * Decides at index time whether an image holds more than one frame, from its container header alone
 * (a few KiB, never a decode). GIF counts as animated by MIME type; WebP, PNG (APNG), AVIF and HEIF are
 * told apart by their own markers, so a static WebP is not animated and an animated AVIF is.
 */
object AnimatedImageSniffer {
    /** Header bytes read per candidate file. */
    const val HeaderBytes: Int = 16 * 1024

    /** Lowercase MIME types whose header has to be read to tell whether they are animated. */
    val SniffedMimeTypes: Set<String> = setOf(
        "image/webp", "image/png", "image/apng", "image/avif", "image/avif-sequence",
        "image/heif", "image/heic", "image/heif-sequence", "image/heic-sequence",
    )

    private val sequenceBrands = setOf("avis", "msf1", "hevc", "hevx", "avcs")

    /** Whether the file has to be opened to tell; GIF is decided by MIME type alone. */
    fun needsHeader(mimeType: String?): Boolean = mimeType?.lowercase() in SniffedMimeTypes

    fun isAnimatedByMime(mimeType: String?): Boolean = mimeType.equals("image/gif", ignoreCase = true)

    /** [header] is the first bytes of the file (at most [HeaderBytes] are needed). */
    fun isAnimated(mimeType: String?, header: ByteArray): Boolean = when {
        isAnimatedByMime(mimeType) -> true
        header.size >= 12 && header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WEBP" -> webpAnimated(header)
        header.size >= 8 && header[0] == 0x89.toByte() && header.ascii(1, 3) == "PNG" -> pngAnimated(header)
        header.size >= 12 && header.ascii(4, 4) == "ftyp" -> isoBrandsAnimated(header)
        else -> false
    }

    private fun webpAnimated(h: ByteArray): Boolean {
        // Extended format: "VP8X" chunk first, flags byte after the 8-byte chunk header; bit 1 is the animation flag.
        if (h.size < 21 || h.ascii(12, 4) != "VP8X") return false
        return h[20].toInt() and 0x02 != 0
    }

    private fun pngAnimated(h: ByteArray): Boolean {
        var offset = 8
        while (offset + 8 <= h.size) {
            val length = h.u32(offset)
            when (h.ascii(offset + 4, 4)) {
                "acTL" -> return true
                // The animation control chunk must precede the first IDAT.
                "IDAT", "IEND" -> return false
            }
            val next = offset.toLong() + 12L + length
            if (length < 0 || next > h.size) return false
            offset = next.toInt()
        }
        return false
    }

    private fun isoBrandsAnimated(h: ByteArray): Boolean {
        val boxSize = h.u32(0).let { if (it < 16) return false else minOf(it, h.size.toLong()).toInt() }
        if (h.ascii(8, 4) in sequenceBrands) return true
        var offset = 16 // major brand at 8, minor version at 12, then compatible brands
        while (offset + 4 <= boxSize) {
            if (h.ascii(offset, 4) in sequenceBrands) return true
            offset += 4
        }
        return false
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.ISO_8859_1)

    private fun ByteArray.u32(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or (this[offset + 3].toLong() and 0xFF)
}

/** Fills [MediaStoreRecord.isAnimated]; opens a file only for the formats that need its header. */
internal class AnimatedImageProbe(private val resolver: ContentResolver) {
    fun complete(record: MediaStoreRecord): MediaStoreRecord {
        if (record.kind != MediaKind.Image) return record
        if (AnimatedImageSniffer.isAnimatedByMime(record.mimeType)) return record.copy(isAnimated = true)
        if (!record.needsFileProbe()) return record
        val header = readHeader(record) ?: return record
        return record.copy(isAnimated = AnimatedImageSniffer.isAnimated(record.mimeType, header))
    }

    private fun readHeader(record: MediaStoreRecord): ByteArray? = try {
        resolver.openInputStream(MediaStoreUriFactory.uriFor(record.key))?.use { input ->
            val buffer = ByteArray(AnimatedImageSniffer.HeaderBytes)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            buffer.copyOf(read)
        }
    } catch (_: IOException) {
        null
    } catch (denied: SecurityException) {
        throw denied
    } catch (_: RuntimeException) {
        null
    }
}
