package com.ugallery.core.editing.image

import androidx.exifinterface.media.ExifInterface
import com.ugallery.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoExifMetadataTest {
    @Test
    fun copiesCaptureDateLocationAndCameraTags() {
        val tags = PhotoExifMetadata.copiedTags
        listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH,
        ).forEach { assertTrue(it, it in tags) }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun neverCopiesFrameDependentTags() {
        val tags = PhotoExifMetadata.copiedTags
        listOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
            ExifInterface.TAG_STRIP_OFFSETS,
            ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_SUBJECT_AREA,
            ExifInterface.TAG_SUBJECT_LOCATION,
            ExifInterface.TAG_MAKER_NOTE,
        ).forEach { assertFalse(it, it in tags) }
    }

    @Test
    fun mapsEveryExifOrientationToUprightOperations() {
        assertEquals(emptyList<EditOperation>(), orientationOperations(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(emptyList<EditOperation>(), orientationOperations(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(listOf(EditOperation.Flip(true)), orientationOperations(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(listOf(EditOperation.Rotate(180)), orientationOperations(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(listOf(EditOperation.Flip(false)), orientationOperations(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(
            listOf(EditOperation.Rotate(90), EditOperation.Flip(true)),
            orientationOperations(ExifInterface.ORIENTATION_TRANSPOSE),
        )
        assertEquals(listOf(EditOperation.Rotate(90)), orientationOperations(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(
            listOf(EditOperation.Rotate(270), EditOperation.Flip(true)),
            orientationOperations(ExifInterface.ORIENTATION_TRANSVERSE),
        )
        assertEquals(listOf(EditOperation.Rotate(270)), orientationOperations(ExifInterface.ORIENTATION_ROTATE_270))
    }
}
