package com.librestatic.lightforge.core.preferences

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
    val appearance: AppearanceSettings = AppearanceSettings(),
) {
    companion object {
        const val CurrentSchemaVersion = 5
    }
}

enum class LibrarySort { DateTaken, DateModified, Name, Size }
enum class LibraryFilter { All, Images, Videos, Animated, Raw }
enum class LibraryGrouping { Day, Month, Year, None }
enum class FolderSelectionMode { AllExceptExcluded, OnlyIncluded }

/** The kinds Photos lists: the primary filter and the extra ones; empty means everything. */
fun LibrarySettings.activeFilters(): Set<LibraryFilter> =
    if (filter == LibraryFilter.All) emptySet() else setOf(filter) + extraFilters.filterNot { it == LibraryFilter.All }

/**
 * Toggles [kind] in the multi-select type filter. Choosing All clears it; removing the last kind
 * returns to All; the first kind chosen stays the primary [LibrarySettings.filter].
 */
fun LibrarySettings.withFilterToggled(kind: LibraryFilter): LibrarySettings {
    if (kind == LibraryFilter.All) return copy(filter = LibraryFilter.All, extraFilters = emptySet())
    val next = activeFilters().let { if (kind in it) it - kind else it + kind }
    val primary = next.firstOrNull() ?: return copy(filter = LibraryFilter.All, extraFilters = emptySet())
    return copy(filter = primary, extraFilters = next - primary)
}

