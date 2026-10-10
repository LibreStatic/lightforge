package com.librestatic.lightforge.core.model

data class CheapMediaDetails(
    val key: MediaKey,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val timelineSortMillis: Long,
    val relativePath: String?,
)

data class ExifMediaDetails(
    val orientation: Int,
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val make: String?,
    val model: String?,
    val lensModel: String?,
    val focalLength: String?,
    val aperture: String?,
    val exposureTime: String?,
    val iso: Int?,
    val location: MediaLocation?,
    val locationState: LocationAccessState,
)

data class MediaLocation(val latitude: Double, val longitude: Double)

enum class LocationAccessState { Available, Missing, PermissionRequired }

sealed interface ExifLoadResult {
    data class Ready(val details: ExifMediaDetails, val fromCache: Boolean) : ExifLoadResult
    data object NotAnImage : ExifLoadResult
    data object CorruptOrUnsupported : ExifLoadResult
    data object MediaUnavailable : ExifLoadResult
}

/**
 * Format-level data read on demand from the file (never cached). Everything is typed and raw so
 * the UI can localize it; a null field means the file does not carry it.
 */
data class TechnicalMediaDetails(
    val container: ContainerInfo? = null,
    val videoStreams: List<VideoStream> = emptyList(),
    val audioStreams: List<AudioStream> = emptyList(),
    val otherStreams: List<OtherStream> = emptyList(),
    val image: ImageTechnicalInfo? = null,
) {
    val isEmpty: Boolean
        get() = container == null && videoStreams.isEmpty() && audioStreams.isEmpty() &&
            otherStreams.isEmpty() && image == null
}

data class ContainerInfo(
    val mimeType: String? = null,
    val bitrate: Long? = null,
    val durationMillis: Long? = null,
    /** Capture frame rate when it differs from the playback rate (slow motion). */
    val captureFrameRate: Float? = null,
    val encoder: String? = null,
    val trackCount: Int? = null,
)

enum class ColorStandard { Bt601, Bt709, Bt2020 }

enum class ColorTransfer { Linear, Sdr, Hlg, Pq }

enum class ColorRange { Limited, Full }

data class VideoStream(
    val trackIndex: Int,
    val mimeType: String?,
    val profile: String? = null,
    val level: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Clockwise degrees the frames are rotated for display. */
    val rotationDegrees: Int? = null,
    val frameRate: Float? = null,
    val bitrate: Long? = null,
    val colorStandard: ColorStandard? = null,
    val colorTransfer: ColorTransfer? = null,
    val colorRange: ColorRange? = null,
    val hasHdrStaticInfo: Boolean = false,
    val bitDepth: Int? = null,
    val language: String? = null,
)

data class AudioStream(
    val trackIndex: Int,
    val mimeType: String?,
    /** Codec profile such as "LC" or "HE-AAC", when the format reports one. */
    val profile: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Long? = null,
    val bitDepth: Int? = null,
    val language: String? = null,
)

data class OtherStream(
    val trackIndex: Int,
    val mimeType: String?,
    val language: String? = null,
)

/** Extra still-image data. Enum-like EXIF values stay raw integers; the UI maps them to text. */
data class ImageTechnicalInfo(
    val whiteBalance: Int? = null,
    val flash: Int? = null,
    val exposureProgram: Int? = null,
    val meteringMode: Int? = null,
    val exposureBias: Double? = null,
    val focalLength35mm: Int? = null,
    val digitalZoomRatio: Double? = null,
    val sceneCaptureType: Int? = null,
    val lensMake: String? = null,
    val software: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val description: String? = null,
    val dateTimeDigitized: String? = null,
    val subsecondTime: String? = null,
    /** Metres above sea level; only filled when location access is authorized. */
    val gpsAltitudeMeters: Double? = null,
    val xResolution: Double? = null,
    val yResolution: Double? = null,
    /** EXIF ResolutionUnit: 2 = inch, 3 = centimetre. */
    val resolutionUnit: Int? = null,
    val compression: Int? = null,
    /** EXIF ColorSpace tag: 1 = sRGB, 65535 = uncalibrated. */
    val exifColorSpace: Int? = null,
    val orientation: Int? = null,
    /** Decoded colour space name such as "Display P3" or "BT.2020 HLG". */
    val decodedColorSpace: String? = null,
    val bitDepth: Int? = null,
    val hasGainMap: Boolean = false,
)
