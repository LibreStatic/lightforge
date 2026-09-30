package com.librestatic.lightforge.core.model

/** Stable identity inside MediaStore. IDs are only unique within a volume. */
data class MediaKey(val volumeName: String, val mediaStoreId: Long) : java.io.Serializable {
    init {
        require(volumeName.isNotBlank())
        require(mediaStoreId >= 0)
    }
}

enum class MediaKind { Image, Video }

/** Presentation metadata shared by indexed library media and one-shot external grants. */
interface ViewerMedia {
    val viewerId: String
    val mediaKey: MediaKey?
    val kind: MediaKind
    val generationModified: Long
    val timelineSortMillis: Long
    val width: Int
    val height: Int
    val durationMillis: Long
    val isFavorite: Boolean
    val displayName: String?
}

/** A visible saved stack, after the current Photos filters have been applied. */
data class TimelineStack(val id: String, val revision: String, val count: Int) {
    init { require(id.isNotBlank() && revision.isNotBlank() && count in 2..500) }
}

data class TimelineMedia(
    val key: MediaKey,
    override val kind: MediaKind,
    override val generationModified: Long,
    override val timelineSortMillis: Long,
    override val width: Int,
    override val height: Int,
    override val durationMillis: Long,
    val dateExpiresMillis: Long? = null,
    override val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    override val displayName: String? = null,
    val sizeBytes: Long = 0L,
    val dateModifiedSeconds: Long = 0L,
    val stack: TimelineStack? = null,
) : ViewerMedia {
    override val viewerId: String get() = "media:${key.volumeName}:${key.mediaStoreId}"
    override val mediaKey: MediaKey get() = key
}

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
            value.stack?.let { "stack:${it.id}" } ?: "media:${value.key.volumeName}:${value.key.mediaStoreId}"
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
