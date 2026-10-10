package com.librestatic.lightforge.core.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.MediaExtractor
import android.os.Build
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.librestatic.lightforge.core.model.AudioStream
import com.librestatic.lightforge.core.model.ColorRange
import com.librestatic.lightforge.core.model.ColorStandard
import com.librestatic.lightforge.core.model.ColorTransfer
import com.librestatic.lightforge.core.model.ContainerInfo
import com.librestatic.lightforge.core.model.ImageTechnicalInfo
import com.librestatic.lightforge.core.model.OtherStream
import com.librestatic.lightforge.core.model.TechnicalMediaDetails
import com.librestatic.lightforge.core.model.VideoStream
import java.io.IOException

/**
 * Reads stream- and format-level data straight from the file. Nothing here is cached, and no
 * video location is ever read. Every reader returns what it could parse instead of failing.
 */
internal class TechnicalMetadataReader(private val resolver: ContentResolver) {

    fun video(uri: Uri): TechnicalMediaDetails? {
        val video = mutableListOf<VideoStream>()
        val audio = mutableListOf<AudioStream>()
        val other = mutableListOf<OtherStream>()
        var trackCount: Int? = null
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    trackCount = extractor.trackCount
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.string(MediaFormat.KEY_MIME)
                        when {
                            mime?.startsWith("video/") == true -> video += videoStream(index, mime, format)
                            mime?.startsWith("audio/") == true -> audio += audioStream(index, mime, format)
                            else -> other += OtherStream(index, mime, format.language())
                        }
                    }
                } finally {
                    extractor.release()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
        val container = containerInfo(uri, trackCount)
        val result = TechnicalMediaDetails(container, video, audio, other)
        return result.takeUnless { it.isEmpty }
    }

    private fun containerInfo(uri: Uri, trackCount: Int?): ContainerInfo? {
        val retriever = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) }
                ?: return null
            fun text(key: Int) = runCatching { retriever.extractMetadata(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            return ContainerInfo(
                mimeType = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                bitrate = text(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 },
                durationMillis = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                captureFrameRate = text(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.takeIf { it > 0f },
                encoder = text(MediaMetadataRetriever.METADATA_KEY_WRITER),
                trackCount = trackCount ?: text(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.toIntOrNull(),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return trackCount?.let { ContainerInfo(trackCount = it) }
        } catch (_: RuntimeException) {
            return trackCount?.let { ContainerInfo(trackCount = it) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun videoStream(index: Int, mime: String?, format: MediaFormat): VideoStream {
        val profile = format.int(MediaFormat.KEY_PROFILE)
        return VideoStream(
            trackIndex = index,
            mimeType = mime,
            profile = profile?.let { CodecNames.videoProfile(mime, it) },
            level = format.int(MediaFormat.KEY_LEVEL)?.let { CodecNames.videoLevel(mime, it) },
            width = format.int(MediaFormat.KEY_WIDTH)?.takeIf { it > 0 },
            height = format.int(MediaFormat.KEY_HEIGHT)?.takeIf { it > 0 },
            rotationDegrees = format.int(MediaFormat.KEY_ROTATION)?.takeIf { it != 0 },
            frameRate = format.number(MediaFormat.KEY_FRAME_RATE)?.toFloat()?.takeIf { it > 0f },
            bitrate = format.number(MediaFormat.KEY_BIT_RATE)?.toLong()?.takeIf { it > 0 },
            colorStandard = when (format.int(MediaFormat.KEY_COLOR_STANDARD)) {
                MediaFormat.COLOR_STANDARD_BT709 -> ColorStandard.Bt709
                MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> ColorStandard.Bt601
                MediaFormat.COLOR_STANDARD_BT2020 -> ColorStandard.Bt2020
                else -> null
            },
            colorTransfer = when (format.int(MediaFormat.KEY_COLOR_TRANSFER)) {
                MediaFormat.COLOR_TRANSFER_LINEAR -> ColorTransfer.Linear
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> ColorTransfer.Sdr
                MediaFormat.COLOR_TRANSFER_ST2084 -> ColorTransfer.Pq
                MediaFormat.COLOR_TRANSFER_HLG -> ColorTransfer.Hlg
                else -> null
            },
            colorRange = when (format.int(MediaFormat.KEY_COLOR_RANGE)) {
                MediaFormat.COLOR_RANGE_FULL -> ColorRange.Full
                MediaFormat.COLOR_RANGE_LIMITED -> ColorRange.Limited
                else -> null
            },
            hasHdrStaticInfo = runCatching { format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO) }.getOrNull() != null,
            bitDepth = profile?.let { CodecNames.videoBitDepth(mime, it) },
            language = format.language(),
        )
    }

    private fun audioStream(index: Int, mime: String?, format: MediaFormat) = AudioStream(
        trackIndex = index,
        mimeType = mime,
        profile = if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            format.int(MediaFormat.KEY_AAC_PROFILE)?.let { CodecNames.aacProfile(it) }
        } else null,
        channels = format.int(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 },
        sampleRate = format.int(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 },
        bitrate = format.number(MediaFormat.KEY_BIT_RATE)?.toLong()?.takeIf { it > 0 },
        bitDepth = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
            format.int(MediaFormat.KEY_PCM_ENCODING)?.let { CodecNames.pcmBitDepth(it) }
        } else null,
        language = format.language(),
    )

    /**
     * [locationAuthorized] means [uri] is the original (unredacted) one. A [SecurityException] is
     * rethrown so the caller can retry with the redacted URI.
     */
    fun image(uri: Uri, mimeType: String?, locationAuthorized: Boolean): TechnicalMediaDetails? {
        val exifPart = try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd -> readExif(ExifInterface(pfd.fileDescriptor), locationAuthorized) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            throw denied
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        val decode = decodeInfo(uri)
        val hasGainMap = mimeType?.lowercase() in JpegMimes && hasGainMap(uri)
        val info = (exifPart ?: ImageTechnicalInfo()).copy(
            decodedColorSpace = decode?.first,
            bitDepth = decode?.second,
            hasGainMap = hasGainMap,
        )
        return TechnicalMediaDetails(image = info).takeUnless { info == ImageTechnicalInfo() }
    }

    private fun readExif(exif: ExifInterface, locationAuthorized: Boolean): ImageTechnicalInfo {
        fun text(tag: String, max: Int = 1_000) = exif.getAttribute(tag)
            ?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
        fun int(tag: String) = exif.getAttributeInt(tag, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        fun double(tag: String) = exif.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() }
        return ImageTechnicalInfo(
            whiteBalance = int(ExifInterface.TAG_WHITE_BALANCE),
            flash = int(ExifInterface.TAG_FLASH),
            exposureProgram = int(ExifInterface.TAG_EXPOSURE_PROGRAM),
            meteringMode = int(ExifInterface.TAG_METERING_MODE),
            exposureBias = double(ExifInterface.TAG_EXPOSURE_BIAS_VALUE),
            focalLength35mm = int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 },
            digitalZoomRatio = double(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)?.takeIf { it > 0.0 },
            sceneCaptureType = int(ExifInterface.TAG_SCENE_CAPTURE_TYPE),
            lensMake = text(ExifInterface.TAG_LENS_MAKE),
            software = text(ExifInterface.TAG_SOFTWARE),
            artist = text(ExifInterface.TAG_ARTIST),
            copyright = text(ExifInterface.TAG_COPYRIGHT),
            description = text(ExifInterface.TAG_IMAGE_DESCRIPTION),
            dateTimeDigitized = text(ExifInterface.TAG_DATETIME_DIGITIZED),
            subsecondTime = text(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
            gpsAltitudeMeters = if (locationAuthorized) {
                exif.getAltitude(Double.NaN).takeIf { it.isFinite() }
            } else null,
            xResolution = double(ExifInterface.TAG_X_RESOLUTION)?.takeIf { it > 0.0 },
            yResolution = double(ExifInterface.TAG_Y_RESOLUTION)?.takeIf { it > 0.0 },
            resolutionUnit = int(ExifInterface.TAG_RESOLUTION_UNIT),
            compression = int(ExifInterface.TAG_COMPRESSION),
            exifColorSpace = int(ExifInterface.TAG_COLOR_SPACE),
            orientation = int(ExifInterface.TAG_ORIENTATION)?.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
        )
    }

    /** Colour space name and bit depth from a bounds-only decode (no pixels are allocated). */
    private fun decodeInfo(uri: Uri): Pair<String?, Int?>? = try {
        resolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            val depth = when (options.outConfig) {
                Bitmap.Config.RGBA_F16 -> 16
                Bitmap.Config.ARGB_8888 -> 8
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    options.outConfig == Bitmap.Config.RGBA_1010102
                ) 10 else null
            }
            colorSpaceName(options.outColorSpace) to depth
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (denied: SecurityException) {
        throw denied
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun colorSpaceName(space: ColorSpace?): String? {
        space ?: return null
        return NamedSpaces.firstOrNull { ColorSpace.get(it.first) == space }?.second
            ?: space.name.takeIf { it.isNotBlank() }
    }

    /** Ultra HDR JPEGs declare the gain map in the XMP of the primary image, near the start. */
    private fun hasGainMap(uri: Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(GainMapScanBytes)
            var filled = 0
            while (filled < buffer.size) {
                val read = input.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.ISO_8859_1).let { "hdrgm:Version" in it || "Semantic=\"GainMap\"" in it }
        } ?: false
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (denied: SecurityException) {
        throw denied
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun MediaFormat.int(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.number(key: String): Number? =
        if (containsKey(key)) runCatching { getNumber(key) }.getOrNull() else null

    private fun MediaFormat.string(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

    /** "und" means undetermined, so it is not worth a row. */
    private fun MediaFormat.language(): String? =
        string(MediaFormat.KEY_LANGUAGE)?.trim()?.takeIf { it.isNotEmpty() && it != "und" }

    private companion object {
        const val GainMapScanBytes = 128 * 1024
        val JpegMimes = setOf("image/jpeg", "image/jpg")
        val NamedSpaces = buildList {
            add(ColorSpace.Named.SRGB to "sRGB")
            add(ColorSpace.Named.DISPLAY_P3 to "Display P3")
            add(ColorSpace.Named.ADOBE_RGB to "Adobe RGB")
            // The HDR transfer variants only exist from Android 14; touching them earlier crashes.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(ColorSpace.Named.BT2020_HLG to "BT.2020 HLG")
                add(ColorSpace.Named.BT2020_PQ to "BT.2020 PQ")
            }
            addAll(listOf(
                ColorSpace.Named.BT2020 to "BT.2020",
                ColorSpace.Named.BT709 to "BT.709",
                ColorSpace.Named.DCI_P3 to "DCI-P3",
                ColorSpace.Named.PRO_PHOTO_RGB to "ProPhoto RGB",
                ColorSpace.Named.LINEAR_SRGB to "Linear sRGB",
                ColorSpace.Named.EXTENDED_SRGB to "Extended sRGB",
                ColorSpace.Named.LINEAR_EXTENDED_SRGB to "Linear extended sRGB",
            ))
        }
    }
}
