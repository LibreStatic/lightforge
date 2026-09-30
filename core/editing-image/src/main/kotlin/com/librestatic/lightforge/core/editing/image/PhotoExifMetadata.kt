package com.librestatic.lightforge.core.editing.image

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.librestatic.lightforge.core.model.EditOperation
import java.io.File

/**
 * EXIF handling for rendered photo exports.
 *
 * `BitmapFactory` and `BitmapRegionDecoder` ignore the Orientation tag, so the renderer turns it
 * into leading Rotate/Flip operations and every later operation works on the upright image.
 * Rendered pixels are therefore always upright and the copied metadata resets Orientation to 1.
 */
internal object PhotoExifMetadata {
    /**
     * Tags that describe the capture rather than the stored pixels. Orientation, dimensions,
     * thumbnail/strip layout, subject coordinates and maker notes are deliberately absent: they
     * refer to the source frame and would be wrong after a rotate, crop or re-encode.
     */
    val copiedTags: List<String> = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_INDEX,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_EXIF_VERSION,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
    )

    fun readOrientation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * Copies [copiedTags] from [source] into the freshly encoded [destination] and records its
     * upright size. Returns false when the source has no readable EXIF or the output format
     * cannot hold it; the export itself stays valid either way.
     */
    fun copy(resolver: ContentResolver, source: Uri, destination: File, width: Int, height: Int): Boolean =
        runCatching {
            val sourceExif = resolver.openInputStream(source)?.use { ExifInterface(it) } ?: return false
            val target = ExifInterface(destination)
            copiedTags.forEach { tag ->
                sourceExif.getAttribute(tag)?.let { value -> target.setAttribute(tag, value) }
            }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            target.saveAttributes()
            true
        }.getOrDefault(false)
}

/**
 * Operations that make a decoded bitmap upright, in the same order as
 * `ExifInterface`'s rotation-then-horizontal-flip convention.
 */
internal fun orientationOperations(orientation: Int): List<EditOperation> = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> listOf(EditOperation.Flip(horizontal = true))
    ExifInterface.ORIENTATION_ROTATE_180 -> listOf(EditOperation.Rotate(180))
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> listOf(EditOperation.Flip(horizontal = false))
    ExifInterface.ORIENTATION_TRANSPOSE -> listOf(EditOperation.Rotate(90), EditOperation.Flip(horizontal = true))
    ExifInterface.ORIENTATION_ROTATE_90 -> listOf(EditOperation.Rotate(90))
    ExifInterface.ORIENTATION_TRANSVERSE -> listOf(EditOperation.Rotate(270), EditOperation.Flip(horizontal = true))
    ExifInterface.ORIENTATION_ROTATE_270 -> listOf(EditOperation.Rotate(270))
    else -> emptyList()
}