data class LibrarySettings(
    val sort: LibrarySort = LibrarySort.DateTaken,
    val ascending: Boolean = false,
    val filter: LibraryFilter = LibraryFilter.All,
    /** More kinds shown together with [filter] (the Photos type chips are multi-select); always empty while [filter] is All. */
    val extraFilters: Set<LibraryFilter> = emptySet(),
    val grouping: LibraryGrouping = LibraryGrouping.Day,
    val folderSelectionMode: FolderSelectionMode = FolderSelectionMode.AllExceptExcluded,
    val folderRules: Map<FolderSelectionTarget, Boolean> = emptyMap(),
    val collectionOrder: List<String> = emptyList(),
    val hiddenCollections: Set<String> = emptySet(),
    /** Album side panel visibility; null follows the window-size default. */
    val albumSidePanelOpen: Boolean? = null,
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

/**
 * Includes or excludes the folder at [relativePath] and everything below it: the rules of the folder
 * itself and of its sub-folders are replaced by one path rule, so a more specific rule set before no
 * longer overrides it.
 */
fun GallerySettings.withFolderPathSelected(volumeName: String, relativePath: String, selected: Boolean): GallerySettings {
    val path = FolderSelectionPolicy.normalizeRelativePath(relativePath) ?: return this
    val retained = library.folderRules.filterKeys { target ->
        target !is FolderSelectionTarget.Path || target.volumeName != volumeName || !target.relativePath.startsWith(path)
    }
    return copy(library = library.copy(folderRules = retained + (FolderSelectionTarget.Path(volumeName, path) to selected)))
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
    val frameInterpolationEngine: FrameInterpolationEngine = FrameInterpolationEngine.Automatic,
)

enum class VideoScrubbingMode { LegacySeekBar, Filmstrip }

/** Which backend synthesizes in-between frames; the app maps it to the frame-interpolation module. */
enum class FrameInterpolationEngine { Automatic, Dsp, Gpu }

/**
 * App colour palette. [MaterialYou] follows the wallpaper on Android 12+; the others are fixed
 * palettes in the style of popular galleries; [MinimalistBlack] is a monochrome, always-dark,
 * pure-black theme.
 */
enum class ThemePalette { MaterialYou, Neutral, Rounded, Crisp, Warm, MinimalistBlack }

enum class ThemeMode { System, Light, Dark }

data class AppearanceSettings(
    val palette: ThemePalette = ThemePalette.MaterialYou,
    val mode: ThemeMode = ThemeMode.System,
    /** Pure-black (AMOLED) backgrounds whenever the effective theme is dark. */
    val pureBlack: Boolean = false,
) {
    /** Whether the theme is dark, given whether the system is in dark mode. */
    fun isDark(systemDark: Boolean): Boolean = when {
        palette == ThemePalette.MinimalistBlack -> true
        mode == ThemeMode.Light -> false
        mode == ThemeMode.Dark -> true
        else -> systemDark
    }

    /** Whether surfaces are pure black, given whether the system is in dark mode. */
    fun isPureBlack(systemDark: Boolean): Boolean =
        palette == ThemePalette.MinimalistBlack || (pureBlack && isDark(systemDark))
}

enum class VideoHoldAction { None, SlowMotion, FastForward, FastRewind }

enum class VideoDoubleTapAction { None, Zoom, SeekBack, SeekForward, PlayPause }

enum class SlowMotionDragMode { Shuttle, Scrub }

data class GestureSettings(
    val doubleTapZoom: Boolean = true,
    val pinchZoom: Boolean = true,
    val swipeDownToClose: Boolean = true,
    val photoBrightness: Boolean = true,
    val videoBrightness: Boolean = true,
    val videoVolume: Boolean = true,
    /**
     * Legacy input of the double-tap zone migration only. The viewer no longer reads it; the
     * double-tap zone actions replace it.
     */
    val videoSeek: Boolean = true,
    val rotatePhotos: Boolean = true,
    val photoMaxZoom: Float = 8f,
    val videoMaxZoom: Float = 4f,
    val videoSkipSeconds: Int = 10,
    /** Swipe up from the middle of the viewer to open Details. */
    val swipeUpForDetails: Boolean = true,
    val videoHoldLeft: VideoHoldAction = VideoHoldAction.FastRewind,
    val videoHoldCenter: VideoHoldAction = VideoHoldAction.SlowMotion,
    val videoHoldRight: VideoHoldAction = VideoHoldAction.FastForward,
    val videoFastSpeed: Float = 2f,
    val slowMotionDragMode: SlowMotionDragMode = SlowMotionDragMode.Shuttle,
    val videoFastForwardAudio: Boolean = true,
    val videoHoldLiveSpeed: Boolean = true,
    val videoDoubleTapLeft: VideoDoubleTapAction = VideoDoubleTapAction.SeekBack,
    val videoDoubleTapCenter: VideoDoubleTapAction = VideoDoubleTapAction.Zoom,
    val videoDoubleTapRight: VideoDoubleTapAction = VideoDoubleTapAction.SeekForward,
    val videoTwoFingerPlayPause: Boolean = true,
) {
    companion object {
        val VideoFastSpeeds: List<Float> = listOf(1.5f, 2f, 3f, 4f)

        /** Double-tap zone action an absent setting inherits from the legacy toggles. */
        fun legacyDoubleTap(
            side: Boolean,
            videoSeek: Boolean,
            doubleTapZoom: Boolean,
            seek: VideoDoubleTapAction,
        ): VideoDoubleTapAction =
            when {
                side && videoSeek -> seek
                doubleTapZoom -> VideoDoubleTapAction.Zoom
                else -> VideoDoubleTapAction.None
            }
    }
}

data class ThumbnailSettings(
    val cropToFill: Boolean = true,
    val animateMedia: Boolean = false,
    val showVideoDuration: Boolean = true,
    val showFileType: Boolean = true,
    val markFavorites: Boolean = true,
    /** Explicit grid column count ([MinGridColumns]..[MaxGridColumns]), or [AutoGridColumns] to follow the window width. */
    val gridColumns: Int = AutoGridColumns,
)

/** Sentinel for [ThumbnailSettings.gridColumns]: pick columns from the available width. */
const val AutoGridColumns = 0

/** Bounds shared by the Settings picker, timeline pinch and settings import. */
const val MinGridColumns = 2
const val MaxGridColumns = 13

data class OperationSettings(
    val shareWithoutLocationByDefault: Boolean = false,
    val keepLastModifiedWhenPossible: Boolean = true,
    val skipAppDeleteConfirmation: Boolean = false,
    /** Warnings the user chose not to see again; empty means every warning is shown. */
    val dismissedWarnings: Set<DismissibleWarning> = emptySet(),
    /**
     * The user's wish for the advanced storage mode (ADR-046): on by default, can be turned off in
     * Settings. Only a wish: the mode is active when this is on AND the system grants All files
     * access. Builds without the feature keep the value.
     */
    val advancedStorageEnabled: Boolean = true,
    /** Advanced mode only: list media a `.nomedia` or dot-prefixed folder hides (ADR-046). */
    val advancedShowHidden: Boolean = false,
    /** Advanced mode only: remove a folder left empty after a move (ADR-046). */
    val advancedDeleteEmptyFolders: Boolean = false,
    /** Device folders: merge a chain of single-child folders into one card ("A/B/C"). Available in every build. */
    val compactFolderChains: Boolean = true,
    /** Device folders: how each folder is sorted and shown. Key is the folder's identity, value an encoded option set; "default" applies elsewhere. */
    val folderViewOptions: Map<String, String> = emptyMap(),
    /** Device folders: identities (`volume|path/`) of the folders pinned to the top. */
    val pinnedFolders: Set<String> = emptySet(),
    /** Device folders: the last folder opened as `volume|path/`, restored on a cold start. */
    val lastFolder: String? = null,
    /** Advanced mode: identities (`volume|path/`) of folders whose items Photos lists even though they are hidden. */
    val showEvenIfHidden: Set<String> = emptySet(),
    /** Device folders: the cover the user chose per folder, as `volume|path/` to `volume:mediaStoreId`. */
    val folderCovers: Map<String, String> = emptyMap(),
    /** Where the app opens on a cold start. */
    val startScreen: StartScreen = StartScreen.Photos,
    /** Advanced mode: absolute paths of folders the user asked to add to the library even though MediaStore does not index them. */
    val scannedFolders: Set<String> = emptySet(),
)

/** The surface the app opens on. [key] is persisted: never rename it. */
enum class StartScreen(val key: String) {
    Photos("photos"),
    Albums("albums"),
    Folders("folders");

    companion object {
        /** An unknown key (a newer app or a damaged backup) means the default. */
        fun fromKey(key: String?): StartScreen = entries.firstOrNull { it.key == key } ?: Photos
    }
}

/** Confirmation dialogs the user can silence, and bring back from Settings. [key] is persisted: never rename it. */
enum class DismissibleWarning(val key: String) {
    VirtualCropShare("virtual_crop_share"),
    VirtualCropPortableExport("virtual_crop_portable_export");

    companion object {
        /** Unknown keys (from a newer app version or a damaged backup) are ignored. */
        fun fromKeys(keys: Collection<String>): Set<DismissibleWarning> =
            entries.filterTo(linkedSetOf()) { it.key in keys }
    }
}

data class SecuritySettings(
    val appLockEnabled: Boolean = false,
    val destructiveActionLockEnabled: Boolean = false,
    val relockTimeoutMinutes: Int = 1,
    /** Asks for the app lock before hidden items or the hidden-folders list are shown (ADR-046). */
    val protectHiddenItems: Boolean = false,
)

data class AnalysisSettings(
    val fullAnalysisMinimumBatteryPercent: Int = 20,
    /** Off by default: model downloads and updates wait for unmetered Wi-Fi. */
    val modelDownloadsOnMobileData: Boolean = false,
    /** Shows the indexing and analysis status cards at the top of Photos. */
    val showLibraryStatus: Boolean = true,
)


/** Bounded stable identifiers, never translated titles or device album IDs. */
object CollectionLayoutPolicy {
    fun decode(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(',').also { validate(it) }
    fun validate(values: Collection<String>) {
        require(values.size <= 64 && values.size == values.distinct().size)
        require(values.all { it.matches(Regex("[a-z][a-z0-9-]{0,63}")) })
    }
}
