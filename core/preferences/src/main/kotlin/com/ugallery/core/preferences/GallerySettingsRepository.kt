package com.ugallery.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.gallerySettingsStore by preferencesDataStore("gallery-settings")

class GallerySettingsRepository(context: Context) {
    private val store = context.applicationContext.gallerySettingsStore

    val settings: Flow<GallerySettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map(::decode)

    suspend fun update(transform: (GallerySettings) -> GallerySettings) {
        store.edit { target -> encode(target, transform(decode(target)).normalized()) }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    suspend fun exportTo(output: OutputStream) {
        output.bufferedWriter().use { it.write(exportJson().toString(2)) }
    }

    suspend fun exportJson(): JSONObject = settings.first().toJson()

    suspend fun importFrom(input: InputStream) {
        importJson(input.bufferedReader().use { JSONObject(it.readText()) })
    }

    suspend fun importJson(json: JSONObject) {
        val parsed = json.toSettings()
        require(parsed.schemaVersion in 1..GallerySettings.CurrentSchemaVersion) { "Unsupported settings version" }
        store.edit { target -> encode(target, parsed.normalized()) }
    }

    private fun GallerySettings.normalized() = copy(
        schemaVersion = GallerySettings.CurrentSchemaVersion,
        library = library.copy(
            includedFolders = library.includedFolders.filter(String::isNotBlank).toSet(),
            excludedFolders = library.excludedFolders.filter(String::isNotBlank).toSet(),
        ),
        gestures = gestures.copy(
            photoMaxZoom = gestures.photoMaxZoom.coerceIn(2f, 8f),
            videoMaxZoom = gestures.videoMaxZoom.coerceIn(2f, 8f),
            videoSkipSeconds = gestures.videoSkipSeconds.takeIf { it in setOf(5, 10, 15, 30) } ?: 10,
        ),
        thumbnails = thumbnails.copy(gridColumns = thumbnails.gridColumns.coerceIn(2, 13)),
        security = security.copy(
            relockTimeoutMinutes = security.relockTimeoutMinutes.takeIf { it in setOf(0, 1, 5, 15) } ?: 1,
        ),
    )

    private fun decode(p: Preferences) = GallerySettings(
        library = LibrarySettings(
            sort = p[Keys.LibrarySort]?.enumOrDefault(LibrarySort.DateTaken) ?: LibrarySort.DateTaken,
            ascending = p[Keys.LibraryAscending] ?: false,
            filter = p[Keys.LibraryFilter]?.enumOrDefault(LibraryFilter.All) ?: LibraryFilter.All,
            grouping = p[Keys.LibraryGrouping]?.enumOrDefault(LibraryGrouping.Day) ?: LibraryGrouping.Day,
            folderSelectionMode = p[Keys.FolderMode]?.enumOrDefault(FolderSelectionMode.AllExceptExcluded)
                ?: FolderSelectionMode.AllExceptExcluded,
            includedFolders = p[Keys.IncludedFolders].orEmpty(),
            excludedFolders = p[Keys.ExcludedFolders].orEmpty(),
        ),
        playback = PlaybackSettings(
            autoplayVideos = p[Keys.Autoplay] ?: true,
            startVideosMuted = p[Keys.StartMuted] ?: true,
            loopVideos = p[Keys.Loop] ?: false,
            rememberVideoPosition = p[Keys.RememberPosition] ?: true,
            maximumBrightness = p[Keys.MaximumBrightness] ?: false,
            videoScrubbingMode = p[Keys.VideoScrubbingMode]
                ?.enumOrDefault(VideoScrubbingMode.LegacySeekBar)
                ?: VideoScrubbingMode.LegacySeekBar,
        ),
        gestures = GestureSettings(
            doubleTapZoom = p[Keys.DoubleTapZoom] ?: true,
            pinchZoom = p[Keys.PinchZoom] ?: true,
            swipeDownToClose = p[Keys.SwipeDown] ?: true,
            photoBrightness = p[Keys.PhotoBrightness] ?: true,
            videoBrightness = p[Keys.VideoBrightness] ?: true,
            videoVolume = p[Keys.VideoVolume] ?: true,
            videoSeek = p[Keys.VideoSeek] ?: true,
            rotatePhotos = p[Keys.RotatePhotos] ?: false,
            photoMaxZoom = p[Keys.PhotoMaxZoom] ?: 8f,
            videoMaxZoom = p[Keys.VideoMaxZoom] ?: 4f,
            videoSkipSeconds = p[Keys.VideoSkipSeconds] ?: 10,
            onboardingShown = p[Keys.OnboardingShown] ?: false,
        ),
        thumbnails = ThumbnailSettings(
            cropToFill = p[Keys.CropThumbnails] ?: true,
            animateMedia = p[Keys.AnimateMedia] ?: true,
            showVideoDuration = p[Keys.ShowDuration] ?: true,
            showFileType = p[Keys.ShowFileType] ?: false,
            markFavorites = p[Keys.MarkFavorites] ?: true,
            gridColumns = p[Keys.GridColumns] ?: 3,
        ),
        operations = OperationSettings(
            shareWithoutLocationByDefault = p[Keys.ShareSanitized] ?: false,
            keepLastModifiedWhenPossible = p[Keys.KeepModified] ?: true,
            skipAppDeleteConfirmation = p[Keys.SkipDeleteConfirmation] ?: false,
        ),
        security = SecuritySettings(
            appLockEnabled = p[Keys.AppLock] ?: false,
            destructiveActionLockEnabled = p[Keys.DestructiveLock] ?: false,
            relockTimeoutMinutes = p[Keys.RelockTimeout] ?: 1,
        ),
    ).normalized()

    private fun encode(p: MutablePreferences, s: GallerySettings) {
        p[Keys.LibrarySort] = s.library.sort.name
        p[Keys.LibraryAscending] = s.library.ascending
        p[Keys.LibraryFilter] = s.library.filter.name
        p[Keys.LibraryGrouping] = s.library.grouping.name
        p[Keys.FolderMode] = s.library.folderSelectionMode.name
        p[Keys.IncludedFolders] = s.library.includedFolders
        p[Keys.ExcludedFolders] = s.library.excludedFolders
        p[Keys.Autoplay] = s.playback.autoplayVideos
        p[Keys.StartMuted] = s.playback.startVideosMuted
        p[Keys.Loop] = s.playback.loopVideos
        p[Keys.RememberPosition] = s.playback.rememberVideoPosition
        p[Keys.MaximumBrightness] = s.playback.maximumBrightness
        p[Keys.VideoScrubbingMode] = s.playback.videoScrubbingMode.name
        p[Keys.DoubleTapZoom] = s.gestures.doubleTapZoom
        p[Keys.PinchZoom] = s.gestures.pinchZoom
        p[Keys.SwipeDown] = s.gestures.swipeDownToClose
        p[Keys.PhotoBrightness] = s.gestures.photoBrightness
        p[Keys.VideoBrightness] = s.gestures.videoBrightness
        p[Keys.VideoVolume] = s.gestures.videoVolume
        p[Keys.VideoSeek] = s.gestures.videoSeek
        p[Keys.RotatePhotos] = s.gestures.rotatePhotos
        p[Keys.PhotoMaxZoom] = s.gestures.photoMaxZoom
        p[Keys.VideoMaxZoom] = s.gestures.videoMaxZoom
        p[Keys.VideoSkipSeconds] = s.gestures.videoSkipSeconds
        p[Keys.OnboardingShown] = s.gestures.onboardingShown
        p[Keys.CropThumbnails] = s.thumbnails.cropToFill
        p[Keys.AnimateMedia] = s.thumbnails.animateMedia
        p[Keys.ShowDuration] = s.thumbnails.showVideoDuration
        p[Keys.ShowFileType] = s.thumbnails.showFileType
        p[Keys.MarkFavorites] = s.thumbnails.markFavorites
        p[Keys.GridColumns] = s.thumbnails.gridColumns
        p[Keys.ShareSanitized] = s.operations.shareWithoutLocationByDefault
        p[Keys.KeepModified] = s.operations.keepLastModifiedWhenPossible
        p[Keys.SkipDeleteConfirmation] = s.operations.skipAppDeleteConfirmation
        p[Keys.AppLock] = s.security.appLockEnabled
        p[Keys.DestructiveLock] = s.security.destructiveActionLockEnabled
        p[Keys.RelockTimeout] = s.security.relockTimeoutMinutes
    }

    private fun GallerySettings.toJson() = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("library", JSONObject().apply {
            put("sort", library.sort.name); put("ascending", library.ascending)
            put("filter", library.filter.name); put("grouping", library.grouping.name)
            put("folderSelectionMode", library.folderSelectionMode.name)
            put("includedFolders", org.json.JSONArray(library.includedFolders.sorted()))
            put("excludedFolders", org.json.JSONArray(library.excludedFolders.sorted()))
        })
        put("playback", JSONObject().apply {
            put("autoplayVideos", playback.autoplayVideos); put("startVideosMuted", playback.startVideosMuted)
            put("loopVideos", playback.loopVideos); put("rememberVideoPosition", playback.rememberVideoPosition)
            put("maximumBrightness", playback.maximumBrightness)
            put("videoScrubbingMode", playback.videoScrubbingMode.name)
        })
        put("gestures", JSONObject().apply {
            put("doubleTapZoom", gestures.doubleTapZoom); put("pinchZoom", gestures.pinchZoom)
            put("swipeDownToClose", gestures.swipeDownToClose); put("photoBrightness", gestures.photoBrightness)
            put("videoBrightness", gestures.videoBrightness); put("videoVolume", gestures.videoVolume)
            put("videoSeek", gestures.videoSeek); put("rotatePhotos", gestures.rotatePhotos)
            put("photoMaxZoom", gestures.photoMaxZoom); put("videoMaxZoom", gestures.videoMaxZoom)
            put("videoSkipSeconds", gestures.videoSkipSeconds); put("onboardingShown", gestures.onboardingShown)
        })
        put("thumbnails", JSONObject().apply {
            put("cropToFill", thumbnails.cropToFill); put("animateMedia", thumbnails.animateMedia)
            put("showVideoDuration", thumbnails.showVideoDuration); put("showFileType", thumbnails.showFileType)
            put("markFavorites", thumbnails.markFavorites); put("gridColumns", thumbnails.gridColumns)
        })
        put("operations", JSONObject().apply {
            put("shareWithoutLocationByDefault", operations.shareWithoutLocationByDefault)
            put("keepLastModifiedWhenPossible", operations.keepLastModifiedWhenPossible)
            put("skipAppDeleteConfirmation", operations.skipAppDeleteConfirmation)
        })
        put("security", JSONObject().apply {
            put("appLockEnabled", security.appLockEnabled)
            put("destructiveActionLockEnabled", security.destructiveActionLockEnabled)
            put("relockTimeoutMinutes", security.relockTimeoutMinutes)
        })
    }

