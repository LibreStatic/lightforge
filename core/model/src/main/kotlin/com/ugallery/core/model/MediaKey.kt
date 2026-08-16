package com.ugallery.core.model

/** Stable identity inside MediaStore. IDs are only unique within a volume. */
data class MediaKey(val volumeName: String, val mediaStoreId: Long) {
    init {
        require(volumeName.isNotBlank())
        require(mediaStoreId >= 0)
    }
}

enum class MediaKind { Image, Video }

enum class GrantLevel { None, Selected, Full }

data class LibraryAccess(
    val images: GrantLevel,
    val videos: GrantLevel,
    val unredactedLocation: Boolean,
) {
    val isLimited: Boolean get() = images == GrantLevel.Selected || videos == GrantLevel.Selected
}

