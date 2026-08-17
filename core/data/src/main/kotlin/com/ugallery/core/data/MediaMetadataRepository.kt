package com.ugallery.core.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaExifEntity
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.CheapMediaDetails
import com.ugallery.core.model.ExifLoadResult
import com.ugallery.core.model.ExifMediaDetails
import com.ugallery.core.model.LocationAccessState
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class MediaMetadataRepository(
    private val resolver: ContentResolver,
    database: GalleryDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()

    /** Room-only fields are returned without opening the media file. */
    suspend fun cheapDetails(key: MediaKey): CheapMediaDetails? = withContext(ioDispatcher) {
        dao.media(key.volumeName, key.mediaStoreId)?.cheapDetails()
    }

    suspend fun onLocationPermissionRevoked(): Int = withContext(ioDispatcher) {
        dao.purgeCachedLocations()
    }

    /** EXIF is opened lazily and keyed by MediaStore generation for safe invalidation. */
    suspend fun exifDetails(
        key: MediaKey,
        allowUnredactedLocation: Boolean,
    ): ExifLoadResult = withContext(ioDispatcher) {
        val media = dao.media(key.volumeName, key.mediaStoreId)
            ?: return@withContext ExifLoadResult.MediaUnavailable
        if (media.mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
            return@withContext ExifLoadResult.NotAnImage
        }
        if (!hasValidContainerSignature(media.uri(), media.mimeType)) {
            return@withContext ExifLoadResult.CorruptOrUnsupported
        }
        val cached = dao.exif(key.volumeName, key.mediaStoreId)
        if (cached != null && cached.generationModified == media.generationModified &&
            (!allowUnredactedLocation || cached.locationReadWithPermission)
        ) {
            return@withContext ExifLoadResult.Ready(
                cached.details(allowUnredactedLocation),
                fromCache = true,
            )
        }

        val baseUri = media.uri()
        val requestedUri = if (allowUnredactedLocation) MediaStore.setRequireOriginal(baseUri) else baseUri
        val read = try {
            readExif(requestedUri, allowUnredactedLocation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            // Permission may have changed after foreground revalidation. Never expose cached GPS.
            runCatching { readExif(baseUri, locationWasAuthorized = false) }.getOrNull()
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return@withContext ExifLoadResult.CorruptOrUnsupported

        val entity = read.entity(key, media.generationModified, nowMillis())
        dao.upsertExif(entity)
        ExifLoadResult.Ready(entity.details(allowUnredactedLocation && read.locationWasAuthorized), false)
    }

    private fun readExif(uri: Uri, locationWasAuthorized: Boolean): ReadExif {
        val exif = resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
            ?: throw IOException("Media is unavailable")
        val latLong = if (locationWasAuthorized) exif.latLong else null
        return ReadExif(
            orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
            aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
            exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                .takeIf { it >= 0 },
            latitude = latLong?.getOrNull(0),
            longitude = latLong?.getOrNull(1),
            locationWasAuthorized = locationWasAuthorized,
        )
    }

    private fun hasValidContainerSignature(uri: Uri, mimeType: String?): Boolean {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            ByteArray(16).also { buffer ->
                val read = input.read(buffer)
                if (read < buffer.size) buffer.fill(0, read.coerceAtLeast(0))
            }
        } ?: return false
        return when (mimeType?.lowercase()) {
            "image/jpeg", "image/jpg" -> bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
            "image/png" -> bytes.copyOfRange(0, 8).contentEquals(PngSignature)
            "image/webp" -> bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP"
            "image/heif", "image/heic", "image/avif" -> bytes.decodeToString(4, 8) == "ftyp"
            "image/x-adobe-dng", "image/tiff" ->
                (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) ||
                    (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte())
            else -> true
        }
    }

    private fun MediaItemEntity.uri(): Uri {
        val collection = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
            MediaStore.Video.Media.getContentUri(volumeName)
        } else {
            MediaStore.Images.Media.getContentUri(volumeName)
        }
        return ContentUris.withAppendedId(collection, mediaStoreId)
    }

    private fun MediaItemEntity.cheapDetails() = CheapMediaDetails(
        key = MediaKey(volumeName, mediaStoreId),
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMillis = durationMillis,
        timelineSortMillis = timelineSortMillis,
        relativePath = relativePath,
    )

    private fun MediaExifEntity.details(allowLocation: Boolean): ExifMediaDetails {
        val cachedLatitude = latitude
        val cachedLongitude = longitude
        val location = if (allowLocation && cachedLatitude != null && cachedLongitude != null) {
            MediaLocation(cachedLatitude, cachedLongitude)
        } else {
            null
        }
        return ExifMediaDetails(
            orientation = orientation,
            dateTimeOriginal = dateTimeOriginal,
            offsetTimeOriginal = offsetTimeOriginal,
            make = make,
            model = model,
            lensModel = lensModel,
            focalLength = focalLength,
            aperture = aperture,
            exposureTime = exposureTime,
            iso = iso,
            location = location,
            locationState = when {
                !allowLocation -> LocationAccessState.PermissionRequired
                location != null -> LocationAccessState.Available
                else -> LocationAccessState.Missing
            },
        )
    }

    private data class ReadExif(
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
        val latitude: Double?,
        val longitude: Double?,
        val locationWasAuthorized: Boolean,
    ) {
        fun entity(key: MediaKey, generation: Long, now: Long) = MediaExifEntity(
            volumeName = key.volumeName,
            mediaStoreId = key.mediaStoreId,
            generationModified = generation,
            orientation = orientation,
            dateTimeOriginal = dateTimeOriginal,
            offsetTimeOriginal = offsetTimeOriginal,
            make = make,
            model = model,
            lensModel = lensModel,
            focalLength = focalLength,
            aperture = aperture,
            exposureTime = exposureTime,
            iso = iso,
            latitude = latitude,
            longitude = longitude,
            locationReadWithPermission = locationWasAuthorized,
            cachedAtMillis = now,
        )
    }

    private companion object {
        val PngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