    private fun JSONObject.toSettings(): GallerySettings {
        fun JSONObject.bool(name: String, fallback: Boolean) = if (has(name)) getBoolean(name) else fallback
        val p = optJSONObject("playback") ?: JSONObject()
        val l = optJSONObject("library") ?: JSONObject()
        val g = optJSONObject("gestures") ?: JSONObject()
        val t = optJSONObject("thumbnails") ?: JSONObject()
        val o = optJSONObject("operations") ?: JSONObject()
        val s = optJSONObject("security") ?: JSONObject()
        return GallerySettings(
            schemaVersion = optInt("schemaVersion", 1),
            library = LibrarySettings(
                sort = l.optString("sort").enumOrDefault(LibrarySort.DateTaken),
                ascending = l.bool("ascending", false),
                filter = l.optString("filter").enumOrDefault(LibraryFilter.All),
                grouping = l.optString("grouping").enumOrDefault(LibraryGrouping.Day),
                folderSelectionMode = l.optString("folderSelectionMode")
                    .enumOrDefault(FolderSelectionMode.AllExceptExcluded),
                includedFolders = l.stringSet("includedFolders"),
                excludedFolders = l.stringSet("excludedFolders"),
            ),
            playback = PlaybackSettings(
                autoplayVideos = p.bool("autoplayVideos", true),
                startVideosMuted = p.bool("startVideosMuted", true),
                loopVideos = p.bool("loopVideos", false),
                rememberVideoPosition = p.bool("rememberVideoPosition", true),
                maximumBrightness = p.bool("maximumBrightness", false),
                videoScrubbingMode = p.optString("videoScrubbingMode")
                    .enumOrDefault(VideoScrubbingMode.LegacySeekBar),
            ),
            gestures = GestureSettings(g.bool("doubleTapZoom", true), g.bool("pinchZoom", true), g.bool("swipeDownToClose", true), g.bool("photoBrightness", true), g.bool("videoBrightness", true), g.bool("videoVolume", true), g.bool("videoSeek", true), g.bool("rotatePhotos", false), g.optDouble("photoMaxZoom", 8.0).toFloat(), g.optDouble("videoMaxZoom", 4.0).toFloat(), g.optInt("videoSkipSeconds", 10), g.bool("onboardingShown", false)),
            thumbnails = ThumbnailSettings(t.bool("cropToFill", true), t.bool("animateMedia", true), t.bool("showVideoDuration", true), t.bool("showFileType", false), t.bool("markFavorites", true), t.optInt("gridColumns", 3)),
            operations = OperationSettings(o.bool("shareWithoutLocationByDefault", false), o.bool("keepLastModifiedWhenPossible", true), o.bool("skipAppDeleteConfirmation", false)),
            security = SecuritySettings(s.bool("appLockEnabled", false), s.bool("destructiveActionLockEnabled", false), s.optInt("relockTimeoutMinutes", 1)),
        )
    }

