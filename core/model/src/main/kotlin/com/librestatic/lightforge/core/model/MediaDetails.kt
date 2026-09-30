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
