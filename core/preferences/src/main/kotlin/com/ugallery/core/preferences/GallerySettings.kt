package com.ugallery.core.preferences

import java.io.Serializable

data class GallerySettings(
    val schemaVersion: Int = CurrentSchemaVersion,
    val library: LibrarySettings = LibrarySettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val gestures: GestureSettings = GestureSettings(),
    val thumbnails: ThumbnailSettings = ThumbnailSettings(),
    val operations: OperationSettings = OperationSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
) {
    companion object {
        const val CurrentSchemaVersion = 5
    }
}

enum class LibrarySort { DateTaken, DateModified, Name, Size }
enum class LibraryFilter { All, Images, Videos, Animated, Raw }
enum class LibraryGrouping { Day, Month, Year, None }
enum class FolderSelectionMode { AllExceptExcluded, OnlyIncluded }

data class LibrarySettings(
    val sort: LibrarySort = LibrarySort.DateTaken,
    val ascending: Boolean = false,
    val filter: LibraryFilter = LibraryFilter.All,
    val grouping: LibraryGrouping = LibraryGrouping.Day,
    val folderSelectionMode: FolderSelectionMode = FolderSelectionMode.AllExceptExcluded,
    val folderRules: Map<FolderSelectionTarget, Boolean> = emptyMap(),
    val collectionOrder: List<String> = emptyList(),
    val hiddenCollections: Set<String> = emptySet(),
)

sealed interface FolderSelectionTarget : Serializable {
    val volumeName: String

    data class Path(
        override val volumeName: String,
        val relativePath: String,
    ) : FolderSelectionTarget {
        init {
            require(volumeName.isNotBlank())
            require(relativePath.isNotBlank() && relativePath.endsWith('/'))
        }
    }

    data class Bucket(
        override val volumeName: String,
        val bucketId: Long,
    ) : FolderSelectionTarget {
        init { require(volumeName.isNotBlank()) }
    }
}

object FolderSelectionPolicy {
    fun normalizeRelativePath(value: String?): String? {
        val segments = value
            ?.replace('\\', '/')
            ?.split('/')
            ?.filter(String::isNotBlank)
            .orEmpty()
        return segments.takeIf(List<String>::isNotEmpty)?.joinToString(separator = "/", postfix = "/")
    }

    fun isSelected(
        defaultSelected: Boolean,
        rules: Map<FolderSelectionTarget, Boolean>,
        volumeName: String,
        bucketId: Long,
        relativePath: String?,
    ): Boolean {
        rules[FolderSelectionTarget.Bucket(volumeName, bucketId)]?.let { return it }
        val normalizedPath = normalizeRelativePath(relativePath) ?: return defaultSelected
        return rules.asSequence()
            .mapNotNull { (target, selected) ->
                val path = target as? FolderSelectionTarget.Path ?: return@mapNotNull null
                if (path.volumeName == volumeName && normalizedPath.startsWith(path.relativePath)) {
                    path.relativePath.length to selected
                } else null
            }
            .maxByOrNull { it.first }
            ?.second
            ?: defaultSelected
    }

}

internal fun migrateLegacyFolderRules(
    mode: FolderSelectionMode,
    included: Set<String>,
    excluded: Set<String>,
): Map<FolderSelectionTarget, Boolean> = buildMap {
    included.mapNotNull(GalleryFolderToken::decode).forEach { (volume, bucket) ->
        put(FolderSelectionTarget.Bucket(volume, bucket), true)
    }
    excluded.mapNotNull(GalleryFolderToken::decode).forEach { (volume, bucket) ->
        val target = FolderSelectionTarget.Bucket(volume, bucket)
        if (mode == FolderSelectionMode.AllExceptExcluded || target !in this) put(target, false)
    }
}

object GalleryFolderToken {
    fun encode(volumeName: String, bucketId: Long): String = "$volumeName|$bucketId"

    fun decode(token: String): Pair<String, Long>? {
        val separator = token.lastIndexOf('|')
        if (separator <= 0 || separator == token.lastIndex) return null
        return token.substring(0, separator) to (token.substring(separator + 1).toLongOrNull() ?: return null)
    }
}

data class PlaybackSettings(
    val autoplayVideos: Boolean = true,
    val startVideosMuted: Boolean = true,
    val loopVideos: Boolean = false,
    val rememberVideoPosition: Boolean = true,
    val maximumBrightness: Boolean = false,
    val videoScrubbingMode: VideoScrubbingMode = VideoScrubbingMode.LegacySeekBar,
)

enum class VideoScrubbingMode { LegacySeekBar, Filmstrip }

data class GestureSettings(
    val doubleTapZoom: Boolean = true,
    val pinchZoom: Boolean = true,
    val swipeDownToClose: Boolean = true,
    val photoBrightness: Boolean = true,
    val videoBrightness: Boolean = true,
    val videoVolume: Boolean = true,
    val videoSeek: Boolean = true,
    val rotatePhotos: Boolean = false,
    val photoMaxZoom: Float = 8f,
    val videoMaxZoom: Float = 4f,
    val videoSkipSeconds: Int = 10,
    val onboardingShown: Boolean = false,
)

data class ThumbnailSettings(
    val cropToFill: Boolean = true,
    val animateMedia: Boolean = true,
    val showVideoDuration: Boolean = true,
    val showFileType: Boolean = false,
    val markFavorites: Boolean = true,
    val gridColumns: Int = 3,
)

data class OperationSettings(
    val shareWithoutLocationByDefault: Boolean = false,
    val keepLastModifiedWhenPossible: Boolean = true,
    val skipAppDeleteConfirmation: Boolean = false,
)

data class SecuritySettings(
    val appLockEnabled: Boolean = false,
    val destructiveActionLockEnabled: Boolean = false,
    val relockTimeoutMinutes: Int = 1,
)

data class AnalysisSettings(
    val fullAnalysisMinimumBatteryPercent: Int = 20,
)


/** Bounded stable identifiers, never translated titles or device album IDs. */
object CollectionLayoutPolicy {
    fun decode(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(',').also { validate(it) }
    fun validate(values: Collection<String>) {
        require(values.size <= 64 && values.size == values.distinct().size)
        require(values.all { it.matches(Regex("[a-z][a-z0-9-]{0,63}")) })
    }
}
