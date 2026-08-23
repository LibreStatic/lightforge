package com.ugallery.core.model

/** Stable identity inside MediaStore. IDs are only unique within a volume. */
data class MediaKey(val volumeName: String, val mediaStoreId: Long) : java.io.Serializable {
    init {
        require(volumeName.isNotBlank())
        require(mediaStoreId >= 0)
    }
}

enum class MediaKind { Image, Video }

data class TimelineMedia(
    val key: MediaKey,
    val kind: MediaKind,
    val generationModified: Long,
    val timelineSortMillis: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val dateExpiresMillis: Long? = null,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val displayName: String? = null,
    val sizeBytes: Long = 0L,
    val dateModifiedSeconds: Long = 0L,
)

sealed interface TimelineEntry {
    val stableKey: String

    data class DayHeader(
        val epochDay: Long,
        val granularity: TimelineGrouping = TimelineGrouping.Day,
    ) : TimelineEntry {
        override val stableKey: String = "date:${granularity.name}:$epochDay"
    }

    data class Media(val value: TimelineMedia) : TimelineEntry {
        override val stableKey: String =
            "media:${value.key.volumeName}:${value.key.mediaStoreId}"
    }
}

enum class TimelineGrouping { Day, Month, Year }

enum class GrantLevel { None, Selected, Full }

data class LibraryAccess(
    val images: GrantLevel,
    val videos: GrantLevel,
    val unredactedLocation: Boolean,
) {
    val isLimited: Boolean get() = images == GrantLevel.Selected || videos == GrantLevel.Selected
}