    private object Keys {
        val LibrarySort = stringPreferencesKey("library.sort")
        val LibraryAscending = booleanPreferencesKey("library.ascending")
        val LibraryFilter = stringPreferencesKey("library.filter")
        val LibraryGrouping = stringPreferencesKey("library.grouping")
        val FolderMode = stringPreferencesKey("library.folder_mode")
        val IncludedFolders = stringSetPreferencesKey("library.included_folders")
        val ExcludedFolders = stringSetPreferencesKey("library.excluded_folders")
        val Autoplay = booleanPreferencesKey("playback.autoplay")
        val StartMuted = booleanPreferencesKey("playback.start_muted")
        val Loop = booleanPreferencesKey("playback.loop")
        val RememberPosition = booleanPreferencesKey("playback.remember_position")
        val MaximumBrightness = booleanPreferencesKey("playback.maximum_brightness")
        val VideoScrubbingMode = stringPreferencesKey("playback.video_scrubbing_mode")
        val DoubleTapZoom = booleanPreferencesKey("gestures.double_tap_zoom")
        val PinchZoom = booleanPreferencesKey("gestures.pinch_zoom")
        val SwipeDown = booleanPreferencesKey("gestures.swipe_down")
        val PhotoBrightness = booleanPreferencesKey("gestures.photo_brightness")
        val VideoBrightness = booleanPreferencesKey("gestures.video_brightness")
        val VideoVolume = booleanPreferencesKey("gestures.video_volume")
        val VideoSeek = booleanPreferencesKey("gestures.video_seek")
        val RotatePhotos = booleanPreferencesKey("gestures.rotate_photos")
        val PhotoMaxZoom = floatPreferencesKey("gestures.photo_max_zoom")
        val VideoMaxZoom = floatPreferencesKey("gestures.video_max_zoom")
        val VideoSkipSeconds = intPreferencesKey("gestures.video_skip_seconds")
        val OnboardingShown = booleanPreferencesKey("gestures.onboarding_shown")
        val CropThumbnails = booleanPreferencesKey("thumbnails.crop")
        val AnimateMedia = booleanPreferencesKey("thumbnails.animate")
        val ShowDuration = booleanPreferencesKey("thumbnails.duration")
        val ShowFileType = booleanPreferencesKey("thumbnails.file_type")
        val MarkFavorites = booleanPreferencesKey("thumbnails.favorite")
        val GridColumns = intPreferencesKey("thumbnails.columns")
        val ShareSanitized = booleanPreferencesKey("operations.share_sanitized")
        val KeepModified = booleanPreferencesKey("operations.keep_modified")
        val SkipDeleteConfirmation = booleanPreferencesKey("operations.skip_delete_confirmation")
        val AppLock = booleanPreferencesKey("security.app_lock")
        val DestructiveLock = booleanPreferencesKey("security.destructive_lock")
        val RelockTimeout = intPreferencesKey("security.relock_timeout")
    }

    private inline fun <reified T : Enum<T>> String.enumOrDefault(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    private fun JSONObject.stringSet(name: String): Set<String> {
        val values = optJSONArray(name) ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
