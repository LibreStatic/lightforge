package com.ugallery.core.preferences

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
        const val CurrentSchemaVersion = 4
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
    val includedFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
)

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
