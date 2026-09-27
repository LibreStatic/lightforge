package com.librestatic.lightforge.core.model

sealed interface AlbumKey {
    data class Physical(val volumeName: String, val bucketId: Long) : AlbumKey
    data class Virtual(val albumId: Long) : AlbumKey
}

data class AlbumSummary(
    val key: AlbumKey,
    val name: String?,
    val itemCount: Long,
    val latestSortMillis: Long?,
    val cover: MediaKey?,
    val availability: AlbumAvailability,
)

enum class AlbumAvailability { Available, VolumeUnavailable }
