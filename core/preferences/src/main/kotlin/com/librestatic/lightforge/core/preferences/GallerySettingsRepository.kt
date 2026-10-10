package com.librestatic.lightforge.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.gallerySettingsStore by preferencesDataStore("gallery-settings")

class GallerySettingsRepository(private val store: DataStore<Preferences>) :
    PortablePreferencesPort {
    constructor(context: Context) : this(context.applicationContext.gallerySettingsStore)

    private val revisionKey = longPreferencesKey("portable.revision")
    private val receiptsKey = stringSetPreferencesKey("portable.receipts")
    private val onboardingKey = booleanPreferencesKey("onboarding.completed")

    private fun advance(target: MutablePreferences): Long =
        Math.addExact(target[revisionKey] ?: 0L, 1L).also { target[revisionKey] = it }

    private data class Receipt(
        val id: String,
        val sha: String,
        val groups: Set<PortablePreferenceGroup>,
        val revision: Long,
    ) {
        fun encode() =
            "$id|$sha|${groups.sortedBy { it.ordinal }.joinToString(",") { it.name }}|$revision"
    }

    private fun receipts(preferences: Preferences): List<Receipt> {
        val encoded = preferences[receiptsKey].orEmpty()
        require(encoded.size <= PortablePreferencesCodec.MaxReceipts)
        val result =
            encoded.map { value ->
                require(value.length <= 256)
                val parts = value.split('|')
                require(parts.size == 4)
                val id = PortablePreferencesCodec.operationId(parts[0])
                require(parts[1].matches(Regex("[0-9a-f]{64}")))
                val groups = parts[2].split(',').map { PortablePreferenceGroup.valueOf(it) }.toSet()
                require(groups.isNotEmpty())
                val revision = parts[3].toLong().also { require(it > 0) }
                Receipt(id, parts[1], groups, revision)
            }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }

    private fun differences(
        current: GallerySettings,
        document: PortablePreferencesDocument,
    ): List<PortablePreferenceDifference> {
        val json = current.toJson()
        return document.values.map { (field, value) ->
            PortablePreferenceDifference(
                field,
                field.validate(json.getJSONObject(field.section).get(field.key)),
                value,
            )
        }
    }

    override suspend fun review(bytes: ByteArray, operationId: String): PortablePreferencesReview {
        PortablePreferencesCodec.operationId(operationId)
        val document = PortablePreferencesCodec.decode(bytes.copyOf())
        val current = store.data.first() // Never convert a storage failure into an empty review.
        val previous = receipts(current).singleOrNull { it.id == operationId }
        if (previous != null && previous.sha != document.sha256)
            throw PortablePreferencesException(PortablePreferencesFailure.OperationMismatch)
        return PortablePreferencesReview(
            operationId,
            document.sha256,
            current[revisionKey] ?: 0L,
            differences(decode(current), document),
            previous?.groups.orEmpty(),
        )
    }

    override suspend fun apply(
        bytes: ByteArray,
        review: PortablePreferencesReview,
        selectedGroups: Set<PortablePreferenceGroup>,
    ): PortablePreferencesResult {
        PortablePreferencesCodec.operationId(review.operationId)
        val document = PortablePreferencesCodec.decode(bytes.copyOf())
        val selected = selectedGroups.toSet()
        if (document.sha256 != review.payloadSha256)
            throw PortablePreferencesException(PortablePreferencesFailure.OperationMismatch)
        require(
            selected.isNotEmpty() &&
                selected.all { group -> document.values.keys.any { it.group == group } }
        )
        var result: PortablePreferencesResult? = null
        store.edit { target ->
            val prior = receipts(target)
            val receipt = prior.singleOrNull { it.id == review.operationId }
            if (receipt != null) {
                if (receipt.sha != document.sha256 || receipt.groups != selected)
                    throw PortablePreferencesException(PortablePreferencesFailure.OperationMismatch)
                result = PortablePreferencesResult(receipt.revision, receipt.groups, true)
            } else {
                // Rebuild the entire diff from the exact bounded payload and destination inside
                // this atomic edit. UI DTO fields never authorize writes by themselves.
                val current = decode(target)
                if (
                    (target[revisionKey] ?: 0L) != review.expectedRevision ||
                        differences(current, document) != review.differences
                )
                    throw PortablePreferencesException(PortablePreferencesFailure.Conflict)
                if (prior.size >= PortablePreferencesCodec.MaxReceipts)
                    throw PortablePreferencesException(PortablePreferencesFailure.ReceiptLimit)
                val merged = current.toJson()
                document.values
                    .filterKeys { it.group in selected }
                    .forEach { (field, value) ->
                        merged.getJSONObject(field.section).put(field.key, field.jsonValue(value))
                    }
                encode(target, merged.toSettings().normalized())
                val revision = advance(target)
                val added = Receipt(review.operationId, document.sha256, selected, revision)
                target[receiptsKey] = (prior + added).map { it.encode() }.toSet()
                result = PortablePreferencesResult(revision, selected, false)
            }
        }
        return checkNotNull(result)
    }

    val settings: Flow<GallerySettings> =
        store.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map(::decode)

    suspend fun update(transform: (GallerySettings) -> GallerySettings) {
        store.edit { target ->
            encode(target, transform(decode(target)).normalized())
            advance(target)
        }
    }

    /**
     * Device-local first-run flag, deliberately outside [GallerySettings] so portable exports,
     * imports and resets never replay or clear it. Null until [resolveOnboarding] runs.
     */
    val onboardingCompleted: Flow<Boolean?> =
        store.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map { it[onboardingKey] }

    /**
     * Settles the first-run flag once: an install that already stored any setting predates the
     * wizard and is treated as completed, so upgrades never see it. Call before other writes.
     */
    suspend fun resolveOnboarding(): Boolean {
        var completed = false
        store.edit { target ->
            completed = target[onboardingKey] ?: target.asMap().isNotEmpty()
            target[onboardingKey] = completed
        }
        return completed
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[onboardingKey] = completed }
    }

    suspend fun reset() {
        // Reset known settings only; atomic import receipts and future unrelated keys survive.
        store.edit { target ->
            encode(target, GallerySettings())
            advance(target)
        }
    }

    suspend fun exportTo(output: OutputStream) {
        output.bufferedWriter().use { it.write(exportJson().toString(2)) }
    }

    suspend fun exportJson(): JSONObject = decode(store.data.first()).toJson()

    suspend fun importFrom(input: InputStream) {
        importJson(JSONObject(PortablePreferencesCodec.readBounded(input).toString(Charsets.UTF_8)))
    }

    suspend fun importJson(json: JSONObject) {
        require(
            json.toString().toByteArray(Charsets.UTF_8).size <= PortablePreferencesCodec.MaxBytes
        )
        val parsed = json.toSettings()
        require(parsed.schemaVersion in 1..GallerySettings.CurrentSchemaVersion) {
            "Unsupported settings version"
        }
        store.edit { target ->
            encode(target, parsed.normalized())
            advance(target)
        }
    }

    private fun GallerySettings.normalized() =
        copy(
            schemaVersion = GallerySettings.CurrentSchemaVersion,
            library =
                library.copy(
                    collectionOrder = library.collectionOrder.also(CollectionLayoutPolicy::validate),
                    hiddenCollections = library.hiddenCollections.also(CollectionLayoutPolicy::validate),
                    folderRules =
                        buildMap {
                            library.folderRules.forEach { (target, selected) ->
                                val normalized =
                                    when (target) {
                                        is FolderSelectionTarget.Path ->
                                            FolderSelectionPolicy.normalizeRelativePath(
                                                    target.relativePath
                                                )
                                                ?.let {
                                                    FolderSelectionTarget.Path(
                                                        target.volumeName,
                                                        it,
                                                    )
                                                }
                                        is FolderSelectionTarget.Bucket -> target
                                    }
                                normalized?.let { put(it, selected) }
                            }
                        }
                ),
            gestures =
                gestures.copy(
                    photoMaxZoom = gestures.photoMaxZoom.coerceIn(2f, 8f),
                    videoMaxZoom = gestures.videoMaxZoom.coerceIn(2f, 8f),
                    videoSkipSeconds =
                        gestures.videoSkipSeconds.takeIf { it in setOf(5, 10, 15, 30) } ?: 10,
                ),
            thumbnails = thumbnails.copy(
                gridColumns = thumbnails.gridColumns.takeIf { it == AutoGridColumns }
                    ?: thumbnails.gridColumns.coerceIn(2, 13),
            ),
            security =
                security.copy(
                    relockTimeoutMinutes =
                        security.relockTimeoutMinutes.takeIf { it in setOf(0, 1, 5, 15) } ?: 1
                ),
            analysis =
                analysis.copy(
                    fullAnalysisMinimumBatteryPercent =
                        analysis.fullAnalysisMinimumBatteryPercent.takeIf {
                            it in setOf(20, 30, 40, 50)
                        } ?: 20
                ),
        )

    private fun decode(p: Preferences) =
        GallerySettings(
                library =
                    LibrarySettings(
                        sort =
                            p[Keys.LibrarySort]?.enumOrDefault(LibrarySort.DateTaken)
                                ?: LibrarySort.DateTaken,
                        ascending = p[Keys.LibraryAscending] ?: false,
                        albumSidePanelOpen = p[Keys.AlbumSidePanelOpen],
                        collectionOrder = runCatching { CollectionLayoutPolicy.decode(p[Keys.CollectionOrder].orEmpty()) }.getOrDefault(emptyList()),
                        hiddenCollections = runCatching { CollectionLayoutPolicy.decode(p[Keys.HiddenCollections].orEmpty()).toSet() }.getOrDefault(emptySet()),
                        filter =
                            p[Keys.LibraryFilter]?.enumOrDefault(LibraryFilter.All)
                                ?: LibraryFilter.All,
                        grouping =
                            p[Keys.LibraryGrouping]?.enumOrDefault(LibraryGrouping.Day)
                                ?: LibraryGrouping.Day,
                        folderSelectionMode =
                            p[Keys.FolderMode]?.enumOrDefault(FolderSelectionMode.AllExceptExcluded)
                                ?: FolderSelectionMode.AllExceptExcluded,
                        folderRules =
                            p[Keys.FolderRules]?.mapNotNull(::decodeStoredFolderRule)?.toMap()
                                ?: migrateLegacyFolderRules(
                                    mode =
                                        p[Keys.FolderMode]?.enumOrDefault(
                                            FolderSelectionMode.AllExceptExcluded
                                        ) ?: FolderSelectionMode.AllExceptExcluded,
                                    included = p[Keys.IncludedFolders].orEmpty(),
                                    excluded = p[Keys.ExcludedFolders].orEmpty(),
                                ),
                    ),
                playback =
                    PlaybackSettings(
                        autoplayVideos = p[Keys.Autoplay] ?: true,
                        startVideosMuted = p[Keys.StartMuted] ?: true,
                        loopVideos = p[Keys.Loop] ?: false,
                        rememberVideoPosition = p[Keys.RememberPosition] ?: true,
                        maximumBrightness = p[Keys.MaximumBrightness] ?: false,
                        videoScrubbingMode =
                            p[Keys.VideoScrubbingMode]?.enumOrDefault(
                                VideoScrubbingMode.LegacySeekBar
                            ) ?: VideoScrubbingMode.LegacySeekBar,
                        frameInterpolationEngine =
                            p[Keys.FrameInterpolationEngine]?.enumOrDefault(
                                FrameInterpolationEngine.Automatic
                            ) ?: FrameInterpolationEngine.Automatic,
                    ),
                gestures =
                    GestureSettings(
                        doubleTapZoom = p[Keys.DoubleTapZoom] ?: true,
                        pinchZoom = p[Keys.PinchZoom] ?: true,
                        swipeDownToClose = p[Keys.SwipeDown] ?: true,
                        photoBrightness = p[Keys.PhotoBrightness] ?: true,
                        videoBrightness = p[Keys.VideoBrightness] ?: true,
                        videoVolume = p[Keys.VideoVolume] ?: true,
                        videoSeek = p[Keys.VideoSeek] ?: true,
                        rotatePhotos = p[Keys.RotatePhotos] ?: true,
                        photoMaxZoom = p[Keys.PhotoMaxZoom] ?: 8f,
                        videoMaxZoom = p[Keys.VideoMaxZoom] ?: 4f,
                        videoSkipSeconds = p[Keys.VideoSkipSeconds] ?: 10,
                        swipeUpForDetails = p[Keys.SwipeUpDetails] ?: true,
                    ),
                thumbnails =
                    ThumbnailSettings(
                        cropToFill = p[Keys.CropThumbnails] ?: true,
                        animateMedia = p[Keys.AnimateMedia] ?: false,
                        showVideoDuration = p[Keys.ShowDuration] ?: true,
                        showFileType = p[Keys.ShowFileType] ?: true,
                        markFavorites = p[Keys.MarkFavorites] ?: true,
                        gridColumns = p[Keys.GridColumnsChoice] ?: AutoGridColumns,
                    ),
                operations =
                    OperationSettings(
                        shareWithoutLocationByDefault = p[Keys.ShareSanitized] ?: false,
                        keepLastModifiedWhenPossible = p[Keys.KeepModified] ?: true,
                        skipAppDeleteConfirmation = p[Keys.SkipDeleteConfirmation] ?: false,
                    ),
                security =
                    SecuritySettings(
                        appLockEnabled = p[Keys.AppLock] ?: false,
                        destructiveActionLockEnabled = p[Keys.DestructiveLock] ?: false,
                        relockTimeoutMinutes = p[Keys.RelockTimeout] ?: 1,
                    ),
                analysis =
                    AnalysisSettings(
                        fullAnalysisMinimumBatteryPercent = p[Keys.FullAnalysisMinimumBattery] ?: 20,
                        modelDownloadsOnMobileData = p[Keys.ModelDownloadsOnMobileData] ?: false,
                    ),
                appearance =
                    AppearanceSettings(
                        palette =
                            p[Keys.AppearancePalette]?.enumOrDefault(ThemePalette.MaterialYou)
                                ?: ThemePalette.MaterialYou,
                        mode = p[Keys.AppearanceMode]?.enumOrDefault(ThemeMode.System) ?: ThemeMode.System,
                        pureBlack = p[Keys.PureBlack] ?: false,
                    ),
            )
            .normalized()

    private fun encode(p: MutablePreferences, s: GallerySettings) {
        p[Keys.LibrarySort] = s.library.sort.name
        p[Keys.LibraryAscending] = s.library.ascending
        s.library.albumSidePanelOpen?.let { p[Keys.AlbumSidePanelOpen] = it } ?: p.remove(Keys.AlbumSidePanelOpen)
        p[Keys.LibraryFilter] = s.library.filter.name
        p[Keys.LibraryGrouping] = s.library.grouping.name
        p[Keys.CollectionOrder] = s.library.collectionOrder.joinToString(",")
        p[Keys.HiddenCollections] = s.library.hiddenCollections.sorted().joinToString(",")
        p[Keys.FolderMode] = s.library.folderSelectionMode.name
        p[Keys.FolderRules] =
            s.library.folderRules.entries.mapTo(linkedSetOf()) { encodeStoredFolderRule(it) }
        p.remove(Keys.IncludedFolders)
        p.remove(Keys.ExcludedFolders)
        p[Keys.Autoplay] = s.playback.autoplayVideos
        p[Keys.StartMuted] = s.playback.startVideosMuted
        p[Keys.Loop] = s.playback.loopVideos
        p[Keys.RememberPosition] = s.playback.rememberVideoPosition
        p[Keys.MaximumBrightness] = s.playback.maximumBrightness
        p[Keys.VideoScrubbingMode] = s.playback.videoScrubbingMode.name
        p[Keys.FrameInterpolationEngine] = s.playback.frameInterpolationEngine.name
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
        p[Keys.SwipeUpDetails] = s.gestures.swipeUpForDetails
        p[Keys.CropThumbnails] = s.thumbnails.cropToFill
        p[Keys.AnimateMedia] = s.thumbnails.animateMedia
        p[Keys.ShowDuration] = s.thumbnails.showVideoDuration
        p[Keys.ShowFileType] = s.thumbnails.showFileType
        p[Keys.MarkFavorites] = s.thumbnails.markFavorites
        p[Keys.GridColumnsChoice] = s.thumbnails.gridColumns
        p[Keys.ShareSanitized] = s.operations.shareWithoutLocationByDefault
        p[Keys.KeepModified] = s.operations.keepLastModifiedWhenPossible
        p[Keys.SkipDeleteConfirmation] = s.operations.skipAppDeleteConfirmation
        p[Keys.AppLock] = s.security.appLockEnabled
        p[Keys.DestructiveLock] = s.security.destructiveActionLockEnabled
        p[Keys.RelockTimeout] = s.security.relockTimeoutMinutes
        p[Keys.FullAnalysisMinimumBattery] = s.analysis.fullAnalysisMinimumBatteryPercent
        p[Keys.ModelDownloadsOnMobileData] = s.analysis.modelDownloadsOnMobileData
        p[Keys.AppearancePalette] = s.appearance.palette.name
        p[Keys.AppearanceMode] = s.appearance.mode.name
        p[Keys.PureBlack] = s.appearance.pureBlack
    }

    private fun GallerySettings.toJson() =
        JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put(
                "library",
                JSONObject().apply {
                    put("sort", library.sort.name)
                    put("ascending", library.ascending)
                    library.albumSidePanelOpen?.let { put("albumSidePanelOpen", it) }
                    put("filter", library.filter.name)
                    put("grouping", library.grouping.name)
                    put("collectionOrder", library.collectionOrder.joinToString(","))
                    put("hiddenCollections", library.hiddenCollections.sorted().joinToString(","))
                    put("folderSelectionMode", library.folderSelectionMode.name)
                    put(
                        "folderRules",
                        JSONArray().apply {
                            library.folderRules.entries
                                .sortedBy { encodeStoredFolderRule(it) }
                                .forEach { put(folderRuleJson(it.key, it.value)) }
                        },
                    )
                },
            )
            put(
                "playback",
                JSONObject().apply {
                    put("autoplayVideos", playback.autoplayVideos)
                    put("startVideosMuted", playback.startVideosMuted)
                    put("loopVideos", playback.loopVideos)
                    put("rememberVideoPosition", playback.rememberVideoPosition)
                    put("maximumBrightness", playback.maximumBrightness)
                    put("videoScrubbingMode", playback.videoScrubbingMode.name)
                    put("frameInterpolationEngine", playback.frameInterpolationEngine.name)
                },
            )
            put(
                "gestures",
                JSONObject().apply {
                    put("doubleTapZoom", gestures.doubleTapZoom)
                    put("pinchZoom", gestures.pinchZoom)
                    put("swipeDownToClose", gestures.swipeDownToClose)
                    put("photoBrightness", gestures.photoBrightness)
                    put("videoBrightness", gestures.videoBrightness)
                    put("videoVolume", gestures.videoVolume)
                    put("videoSeek", gestures.videoSeek)
                    put("rotatePhotos", gestures.rotatePhotos)
                    put("photoMaxZoom", gestures.photoMaxZoom)
                    put("videoMaxZoom", gestures.videoMaxZoom)
                    put("videoSkipSeconds", gestures.videoSkipSeconds)
                    put("swipeUpForDetails", gestures.swipeUpForDetails)
                },
            )
            put(
                "thumbnails",
                JSONObject().apply {
                    put("cropToFill", thumbnails.cropToFill)
                    put("animateMedia", thumbnails.animateMedia)
                    put("showVideoDuration", thumbnails.showVideoDuration)
                    put("showFileType", thumbnails.showFileType)
                    put("markFavorites", thumbnails.markFavorites)
                    put("gridColumns", thumbnails.gridColumns)
                },
            )
            put(
                "operations",
                JSONObject().apply {
                    put("shareWithoutLocationByDefault", operations.shareWithoutLocationByDefault)
                    put("keepLastModifiedWhenPossible", operations.keepLastModifiedWhenPossible)
                    put("skipAppDeleteConfirmation", operations.skipAppDeleteConfirmation)
                },
            )
            put(
                "security",
                JSONObject().apply {
                    put("appLockEnabled", security.appLockEnabled)
                    put("destructiveActionLockEnabled", security.destructiveActionLockEnabled)
                    put("relockTimeoutMinutes", security.relockTimeoutMinutes)
                },
            )
            put(
                "analysis",
                JSONObject().apply {
                    put(
                        "fullAnalysisMinimumBatteryPercent",
                        analysis.fullAnalysisMinimumBatteryPercent,
                    )
                    put("modelDownloadsOnMobileData", analysis.modelDownloadsOnMobileData)
                },
            )
            put(
                "appearance",
                JSONObject().apply {
                    put("palette", appearance.palette.name)
                    put("mode", appearance.mode.name)
                    put("pureBlack", appearance.pureBlack)
                },
            )
        }

    private fun JSONObject.toSettings(): GallerySettings {
        fun JSONObject.bool(name: String, fallback: Boolean) =
            if (has(name)) getBoolean(name) else fallback
        val p = optJSONObject("playback") ?: JSONObject()
        val l = optJSONObject("library") ?: JSONObject()
        val g = optJSONObject("gestures") ?: JSONObject()
        val t = optJSONObject("thumbnails") ?: JSONObject()
        val o = optJSONObject("operations") ?: JSONObject()
        val s = optJSONObject("security") ?: JSONObject()
        val a = optJSONObject("analysis") ?: JSONObject()
        val ap = optJSONObject("appearance") ?: JSONObject()
        return GallerySettings(
            schemaVersion = optInt("schemaVersion", 1),
            library =
                LibrarySettings(
                    sort = l.optString("sort").enumOrDefault(LibrarySort.DateTaken),
                    ascending = l.bool("ascending", false),
                    albumSidePanelOpen = if (l.has("albumSidePanelOpen")) l.optBoolean("albumSidePanelOpen") else null,
                    filter = l.optString("filter").enumOrDefault(LibraryFilter.All),
                    grouping = l.optString("grouping").enumOrDefault(LibraryGrouping.Day),
                    collectionOrder = CollectionLayoutPolicy.decode(l.optString("collectionOrder", "")),
                    hiddenCollections = CollectionLayoutPolicy.decode(l.optString("hiddenCollections", "")).toSet(),
                    folderSelectionMode =
                        l.optString("folderSelectionMode")
                            .enumOrDefault(FolderSelectionMode.AllExceptExcluded),
                    folderRules =
                        if (l.has("folderRules")) {
                            l.optJSONArray("folderRules").toFolderRules()
                        } else {
                            migrateLegacyFolderRules(
                                mode =
                                    l.optString("folderSelectionMode")
                                        .enumOrDefault(FolderSelectionMode.AllExceptExcluded),
                                included = l.stringSet("includedFolders"),
                                excluded = l.stringSet("excludedFolders"),
                            )
                        },
                ),
            playback =
                PlaybackSettings(
                    autoplayVideos = p.bool("autoplayVideos", true),
                    startVideosMuted = p.bool("startVideosMuted", true),
                    loopVideos = p.bool("loopVideos", false),
                    rememberVideoPosition = p.bool("rememberVideoPosition", true),
                    maximumBrightness = p.bool("maximumBrightness", false),
                    videoScrubbingMode =
                        p.optString("videoScrubbingMode")
                            .enumOrDefault(VideoScrubbingMode.LegacySeekBar),
                    frameInterpolationEngine =
                        p.optString("frameInterpolationEngine")
                            .enumOrDefault(FrameInterpolationEngine.Automatic),
                ),
            gestures =
                GestureSettings(
                    g.bool("doubleTapZoom", true),
                    g.bool("pinchZoom", true),
                    g.bool("swipeDownToClose", true),
                    g.bool("photoBrightness", true),
                    g.bool("videoBrightness", true),
                    g.bool("videoVolume", true),
                    g.bool("videoSeek", true),
                    g.bool("rotatePhotos", true),
                    g.optDouble("photoMaxZoom", 8.0).toFloat(),
                    g.optDouble("videoMaxZoom", 4.0).toFloat(),
                    g.optInt("videoSkipSeconds", 10),
                    g.bool("swipeUpForDetails", true),
                ),
            thumbnails =
                ThumbnailSettings(
                    t.bool("cropToFill", true),
                    t.bool("animateMedia", false),
                    t.bool("showVideoDuration", true),
                    t.bool("showFileType", true),
                    t.bool("markFavorites", true),
                    t.optInt("gridColumns", AutoGridColumns),
                ),
            operations =
                OperationSettings(
                    o.bool("shareWithoutLocationByDefault", false),
                    o.bool("keepLastModifiedWhenPossible", true),
                    o.bool("skipAppDeleteConfirmation", false),
                ),
            security =
                SecuritySettings(
                    s.bool("appLockEnabled", false),
                    s.bool("destructiveActionLockEnabled", false),
                    s.optInt("relockTimeoutMinutes", 1),
                ),
            analysis =
                AnalysisSettings(
                    a.optInt("fullAnalysisMinimumBatteryPercent", 20),
                    a.bool("modelDownloadsOnMobileData", false),
                ),
            appearance =
                AppearanceSettings(
                    palette = ap.optString("palette").enumOrDefault(ThemePalette.MaterialYou),
                    mode = ap.optString("mode").enumOrDefault(ThemeMode.System),
                    pureBlack = ap.bool("pureBlack", false),
                ),
        )
    }

    private object Keys {
        val LibrarySort = stringPreferencesKey("library.sort")
        val LibraryAscending = booleanPreferencesKey("library.ascending")
        val AlbumSidePanelOpen = booleanPreferencesKey("library.album_side_panel_open")
        val LibraryFilter = stringPreferencesKey("library.filter")
        val LibraryGrouping = stringPreferencesKey("library.grouping")
        val CollectionOrder = stringPreferencesKey("library.collectionOrder")
        val HiddenCollections = stringPreferencesKey("library.hiddenCollections")
        val FolderMode = stringPreferencesKey("library.folder_mode")
        val FolderRules = stringSetPreferencesKey("library.folder_rules")
        val IncludedFolders = stringSetPreferencesKey("library.included_folders")
        val ExcludedFolders = stringSetPreferencesKey("library.excluded_folders")
        val Autoplay = booleanPreferencesKey("playback.autoplay")
        val StartMuted = booleanPreferencesKey("playback.start_muted")
        val Loop = booleanPreferencesKey("playback.loop")
        val RememberPosition = booleanPreferencesKey("playback.remember_position")
        val MaximumBrightness = booleanPreferencesKey("playback.maximum_brightness")
        val VideoScrubbingMode = stringPreferencesKey("playback.video_scrubbing_mode")
        val FrameInterpolationEngine = stringPreferencesKey("playback.frame_interpolation_engine")
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
        val SwipeUpDetails = booleanPreferencesKey("gestures.swipe_up_details")
        val CropThumbnails = booleanPreferencesKey("thumbnails.crop")
        val AnimateMedia = booleanPreferencesKey("thumbnails.animate")
        val ShowDuration = booleanPreferencesKey("thumbnails.duration")
        val ShowFileType = booleanPreferencesKey("thumbnails.file_type")
        val MarkFavorites = booleanPreferencesKey("thumbnails.favorite")
        // The legacy "thumbnails.columns" key was written on every launch, so it cannot tell an
        // explicit choice from the old default; only deliberate choices land in this key.
        val GridColumnsChoice = intPreferencesKey("thumbnails.columns.choice")
        val ShareSanitized = booleanPreferencesKey("operations.share_sanitized")
        val KeepModified = booleanPreferencesKey("operations.keep_modified")
        val SkipDeleteConfirmation = booleanPreferencesKey("operations.skip_delete_confirmation")
        val AppLock = booleanPreferencesKey("security.app_lock")
        val DestructiveLock = booleanPreferencesKey("security.destructive_lock")
        val RelockTimeout = intPreferencesKey("security.relock_timeout")
        val FullAnalysisMinimumBattery = intPreferencesKey("analysis.full_minimum_battery_percent")
        val ModelDownloadsOnMobileData = booleanPreferencesKey("analysis.model_downloads_on_mobile_data")
        val AppearancePalette = stringPreferencesKey("appearance.palette")
        val AppearanceMode = stringPreferencesKey("appearance.mode")
        val PureBlack = booleanPreferencesKey("appearance.pure_black")
    }

    private inline fun <reified T : Enum<T>> String.enumOrDefault(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    private fun JSONObject.stringSet(name: String): Set<String> {
        val values = optJSONArray(name) ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) values
                .optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    private fun folderRuleJson(target: FolderSelectionTarget, selected: Boolean) =
        JSONObject().apply {
            put("selected", selected)
            put("volumeName", target.volumeName)
            when (target) {
                is FolderSelectionTarget.Path -> {
                    put("type", "path")
                    put("relativePath", target.relativePath)
                }
                is FolderSelectionTarget.Bucket -> {
                    put("type", "bucket")
                    put("bucketId", target.bucketId)
                }
            }
        }

    private fun encodeStoredFolderRule(entry: Map.Entry<FolderSelectionTarget, Boolean>): String =
        folderRuleJson(entry.key, entry.value).toString()

    private fun decodeStoredFolderRule(value: String): Pair<FolderSelectionTarget, Boolean>? =
        runCatching { JSONObject(value).toFolderRule() }.getOrNull()

    private fun JSONObject.toFolderRule(): Pair<FolderSelectionTarget, Boolean>? {
        val volume = optString("volumeName").takeIf(String::isNotBlank) ?: return null
        val target =
            when (optString("type")) {
                "path" ->
                    FolderSelectionPolicy.normalizeRelativePath(optString("relativePath"))?.let {
                        FolderSelectionTarget.Path(volume, it)
                    }
                "bucket" ->
                    if (has("bucketId")) FolderSelectionTarget.Bucket(volume, getLong("bucketId"))
                    else null
                else -> null
            } ?: return null
        return target to optBoolean("selected", false)
    }

    private fun JSONArray?.toFolderRules(): Map<FolderSelectionTarget, Boolean> = buildMap {
        val array = this@toFolderRules ?: return@buildMap
        for (index in 0 until array.length()) {
            runCatching { array.optJSONObject(index)?.toFolderRule() }
                .getOrNull()
                ?.let { (target, selected) -> put(target, selected) }
        }
    }
}
