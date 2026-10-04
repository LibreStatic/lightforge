package com.librestatic.lightforge.feature.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.biometric.BiometricManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.testTagsAsResourceId

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import com.librestatic.lightforge.core.preferences.AutoGridColumns
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.FolderSelectionMode
import com.librestatic.lightforge.core.preferences.FolderSelectionPolicy
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibraryGrouping
import com.librestatic.lightforge.core.preferences.LibrarySort
import com.librestatic.lightforge.core.preferences.VideoScrubbingMode
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryShapeIllustration
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.GalleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.GalleryValueChip
import com.librestatic.lightforge.core.designsystem.GalleryLabelValueLayout
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import com.librestatic.lightforge.core.designsystem.GalleryPaneMetrics
import com.librestatic.lightforge.core.designsystem.ReadableContentMaxWidth
import com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding
import com.librestatic.lightforge.core.designsystem.galleryMasterPaneWidth

data class GalleryFolderOption(
    val volumeName: String,
    val bucketId: Long,
    val relativePath: String?,
    val displayName: String,
    val itemCount: Long,
)

enum class AnalysisStatus { Ready, Running, Paused, Complete }

data class FaceAnalysisUiState(
    val consentGranted: Boolean = false,
    val paused: Boolean = false,
    val completedItems: Long = 0,
    val status: AnalysisStatus? = null,
)

enum class SemanticModelCompatibilityUi { Recommended, Supported, Unsupported }

data class SemanticModelSettingsItemUi(
    val id: String,
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val quality: String,
    val languages: String,
    val compatibility: SemanticModelCompatibilityUi,
    val installed: Boolean,
    val active: Boolean,
    val downloading: Boolean,
    val downloadedBytes: Long = 0,
    val waiting: ModelDownloadWaitUi? = null,
    val error: String? = null,
)

/** Why a queued model download is paused. */
enum class ModelDownloadWaitUi { Network, WiFi, Battery }

@Composable
fun modelDownloadWaitText(wait: ModelDownloadWaitUi): String = stringResource(
    when (wait) {
        ModelDownloadWaitUi.Network -> R.string.model_download_waiting_network
        ModelDownloadWaitUi.WiFi -> R.string.model_download_waiting_wifi
        ModelDownloadWaitUi.Battery -> R.string.model_download_waiting_battery
    },
)

data class SemanticModelSettingsUiState(
    val enabled: Boolean = false,
    val automaticSelection: Boolean = true,
    val activeModelId: String? = null,
    val buildingModelId: String? = null,
    val indexError: String? = null,
    val models: List<SemanticModelSettingsItemUi> = emptyList(),
)

/** Nested settings destinations. [Root] lists categories; every other value is a detail page. */
private enum class SettingsPage { Root, Library, LibraryFolders, Playback, Gestures, Thumbnails, Operations, Security, Backup, AiAnalysis }

@Composable
fun RecognitionSettingsContent(
    state: FaceAnalysisUiState,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyzeAll: () -> Unit,
    onDelete: () -> Unit,
    petCollectionsEnabled: Boolean,
    petAnalysisState: FaceAnalysisUiState = FaceAnalysisUiState(),
    onPetCollectionsEnabledChange: (Boolean) -> Unit,
    onHideDogResults: () -> Unit,
    onHideCatResults: () -> Unit,
    onRestorePetResults: () -> Unit,
    settings: GallerySettings = GallerySettings(),
    folderOptions: List<GalleryFolderOption> = emptyList(),
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit = {},
    onExportSettings: () -> Unit = {},
    onLocalBackup: (() -> Unit)? = null,
    onRemoteBackup: (() -> Unit)? = null,
    onLocalSharing: (() -> Unit)? = null,
    onPetIdentity: (() -> Unit)? = null,
    onOwnSync: (() -> Unit)? = null,
    onOfflinePlaces: (() -> Unit)? = null,
    onImportSettings: () -> Unit = {},
    onResetSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onBack: () -> Unit = {},
    peopleAnalysisEnabled: Boolean = state.consentGranted,
    contentAnalysisEnabled: Boolean = petAnalysisState.consentGranted,
    localAnalysisEnabled: Boolean = peopleAnalysisEnabled && contentAnalysisEnabled && petCollectionsEnabled,
    onAllAnalysisEnabledChange: (Boolean) -> Unit = {},
    onPeopleAnalysisEnabledChange: (Boolean) -> Unit = {},
    onContentAnalysisEnabledChange: (Boolean) -> Unit = {},
    cleanupAnalysisEnabled: Boolean = false,
    onCleanupAnalysisEnabledChange: (Boolean) -> Unit = {},
    semanticModels: SemanticModelSettingsUiState = SemanticModelSettingsUiState(),
    onSemanticEnabledChange: (Boolean) -> Unit = {},
    onSemanticDownload: (String) -> Unit = {},
    onSemanticCancelDownload: (String) -> Unit = {},
    onSemanticActivate: (String, Boolean) -> Unit = { _, _ -> },
    onSemanticDelete: (String) -> Unit = {},
    onSemanticDeleteAll: () -> Unit = {},
    onSemanticAutomaticSelection: () -> Unit = {},
    showHeader: Boolean = true,
    adaptiveInfo: GalleryAdaptiveLayoutInfo? = null,
    modifier: Modifier = Modifier,
) {
    // The master grows from 300 to 360 dp out of the detail's spare room; below 660 dp of content
    // the two panes do not fit and settings shows one page at a time.
    val masterWidth = adaptiveInfo?.let { galleryMasterPaneWidth(it.contentWidth) }
    val twoPane = adaptiveInfo != null && adaptiveInfo.windowClass != GalleryWindowClass.Compact && masterWidth != null
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var folderVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var folderPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun leaveFolderLevel() {
        val current = folderPath
        if (current == null) {
            page = SettingsPage.Library
        } else {
            val segments = current.trimEnd('/').split('/')
            if (segments.size == 1) {
                folderVolume = null
                folderPath = null
            } else {
                folderPath = segments.dropLast(1).joinToString(separator = "/", postfix = "/")
            }
        }
    }

    // Two panes always show a category, so Root falls back to the first one.
    val visiblePage = if (twoPane && page == SettingsPage.Root) SettingsPage.Library else page

    BackHandler {
        when (visiblePage) {
            SettingsPage.LibraryFolders -> leaveFolderLevel()
            SettingsPage.Root -> onBack()
            else -> if (twoPane) onBack() else page = SettingsPage.Root
        }
    }

    val detail: @Composable () -> Unit = {
        // Root is only reachable in single-pane mode, where it is rendered by the caller.
        when (visiblePage) {
            SettingsPage.Root -> Unit
            SettingsPage.Library -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Collections, title = stringResource(R.string.settings_library), onBack = { page = SettingsPage.Root }) {
                LibrarySection(settings, folderOptions, onSettingsChange) {
                    folderVolume = null
                    folderPath = null
                    page = SettingsPage.LibraryFolders
                }
            }
            SettingsPage.LibraryFolders -> FolderSelectionPage(
                settings = settings,
                folderOptions = folderOptions,
                currentVolume = folderVolume,
                currentPath = folderPath,
                onOpenFolder = { volume, path -> folderVolume = volume; folderPath = path },
                onBack = ::leaveFolderLevel,
                onSettingsChange = onSettingsChange,
            )
            SettingsPage.Playback -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Play, title = stringResource(R.string.settings_playback), onBack = { page = SettingsPage.Root }) {
                PlaybackSection(settings, onSettingsChange)
            }
            SettingsPage.Gestures -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Tune, title = stringResource(R.string.settings_gestures), onBack = { page = SettingsPage.Root }) {
                GesturesSection(settings, onSettingsChange)
            }
            SettingsPage.Thumbnails -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Image, title = stringResource(R.string.settings_thumbnails), onBack = { page = SettingsPage.Root }) {
                ThumbnailsSection(settings, onSettingsChange)
            }
            SettingsPage.Operations -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Folder, title = stringResource(R.string.settings_operations), onBack = { page = SettingsPage.Root }) {
                OperationsSection(settings, onSettingsChange)
            }
            SettingsPage.Security -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Lock, title = stringResource(R.string.settings_security), onBack = { page = SettingsPage.Root }) {
                SecuritySection(settings, onSettingsChange)
            }
            SettingsPage.Backup -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.Download, title = stringResource(R.string.settings_backup), onBack = { page = SettingsPage.Root }) {
                BackupSection(onExportSettings, onImportSettings, onResetSettings, onLocalBackup, onRemoteBackup, onOwnSync, onOfflinePlaces, onLocalSharing)
            }
            SettingsPage.AiAnalysis -> SettingsSubPage(embedded = twoPane, illustration = GalleryIcons.AutoAwesome, title = stringResource(R.string.settings_page_ai), onBack = { page = SettingsPage.Root }) {
                if (onPetIdentity != null) OutlinedButton(onClick = onPetIdentity, modifier = Modifier.fillMaxWidth().testTag("settings-pet-identity")) { Text(stringResource(R.string.pet_identity_entry)) }
                AiAnalysisSection(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    state = state,
                    onEnable = onEnable,
                    onPause = onPause,
                    onResume = onResume,
                    onAnalyzeAll = onAnalyzeAll,
                    onDelete = onDelete,
                    onDeleteRequested = { confirmDelete = true },
                    petCollectionsEnabled = petCollectionsEnabled,
                    petAnalysisState = petAnalysisState,
                    onPetCollectionsEnabledChange = onPetCollectionsEnabledChange,
                    onHideDogResults = onHideDogResults,
                    onHideCatResults = onHideCatResults,
                    onRestorePetResults = onRestorePetResults,
                    peopleAnalysisEnabled = peopleAnalysisEnabled,
                    contentAnalysisEnabled = contentAnalysisEnabled,
                    localAnalysisEnabled = localAnalysisEnabled,
                    onAllAnalysisEnabledChange = onAllAnalysisEnabledChange,
                    onPeopleAnalysisEnabledChange = onPeopleAnalysisEnabledChange,
                    onContentAnalysisEnabledChange = onContentAnalysisEnabledChange,
                    cleanupAnalysisEnabled = cleanupAnalysisEnabled,
                    onCleanupAnalysisEnabledChange = onCleanupAnalysisEnabledChange,
                    semanticModels = semanticModels,
                    onSemanticEnabledChange = onSemanticEnabledChange,
                    onSemanticDownload = onSemanticDownload,
                    onSemanticCancelDownload = onSemanticCancelDownload,
                    onSemanticActivate = onSemanticActivate,
                    onSemanticDelete = onSemanticDelete,
                    onSemanticDeleteAll = onSemanticDeleteAll,
                    onSemanticAutomaticSelection = onSemanticAutomaticSelection,
                    showHeader = showHeader,
                )
            }
        }
    }

    val categoryList: @Composable (Modifier) -> Unit = { listModifier ->
        SettingsCategoryList(
            settings = settings,
            aiConsentGranted = peopleAnalysisEnabled || contentAnalysisEnabled || petCollectionsEnabled,
            onOpen = { page = it },
            onOpenAbout = onOpenAbout,
            selectedPage = if (twoPane) visiblePage else null,
            modifier = listModifier,
        )
    }

    Box(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, contentAlignment = Alignment.TopCenter) {
        if (twoPane) {
            val density = LocalDensity.current
            // Window-space x of this pane, so a hinge measured in window coordinates lines up even
            // when the navigation rail sits to its left.
            var originX by remember { mutableStateOf(0.dp) }
            val hinge = adaptiveInfo?.foldInfo?.takeIf { it.enablesSideBySide }
            val hingeListWidth = hinge?.let { it.left - originX }?.takeIf { it >= GalleryPaneMetrics.MasterMinWidth }
            val listWidth = hingeListWidth ?: masterWidth ?: GalleryPaneMetrics.MasterMinWidth
            Row(
                Modifier.fillMaxSize().onGloballyPositioned { originX = with(density) { it.positionInWindow().x.toDp() } },
            ) {
                SettingsRootPage(onBack, Modifier.width(listWidth).fillMaxHeight()) { categoryList(Modifier) }
                if (hingeListWidth != null && hinge != null) Spacer(Modifier.width(hinge.hingeWidth))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.fillMaxSize().widthIn(max = ReadableContentMaxWidth)) {
                        CompositionLocalProvider(LocalSettingsCards provides true) { detail() }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().widthIn(max = ReadableContentMaxWidth)) {
                if (visiblePage == SettingsPage.Root) {
                    SettingsRootPage(onBack) { categoryList(Modifier.widthIn(max = ReadableContentMaxWidth)) }
                } else {
                    detail()
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.face_analysis_delete_title)) },
        text = { Text(stringResource(R.string.face_analysis_delete_body)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text(stringResource(R.string.face_analysis_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.face_analysis_cancel)) }
        },
    )
}

@Composable
private fun SettingsRootPage(onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.settings_title), onBack)
        content()
    }
}

@Composable
private fun SettingsCategoryList(
    settings: GallerySettings,
    aiConsentGranted: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onOpenAbout: () -> Unit,
    selectedPage: SettingsPage? = null,
    modifier: Modifier = Modifier,
) {
    val sortLabel = stringResource(when (settings.library.sort) {
        LibrarySort.DateTaken -> R.string.settings_sort_date_taken
        LibrarySort.DateModified -> R.string.settings_sort_date_modified
        LibrarySort.Name -> R.string.settings_sort_name
        LibrarySort.Size -> R.string.settings_sort_size
    })
    val filterLabel = stringResource(when (settings.library.filter) {
        LibraryFilter.All -> R.string.settings_filter_all
        LibraryFilter.Images -> R.string.settings_filter_images
        LibraryFilter.Videos -> R.string.settings_filter_videos
        LibraryFilter.Animated -> R.string.settings_filter_animated
        LibraryFilter.Raw -> R.string.settings_filter_raw
    })
    val playbackCount = enabledCount(
        settings.playback.autoplayVideos,
        settings.playback.startVideosMuted,
        settings.playback.loopVideos,
        settings.playback.rememberVideoPosition,
        settings.playback.maximumBrightness,
    )
    val gestureCount = enabledCount(
        settings.gestures.doubleTapZoom,
        settings.gestures.pinchZoom,
        settings.gestures.swipeDownToClose,
        settings.gestures.swipeUpForDetails,
        settings.gestures.photoBrightness,
        settings.gestures.videoBrightness,
        settings.gestures.videoVolume,
        settings.gestures.videoSeek,
        settings.gestures.rotatePhotos,
    )
    val thumbnailCount = enabledCount(
        settings.thumbnails.cropToFill,
        settings.thumbnails.animateMedia,
        settings.thumbnails.showVideoDuration,
        settings.thumbnails.showFileType,
        settings.thumbnails.markFavorites,
    )
    val operationsCount = enabledCount(
        settings.operations.shareWithoutLocationByDefault,
        settings.operations.keepLastModifiedWhenPossible,
        settings.operations.skipAppDeleteConfirmation,
    )
    val securityOn = settings.security.appLockEnabled || settings.security.destructiveActionLockEnabled
    val columnsSummary = if (settings.thumbnails.gridColumns == AutoGridColumns) {
        stringResource(R.string.settings_summary_columns_auto)
    } else {
        stringResource(R.string.settings_summary_columns, settings.thumbnails.gridColumns)
    }
    val enabledPattern = stringResource(R.string.settings_summary_enabled)
    val onLabel = stringResource(R.string.settings_summary_on)
    val offLabel = stringResource(R.string.settings_summary_off)

    val twoPane = selectedPage != null
    // The list of a two-pane layout keeps the folder browser under "Library".
    fun selected(page: SettingsPage) = selectedPage == page ||
        (page == SettingsPage.Library && selectedPage == SettingsPage.LibraryFolders)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GallerySpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xl),
    ) {
        SettingsCategoryGroup(stringResource(R.string.settings_group_viewing)) {
            SettingsCategoryRow(GalleryIcons.Collections, stringResource(R.string.settings_library), "$sortLabel · $filterLabel", 0, 4, selected = selected(SettingsPage.Library), chevron = !twoPane) { onOpen(SettingsPage.Library) }
            SettingsCategoryRow(
                GalleryIcons.Play,
                stringResource(R.string.settings_playback),
                enabledPattern.format(playbackCount, 5),
                1,
                4,
                Modifier.testTag("settings_playback_row"),
                selected = selected(SettingsPage.Playback),
                chevron = !twoPane,
            ) { onOpen(SettingsPage.Playback) }
            SettingsCategoryRow(GalleryIcons.Tune, stringResource(R.string.settings_gestures), enabledPattern.format(gestureCount, 9), 2, 4, selected = selected(SettingsPage.Gestures), chevron = !twoPane) { onOpen(SettingsPage.Gestures) }
            SettingsCategoryRow(GalleryIcons.Image, stringResource(R.string.settings_thumbnails), "$columnsSummary · " + enabledPattern.format(thumbnailCount, 5), 3, 4, selected = selected(SettingsPage.Thumbnails), chevron = !twoPane) { onOpen(SettingsPage.Thumbnails) }
        }
        SettingsCategoryGroup(stringResource(R.string.settings_group_management)) {
            SettingsCategoryRow(GalleryIcons.Folder, stringResource(R.string.settings_operations), enabledPattern.format(operationsCount, 3), 0, 3, selected = selected(SettingsPage.Operations), chevron = !twoPane) { onOpen(SettingsPage.Operations) }
            SettingsCategoryRow(GalleryIcons.Lock, stringResource(R.string.settings_security), if (securityOn) onLabel else offLabel, 1, 3, selected = selected(SettingsPage.Security), chevron = !twoPane) { onOpen(SettingsPage.Security) }
            SettingsCategoryRow(GalleryIcons.Download, stringResource(R.string.settings_backup), null, 2, 3, selected = selected(SettingsPage.Backup), chevron = !twoPane) { onOpen(SettingsPage.Backup) }
        }
        AppLanguageGroup(chevron = !twoPane)
        SettingsCategoryGroup(stringResource(R.string.settings_group_intelligence)) {
            SettingsCategoryRow(GalleryIcons.AutoAwesome, stringResource(R.string.settings_page_ai), if (aiConsentGranted) onLabel else offLabel, 0, 1, selected = selected(SettingsPage.AiAnalysis), chevron = !twoPane) { onOpen(SettingsPage.AiAnalysis) }
        }
        SettingsCategoryGroup(stringResource(R.string.settings_group_about)) {
            SettingsCategoryRow(
                GalleryIcons.Info,
                stringResource(R.string.settings_about),
                stringResource(R.string.settings_about_summary),
                0,
                1,
                Modifier.testTag("settings_about_row"),
                chevron = !twoPane,
                onClick = onOpenAbout,
            )
        }
        SettingsListEnd()
    }
}

/**
 * Last item of a settings scroll column: the content scrolls under a launcher taskbar or gesture
 * bar, and its last row can still be scrolled clear of it and of any floating bottom controls.
 */
@Composable
private fun SettingsListEnd() {
    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    Spacer(Modifier.height(galleryBottomContentPadding()))
}

private fun enabledCount(vararg flags: Boolean) = flags.count { it }

/** Per-app language tags; keep in sync with app/src/main/res/xml/locales_config.xml. */
private val AppLanguageTags = listOf("", "en", "es", "fr", "pt", "it", "de")
private val AppLanguageLabels = listOf(
    R.string.settings_language_system,
    R.string.settings_language_en,
    R.string.settings_language_es,
    R.string.settings_language_fr,
    R.string.settings_language_pt,
    R.string.settings_language_it,
    R.string.settings_language_de,
)

/**
 * App language picker. Android 13+ applies it per app through `LocaleManager`; older releases
 * store it in [LegacyAppLanguage], which `MainActivity` applies in `attachBaseContext`.
 */
@Composable
private fun AppLanguageGroup(chevron: Boolean = true) {
    val context = LocalContext.current
    val current = remember(context) {
        val language = if (LegacyAppLanguage.isNeeded) {
            LegacyAppLanguage.tag(context)
        } else {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language.orEmpty()
        }
        AppLanguageTags.indexOf(language).coerceAtLeast(0)
    }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    SettingsCategoryGroup(stringResource(R.string.settings_language)) {
        SettingsCategoryRow(
            Icons.Rounded.Language,
            stringResource(R.string.settings_language),
            stringResource(AppLanguageLabels[current]),
            0,
            1,
            Modifier.testTag("settings_language_row"),
            chevron = chevron,
        ) { dialogVisible = true }
    }
    if (dialogVisible) SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_language),
        options = AppLanguageLabels,
        selectedOption = current,
        optionTestTags = AppLanguageTags.map { "settings_language_" + it.ifEmpty { "system" } },
        onDismiss = { dialogVisible = false },
    ) { index ->
        dialogVisible = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (index == 0) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(AppLanguageTags[index])
        } else if (index != current) {
            LegacyAppLanguage.setTag(context.applicationContext, AppLanguageTags[index])
            LegacyAppLanguage.activity(context)?.recreate()
        }
    }
}

@Composable
private fun SettingsCategoryGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = GallerySpacing.Md),
        )
        Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) { content() }
    }
}

@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val leading: @Composable () -> Unit = {
        // The active row is filled with secondaryContainer, so its icon chip flips to the
        // primary pair to stay distinguishable in every theme.
        Box(
            Modifier.size(48.dp).background(
                if (selected) colors.primary else colors.secondaryContainer,
                MaterialTheme.shapes.extraLarge,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onPrimary else colors.onSecondaryContainer)
        }
    }
    val trailing: (@Composable () -> Unit)? = if (chevron) {
        {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else null
    val supporting: (@Composable () -> Unit)? = summary?.let { value ->
        { Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    if (selected) {
        SegmentedListItem(
            selected = true,
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            modifier = modifier.fillMaxWidth(),
            colors = ListItemDefaults.segmentedColors(
                selectedContainerColor = colors.secondaryContainer,
                selectedContentColor = colors.onSecondaryContainer,
                selectedSupportingContentColor = colors.onSecondaryContainer,
            ),
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
        ) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    } else {
        SegmentedListItem(
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            modifier = modifier.fillMaxWidth(),
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
        ) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Detail width from which the embedded title gets the display style and its illustration. */
private val EmbeddedTitleRoomyWidth = 440.dp

/** True inside the detail pane of a two-pane layout, where sections are grouped into cards. */
private val LocalSettingsCards = compositionLocalOf { false }

@Composable
private fun settingsRowMinHeight() = if (LocalSettingsCards.current) 64.dp else 0.dp

@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    embedded: Boolean = false,
    illustration: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // The category list already provides navigation in two panes, so the detail gets a large
        // title instead of a top bar with a back arrow.
        if (!embedded) SettingsHeader(title, onBack)
        Column(
            Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.widthIn(max = ReadableContentMaxWidth))
                .verticalScroll(rememberScrollState()).padding(if (embedded) GallerySpacing.Xxl else GallerySpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(if (embedded) GallerySpacing.Xl else GallerySpacing.Lg),
        ) {
            if (embedded) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // A narrow detail pane drops the decorative shape and uses a smaller title, so
                    // long words ("Reproducción", "Miniaturansicht") wrap between words or not at all.
                    val roomy = maxWidth >= EmbeddedTitleRoomyWidth
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = (if (roomy) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium)
                                .copy(hyphens = Hyphens.None, lineBreak = LineBreak.Heading),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = GallerySpacing.Md).semantics { heading() },
                        )
                        if (illustration != null && roomy) GalleryShapeIllustration(illustration, size = 96.dp)
                    }
                }
            }
            content()
            SettingsListEnd()
        }
    }
}

/**
 * Groups related rows into a tonal card in the two-pane detail. Outside it (phones) the rows are
 * emitted directly, so the single-pane layout is unchanged.
 */
@Composable
private fun SettingsCard(title: String? = null, content: @Composable () -> Unit) {
    if (!LocalSettingsCards.current) {
        content()
        return
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(GallerySpacing.Md), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = GallerySpacing.Md, end = GallerySpacing.Md, top = GallerySpacing.Sm, bottom = GallerySpacing.Xs),
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    GalleryTopAppBar(
        title = title,
        onBack = onBack,
        navigationContentDescription = stringResource(R.string.settings_back),
    )
}

internal data class FolderTreeNode(
    val volumeName: String,
    val relativePath: String?,
    val name: String,
    val directOption: GalleryFolderOption?,
    val children: List<FolderTreeNode>,
) {
    val options: List<GalleryFolderOption> = buildList {
        directOption?.let(::add)
        children.forEach { addAll(it.options) }
    }
    val itemCount: Long = options.sumOf(GalleryFolderOption::itemCount)
}

private class MutableFolderTreeNode(
    val volumeName: String,
    val relativePath: String?,
    val name: String,
) {
    var directOption: GalleryFolderOption? = null
    val children = linkedMapOf<String, MutableFolderTreeNode>()

    fun freeze(): FolderTreeNode = FolderTreeNode(
        volumeName = volumeName,
        relativePath = relativePath,
        name = name,
        directOption = directOption,
        children = children.values.map(MutableFolderTreeNode::freeze)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
    )
}

internal fun buildFolderHierarchy(options: List<GalleryFolderOption>): List<FolderTreeNode> {
    val roots = linkedMapOf<String, MutableFolderTreeNode>()
    options.forEach { option ->
        val normalizedPath = FolderSelectionPolicy.normalizeRelativePath(option.relativePath)
        if (normalizedPath == null) {
            roots["${option.volumeName}|#${option.bucketId}"] = MutableFolderTreeNode(
                volumeName = option.volumeName,
                relativePath = null,
                name = option.displayName,
            ).also { it.directOption = option }
            return@forEach
        }
        var parentChildren = roots
        val segments = normalizedPath.trimEnd('/').split('/')
        segments.forEachIndexed { index, segment ->
            val path = segments.take(index + 1).joinToString(separator = "/", postfix = "/")
            val key = "${option.volumeName}|$path"
            val node = parentChildren.getOrPut(key) {
                MutableFolderTreeNode(option.volumeName, path, segment)
            }
            if (index == segments.lastIndex) node.directOption = option
            parentChildren = node.children
        }
    }
    return roots.values.map(MutableFolderTreeNode::freeze)
        .sortedWith(
            compareBy<FolderTreeNode> { if (it.volumeName == "external_primary") 0 else 1 }
                .thenBy { it.volumeName }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
}

private fun FolderTreeNode.find(volumeName: String, relativePath: String): FolderTreeNode? {
    if (this.volumeName == volumeName && this.relativePath == relativePath) return this
    return children.firstNotNullOfOrNull { it.find(volumeName, relativePath) }
}

private fun FolderTreeNode.selectionState(settings: GallerySettings): ToggleableState {
    val defaultSelected = settings.library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded
    val selected = options.count { option ->
        FolderSelectionPolicy.isSelected(
            defaultSelected,
            settings.library.folderRules,
            option.volumeName,
            option.bucketId,
            option.relativePath,
        )
    }
    return when (selected) {
        0 -> ToggleableState.Off
        options.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

private fun GallerySettings.withFolderNodeSelected(node: FolderTreeNode, selected: Boolean): GallerySettings {
    val optionTargets = node.options.mapTo(hashSetOf()) {
        FolderSelectionTarget.Bucket(it.volumeName, it.bucketId)
    }
    val path = node.relativePath
    val retained = library.folderRules.filterKeys { target ->
        when (target) {
            is FolderSelectionTarget.Path -> path == null || target.volumeName != node.volumeName ||
                !target.relativePath.startsWith(path)
            is FolderSelectionTarget.Bucket -> target !in optionTargets
        }
    }
    val updated = retained.toMutableMap().apply {
        if (path != null) {
            put(FolderSelectionTarget.Path(node.volumeName, path), selected)
        } else {
            node.directOption?.let { put(FolderSelectionTarget.Bucket(it.volumeName, it.bucketId), selected) }
        }
    }
    return copy(library = library.copy(folderRules = updated))
}

private fun GallerySettings.withDirectFolderSelected(option: GalleryFolderOption, selected: Boolean): GallerySettings {
    val target = FolderSelectionTarget.Bucket(option.volumeName, option.bucketId)
    val withoutExact = library.folderRules - target
    val defaultSelected = library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded
    val inherited = FolderSelectionPolicy.isSelected(
        defaultSelected,
        withoutExact,
        option.volumeName,
        option.bucketId,
        option.relativePath,
    )
    val updated = if (inherited == selected) withoutExact else withoutExact + (target to selected)
    return copy(library = library.copy(folderRules = updated))
}

@Composable
private fun FolderSelectionPage(
    settings: GallerySettings,
    folderOptions: List<GalleryFolderOption>,
    currentVolume: String?,
    currentPath: String?,
    onOpenFolder: (String, String) -> Unit,
    onBack: () -> Unit,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    val roots = remember(folderOptions) { buildFolderHierarchy(folderOptions) }
    val currentNode = if (currentVolume != null && currentPath != null) {
        roots.firstNotNullOfOrNull { it.find(currentVolume, currentPath) }
    } else null
    val title = currentNode?.name ?: stringResource(R.string.settings_folders)

    Column(Modifier.fillMaxSize()) {
        SettingsHeader(title, onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = ReadableContentMaxWidth),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = GallerySpacing.Xl,
                    end = GallerySpacing.Xl,
                    top = GallerySpacing.Lg,
                    bottom = GallerySpacing.Lg + galleryBottomContentPadding() +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
            ) {
                if (currentNode == null) {
                    if (roots.isEmpty()) {
                        item { Text(stringResource(R.string.settings_folders_empty)) }
                    } else {
                        roots.groupBy(FolderTreeNode::volumeName).forEach { (volume, nodes) ->
                            item(key = "volume:$volume") {
                                Text(
                                    if (volume == "external_primary") {
                                        stringResource(R.string.settings_internal_storage)
                                    } else {
                                        stringResource(R.string.settings_storage_volume, volume)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm),
                                )
                            }
                            items(nodes, key = { "${it.volumeName}|${it.relativePath}|${it.name}" }) { node ->
                                FolderNodeRow(
                                    node = node,
                                    state = node.selectionState(settings),
                                    onToggle = { selected ->
                                        onSettingsChange { it.withFolderNodeSelected(node, selected) }
                                    },
                                    onOpen = node.relativePath?.let { path -> { onOpenFolder(node.volumeName, path) } },
                                )
                            }
                        }
                    }
                } else {
                    item(key = "path") {
                        Text(
                            currentNode.relativePath.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = "subtree") {
                        FolderSelectionRow(
                            label = stringResource(R.string.settings_folder_and_subfolders),
                            count = currentNode.itemCount,
                            state = currentNode.selectionState(settings),
                            onToggle = { selected ->
                                onSettingsChange { it.withFolderNodeSelected(currentNode, selected) }
                            },
                        )
                    }
                    currentNode.directOption?.let { direct ->
                        item(key = "direct") {
                            val selected = FolderSelectionPolicy.isSelected(
                                settings.library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded,
                                settings.library.folderRules,
                                direct.volumeName,
                                direct.bucketId,
                                direct.relativePath,
                            )
                            DirectFolderSelectionRow(direct.itemCount, selected) { enabled ->
                                onSettingsChange { it.withDirectFolderSelected(direct, enabled) }
                            }
                        }
                    }
                    if (currentNode.children.isNotEmpty()) {
                        item(key = "subfolders-heading") {
                            Text(
                                stringResource(R.string.settings_subfolders),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = GallerySpacing.Md),
                            )
                        }
                        items(currentNode.children, key = { "${it.volumeName}|${it.relativePath}" }) { node ->
                            FolderNodeRow(
                                node = node,
                                state = node.selectionState(settings),
                                onToggle = { selected ->
                                    onSettingsChange { it.withFolderNodeSelected(node, selected) }
                                },
                                onOpen = node.relativePath?.let { path -> { onOpenFolder(node.volumeName, path) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderNodeRow(
    node: FolderTreeNode,
    state: ToggleableState,
    onToggle: (Boolean) -> Unit,
    onOpen: (() -> Unit)?,
) {
    val toggleLabel = stringResource(R.string.settings_toggle_folder, node.name)
    ListItem(
        supportingContent = { Text(pluralStringResource(R.plurals.settings_media_items, node.itemCount.toInt(), node.itemCount)) },
        leadingContent = { Icon(GalleryIcons.Folder, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = state,
                    onClick = { onToggle(state != ToggleableState.On) },
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                )
                if (onOpen != null) {
                    IconButton(onClick = onOpen) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.settings_open_folder, node.name),
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable {
            if (onOpen != null) onOpen() else onToggle(state != ToggleableState.On)
        },
    ) { Text(node.name) }
}

@Composable
private fun FolderSelectionRow(
    label: String,
    count: Long,
    state: ToggleableState,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        supportingContent = { Text(pluralStringResource(R.plurals.settings_media_items, count.toInt(), count)) },
        trailingContent = {
            TriStateCheckbox(state = state, onClick = { onToggle(state != ToggleableState.On) })
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onToggle(state != ToggleableState.On) },
    ) { Text(label) }
}

@Composable
private fun DirectFolderSelectionRow(count: Long, selected: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.settings_folder_direct_items)
    ListItem(
        supportingContent = { Text(pluralStringResource(R.plurals.settings_media_items, count.toInt(), count)) },
        trailingContent = {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.semantics { contentDescription = label },
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onToggle(!selected) },
    ) { Text(label) }
}

@Composable
private fun LibrarySection(
    settings: GallerySettings,
    folderOptions: List<GalleryFolderOption>,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
    onOpenFolders: () -> Unit,
) {
    var sortDialogVisible by rememberSaveable { mutableStateOf(false) }
    var filterDialogVisible by rememberSaveable { mutableStateOf(false) }
    var groupDialogVisible by rememberSaveable { mutableStateOf(false) }
    var folderModeDialogVisible by rememberSaveable { mutableStateOf(false) }

    fun LibrarySort.labelRes(): Int = when (this) {
        LibrarySort.DateTaken -> R.string.settings_sort_date_taken
        LibrarySort.DateModified -> R.string.settings_sort_date_modified
        LibrarySort.Name -> R.string.settings_sort_name
        LibrarySort.Size -> R.string.settings_sort_size
    }

    fun LibraryFilter.labelRes(): Int = when (this) {
        LibraryFilter.All -> R.string.settings_filter_all
        LibraryFilter.Images -> R.string.settings_filter_images
        LibraryFilter.Videos -> R.string.settings_filter_videos
        LibraryFilter.Animated -> R.string.settings_filter_animated
        LibraryFilter.Raw -> R.string.settings_filter_raw
    }

    fun LibraryGrouping.labelRes(): Int = when (this) {
        LibraryGrouping.Day -> R.string.settings_group_day
        LibraryGrouping.Month -> R.string.settings_group_month
        LibraryGrouping.Year -> R.string.settings_group_year
        LibraryGrouping.None -> R.string.settings_group_none
    }

    SettingsCard(stringResource(R.string.settings_card_sorting)) {
        SettingsValueRow(
            stringResource(R.string.settings_library_sort),
            stringResource(settings.library.sort.labelRes()),
            modifier = Modifier.testTag("library_sort_row"),
            onClick = { sortDialogVisible = true },
        )
        SettingsSwitchRow(stringResource(R.string.settings_sort_ascending), settings.library.ascending) {
            onSettingsChange { current -> current.copy(library = current.library.copy(ascending = it)) }
        }
        SettingsValueRow(
            stringResource(R.string.settings_library_filter),
            stringResource(settings.library.filter.labelRes()),
            modifier = Modifier.testTag("library_filter_row"),
            onClick = { filterDialogVisible = true },
        )
        SettingsValueRow(
            stringResource(R.string.settings_library_group),
            stringResource(settings.library.grouping.labelRes()),
            modifier = Modifier.testTag("library_group_row"),
            onClick = { groupDialogVisible = true },
        )
    }
    SettingsCard(stringResource(R.string.settings_folders)) {
        SettingsValueRow(
            stringResource(R.string.settings_folder_mode),
            stringResource(
                if (settings.library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded) {
                    R.string.settings_folder_exclude_mode
                } else R.string.settings_folder_include_mode,
            ),
            modifier = Modifier.testTag("folder_mode_row"),
            onClick = { folderModeDialogVisible = true },
        )
        val defaultSelected = settings.library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded
        val selectedFolders = folderOptions.count { option ->
            FolderSelectionPolicy.isSelected(
                defaultSelected = defaultSelected,
                rules = settings.library.folderRules,
                volumeName = option.volumeName,
                bucketId = option.bucketId,
                relativePath = option.relativePath,
            )
        }
        val folderSummary = if (folderOptions.isEmpty()) {
            stringResource(R.string.settings_folders_empty)
        } else {
            stringResource(R.string.settings_folders_enabled_summary, selectedFolders, folderOptions.size)
        }
        SettingsValueRow(
            label = stringResource(R.string.settings_folders),
            value = folderSummary,
            modifier = Modifier.testTag("folder_browser_row"),
            onClick = onOpenFolders,
        )
    }
    if (sortDialogVisible) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_library_sort),
            options = LibrarySort.entries.map { it.labelRes() },
            selectedOption = LibrarySort.entries.indexOf(settings.library.sort),
            optionTestTags = LibrarySort.entries.map { "library_sort_" + it.name },
            onDismiss = { sortDialogVisible = false },
        ) { index ->
            onSettingsChange { current ->
                current.copy(library = current.library.copy(sort = LibrarySort.entries[index]))
            }
            sortDialogVisible = false
        }
    }
    if (filterDialogVisible) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_library_filter),
            options = LibraryFilter.entries.map { it.labelRes() },
            selectedOption = LibraryFilter.entries.indexOf(settings.library.filter),
            optionTestTags = LibraryFilter.entries.map { "library_filter_" + it.name },
            onDismiss = { filterDialogVisible = false },
        ) { index ->
            onSettingsChange { current ->
                current.copy(library = current.library.copy(filter = LibraryFilter.entries[index]))
            }
            filterDialogVisible = false
        }
    }
    if (groupDialogVisible) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_library_group),
            options = LibraryGrouping.entries.map { it.labelRes() },
            selectedOption = LibraryGrouping.entries.indexOf(settings.library.grouping),
            optionTestTags = LibraryGrouping.entries.map { "library_group_" + it.name },
            onDismiss = { groupDialogVisible = false },
        ) { index ->
            onSettingsChange { current ->
                current.copy(library = current.library.copy(grouping = LibraryGrouping.entries[index]))
            }
            groupDialogVisible = false
        }
    }
    if (folderModeDialogVisible) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_folder_mode),
            options = listOf(R.string.settings_folder_exclude_mode, R.string.settings_folder_include_mode),
            selectedOption = if (settings.library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded) 0 else 1,
            optionTestTags = listOf("folder_mode_exclude", "folder_mode_include"),
            onDismiss = { folderModeDialogVisible = false },
        ) { index ->
            onSettingsChange { current ->
                current.copy(
                    library = current.library.copy(
                        folderSelectionMode = if (index == 0) {
                            FolderSelectionMode.AllExceptExcluded
                        } else FolderSelectionMode.OnlyIncluded,
                    ),
                )
            }
            folderModeDialogVisible = false
        }
    }
}

@Composable
private fun SettingsSingleChoiceDialog(
    title: String,
    options: List<Int>,
    selectedOption: Int,
    optionTestTags: List<String>,
    onDismiss: () -> Unit,
    onOptionSelected: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, labelRes ->
                    val label = stringResource(labelRes)
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onOptionSelected(index) }
                            .testTag(optionTestTags.getOrElse(index) { "option_" + index }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedOption, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}


@Composable
private fun PlaybackSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    var scrubbingModeDialogVisible by rememberSaveable { mutableStateOf(false) }
    SettingsCard {
        SettingsValueRow(
            label = stringResource(R.string.settings_video_scrubbing_mode),
            value = stringResource(
                when (settings.playback.videoScrubbingMode) {
                    VideoScrubbingMode.LegacySeekBar -> R.string.settings_video_scrubbing_legacy
                    VideoScrubbingMode.Filmstrip -> R.string.settings_video_scrubbing_filmstrip
                },
            ),
            modifier = Modifier.testTag("video_scrubbing_mode_row"),
            onClick = { scrubbingModeDialogVisible = true },
        )
        SettingsSwitchRow(stringResource(R.string.settings_autoplay_videos), settings.playback.autoplayVideos) {
            onSettingsChange { current -> current.copy(playback = current.playback.copy(autoplayVideos = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_start_muted), settings.playback.startVideosMuted) {
            onSettingsChange { current -> current.copy(playback = current.playback.copy(startVideosMuted = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_loop_videos), settings.playback.loopVideos) {
            onSettingsChange { current -> current.copy(playback = current.playback.copy(loopVideos = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_remember_video), settings.playback.rememberVideoPosition) {
            onSettingsChange { current -> current.copy(playback = current.playback.copy(rememberVideoPosition = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_max_brightness), settings.playback.maximumBrightness) {
            onSettingsChange { current -> current.copy(playback = current.playback.copy(maximumBrightness = it)) }
        }
    }
    if (scrubbingModeDialogVisible) AlertDialog(
        modifier = Modifier.testTag("video_scrubbing_mode_dialog"),
        onDismissRequest = { scrubbingModeDialogVisible = false },
        title = { Text(stringResource(R.string.settings_video_scrubbing_mode)) },
        text = {
            Column {
                VideoScrubbingMode.entries.forEach { mode ->
                    val label = stringResource(
                        when (mode) {
                            VideoScrubbingMode.LegacySeekBar -> R.string.settings_video_scrubbing_legacy
                            VideoScrubbingMode.Filmstrip -> R.string.settings_video_scrubbing_filmstrip
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                onSettingsChange { current ->
                                    current.copy(playback = current.playback.copy(videoScrubbingMode = mode))
                                }
                                scrubbingModeDialogVisible = false
                            }
                            .testTag("video_scrubbing_mode_${mode.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.playback.videoScrubbingMode == mode,
                            onClick = null,
                        )
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { scrubbingModeDialogVisible = false }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
internal fun PlaybackSettingsTestContent(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    Column { PlaybackSection(settings, onSettingsChange) }
}

@Composable
private fun GesturesSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_card_photo_gestures)) {
        SettingsSwitchRow(stringResource(R.string.settings_double_tap_zoom), settings.gestures.doubleTapZoom) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(doubleTapZoom = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_pinch_zoom), settings.gestures.pinchZoom) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(pinchZoom = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_swipe_down), settings.gestures.swipeDownToClose) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(swipeDownToClose = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_swipe_up_details), settings.gestures.swipeUpForDetails) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(swipeUpForDetails = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_photo_brightness), settings.gestures.photoBrightness) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(photoBrightness = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_rotate_photos), settings.gestures.rotatePhotos) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(rotatePhotos = it)) }
        }
        SettingsValueRow(stringResource(R.string.settings_photo_zoom_limit), stringResource(R.string.settings_zoom_value, settings.gestures.photoMaxZoom.toInt())) {
            val next = when (settings.gestures.photoMaxZoom.toInt()) { 2 -> 4f; 4 -> 8f; else -> 2f }
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(photoMaxZoom = next)) }
        }
    }
    SettingsCard(stringResource(R.string.settings_card_video_gestures)) {
        SettingsSwitchRow(stringResource(R.string.settings_video_brightness), settings.gestures.videoBrightness) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoBrightness = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_video_volume), settings.gestures.videoVolume) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoVolume = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_video_seek), settings.gestures.videoSeek) {
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoSeek = it)) }
        }
        SettingsValueRow(stringResource(R.string.settings_video_zoom_limit), stringResource(R.string.settings_zoom_value, settings.gestures.videoMaxZoom.toInt())) {
            val next = when (settings.gestures.videoMaxZoom.toInt()) { 2 -> 4f; 4 -> 8f; else -> 2f }
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoMaxZoom = next)) }
        }
        SettingsValueRow(stringResource(R.string.settings_skip_seconds), pluralStringResource(R.plurals.settings_seconds, settings.gestures.videoSkipSeconds.toInt(), settings.gestures.videoSkipSeconds)) {
            val next = when (settings.gestures.videoSkipSeconds) { 5 -> 10; 10 -> 15; 15 -> 30; else -> 5 }
            onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoSkipSeconds = next)) }
        }
    }
}

@Composable
private fun ThumbnailsSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    SettingsCard {
        SettingsSwitchRow(stringResource(R.string.settings_crop_thumbnails), settings.thumbnails.cropToFill) {
            onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(cropToFill = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_animate_media), settings.thumbnails.animateMedia) {
            onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(animateMedia = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_show_duration), settings.thumbnails.showVideoDuration) {
            onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(showVideoDuration = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_show_file_type), settings.thumbnails.showFileType) {
            onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(showFileType = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_mark_favorites), settings.thumbnails.markFavorites) {
            onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(markFavorites = it)) }
        }
        val columns = settings.thumbnails.gridColumns
        SettingsValueRow(
            stringResource(R.string.settings_grid_columns),
            if (columns == AutoGridColumns) stringResource(R.string.settings_grid_columns_auto) else columns.toString(),
        ) {
            // Automatic -> 2..8 -> Automatic.
            val next = when {
                columns == AutoGridColumns -> 2
                columns >= 8 -> AutoGridColumns
                else -> columns + 1
            }
            onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(gridColumns = next)) }
        }
    }
}

@Composable
private fun OperationsSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    SettingsCard {
        SettingsSwitchRow(stringResource(R.string.settings_share_sanitized), settings.operations.shareWithoutLocationByDefault) {
            onSettingsChange { current -> current.copy(operations = current.operations.copy(shareWithoutLocationByDefault = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_keep_modified), settings.operations.keepLastModifiedWhenPossible) {
            onSettingsChange { current -> current.copy(operations = current.operations.copy(keepLastModifiedWhenPossible = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_skip_confirmation), settings.operations.skipAppDeleteConfirmation) {
            onSettingsChange { current -> current.copy(operations = current.operations.copy(skipAppDeleteConfirmation = it)) }
        }
    }
}

@Composable
private fun SecuritySection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    val context = LocalContext.current
    var lockUnavailable by remember { mutableStateOf(false) }
    // Both locks authenticate with biometrics or the device credential. Without either, enabling a
    // lock would only lock the user out (or silently block deletes), so refuse and explain.
    fun canAuthenticate() = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    ) == BiometricManager.BIOMETRIC_SUCCESS
    if (lockUnavailable) {
        AlertDialog(
            onDismissRequest = { lockUnavailable = false },
            title = { Text(stringResource(R.string.settings_lock_unavailable_title)) },
            text = { Text(stringResource(R.string.settings_lock_unavailable_body)) },
            confirmButton = { TextButton(onClick = { lockUnavailable = false }) { Text(stringResource(R.string.settings_lock_unavailable_ok)) } },
        )
    }
    SettingsCard {
        SettingsSwitchRow(stringResource(R.string.settings_app_lock), settings.security.appLockEnabled) {
            if (it && !canAuthenticate()) lockUnavailable = true
            else onSettingsChange { current -> current.copy(security = current.security.copy(appLockEnabled = it)) }
        }
        SettingsSwitchRow(stringResource(R.string.settings_destructive_lock), settings.security.destructiveActionLockEnabled) {
            if (it && !canAuthenticate()) lockUnavailable = true
            else onSettingsChange { current -> current.copy(security = current.security.copy(destructiveActionLockEnabled = it)) }
        }
        SettingsValueRow(stringResource(R.string.settings_relock_timeout), stringResource(R.string.settings_minutes, settings.security.relockTimeoutMinutes)) {
            val next = when (settings.security.relockTimeoutMinutes) { 0 -> 1; 1 -> 5; 5 -> 15; else -> 0 }
            onSettingsChange { current -> current.copy(security = current.security.copy(relockTimeoutMinutes = next)) }
        }
    }
}

@Composable
private fun BackupSection(
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onResetSettings: () -> Unit,
    onLocalBackup: (() -> Unit)?,
    onRemoteBackup: (() -> Unit)?,
    onOwnSync: (() -> Unit)?,
    onOfflinePlaces: (() -> Unit)?,
    onLocalSharing: (() -> Unit)?,
) {
    val cards = LocalSettingsCards.current
    SettingsCard {
        if (onLocalSharing != null) SettingsActionRow(GalleryIcons.Share, stringResource(R.string.local_sharing_entry), Modifier.testTag("settings-local-sharing"), onLocalSharing)
        if (onOwnSync != null) SettingsActionRow(GalleryIcons.Link, stringResource(R.string.own_sync_entry), Modifier.testTag("settings-own-sync"), onOwnSync)
        if (onOfflinePlaces != null) SettingsActionRow(GalleryIcons.Place, stringResource(R.string.offline_places_entry), Modifier.testTag("settings-offline-places"), onOfflinePlaces)
        if (onRemoteBackup != null) SettingsActionRow(GalleryIcons.Archive, stringResource(R.string.remote_backup_entry), Modifier.testTag("settings-remote-backup"), onRemoteBackup)
        if (onLocalBackup != null) SettingsActionRow(GalleryIcons.Folder, stringResource(R.string.local_backup_title), Modifier.testTag("settings-local-backup"), onLocalBackup)
    }
    if (cards) {
        SettingsCard {
            SettingsActionRow(GalleryIcons.Share, stringResource(R.string.settings_export), onClick = onExportSettings)
            SettingsActionRow(GalleryIcons.Download, stringResource(R.string.settings_import), onClick = onImportSettings)
        }
        SettingsCard {
            SettingsActionRow(GalleryIcons.History, stringResource(R.string.settings_reset), onClick = onResetSettings)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            OutlinedButton(onClick = onExportSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_export)) }
            OutlinedButton(onClick = onImportSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_import)) }
        }
        TextButton(onClick = onResetSettings) { Text(stringResource(R.string.settings_reset)) }
    }
}

/**
 * Navigation/action row for the two-pane detail. On phones the original outlined buttons are kept,
 * so the single-pane layout does not change.
 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (!LocalSettingsCards.current) {
        OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) { Text(label) }
        return
    }
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).heightIn(min = settingsRowMinHeight()),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) { Text(label) }
}

@Composable
private fun AiAnalysisSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
    state: FaceAnalysisUiState,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyzeAll: () -> Unit,
    onDelete: () -> Unit,
    onDeleteRequested: () -> Unit,
    petCollectionsEnabled: Boolean,
    petAnalysisState: FaceAnalysisUiState,
    onPetCollectionsEnabledChange: (Boolean) -> Unit,
    onHideDogResults: () -> Unit,
    onHideCatResults: () -> Unit,
    onRestorePetResults: () -> Unit,
    peopleAnalysisEnabled: Boolean,
    contentAnalysisEnabled: Boolean,
    localAnalysisEnabled: Boolean,
    onAllAnalysisEnabledChange: (Boolean) -> Unit,
    onPeopleAnalysisEnabledChange: (Boolean) -> Unit,
    onContentAnalysisEnabledChange: (Boolean) -> Unit,
    cleanupAnalysisEnabled: Boolean,
    onCleanupAnalysisEnabledChange: (Boolean) -> Unit,
    semanticModels: SemanticModelSettingsUiState,
    onSemanticEnabledChange: (Boolean) -> Unit,
    onSemanticDownload: (String) -> Unit,
    onSemanticCancelDownload: (String) -> Unit,
    onSemanticActivate: (String, Boolean) -> Unit,
    onSemanticDelete: (String) -> Unit,
    onSemanticDeleteAll: () -> Unit,
    onSemanticAutomaticSelection: () -> Unit,
    showHeader: Boolean,
) {
    val petCollectionsLabel = stringResource(R.string.pet_collections_enable)
    // Children stay tappable while the master is off (tapping one turns the master back on),
    // but read as disabled so the master's state is obvious.
    val childModifier = Modifier.alpha(if (localAnalysisEnabled) 1f else DisabledChildAlpha)
    if (showHeader) Text(
        stringResource(R.string.face_analysis_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.local_analysis_privacy), style = MaterialTheme.typography.bodyLarge)
    SettingsValueRow(
        stringResource(R.string.local_analysis_minimum_battery),
        stringResource(
            R.string.local_analysis_battery_percent,
            settings.analysis.fullAnalysisMinimumBatteryPercent,
        ),
    ) {
        val next = when (settings.analysis.fullAnalysisMinimumBatteryPercent) {
            20 -> 30
            30 -> 40
            40 -> 50
            else -> 20
        }
        onSettingsChange { current ->
            current.copy(
                analysis = current.analysis.copy(fullAnalysisMinimumBatteryPercent = next),
            )
        }
    }
    Text(
        stringResource(R.string.local_analysis_minimum_battery_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsSwitchRow(
        stringResource(R.string.model_downloads_mobile_data),
        settings.analysis.modelDownloadsOnMobileData,
        modifier = Modifier.testTag("model_downloads_mobile_data_switch"),
    ) { allowed ->
        onSettingsChange { current ->
            current.copy(analysis = current.analysis.copy(modelDownloadsOnMobileData = allowed))
        }
    }
    Text(
        stringResource(R.string.model_downloads_mobile_data_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md)) {
            SettingsSwitchRow(
                stringResource(R.string.local_analysis_master),
                localAnalysisEnabled,
                modifier = Modifier.testTag("local_analysis_master_switch"),
                onCheckedChange = onAllAnalysisEnabledChange,
            )
            Text(
                stringResource(R.string.local_analysis_master_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.local_analysis_features), style = MaterialTheme.typography.titleLarge)
            SettingsSwitchRow(
                stringResource(R.string.local_analysis_people),
                peopleAnalysisEnabled,
                modifier = childModifier.testTag("people_analysis_switch"),
                onCheckedChange = onPeopleAnalysisEnabledChange,
            )
            Text(stringResource(R.string.local_analysis_people_summary), style = MaterialTheme.typography.bodySmall, modifier = childModifier)
            SettingsSwitchRow(
                stringResource(R.string.local_analysis_content),
                contentAnalysisEnabled,
                modifier = childModifier.testTag("content_analysis_switch"),
                onCheckedChange = onContentAnalysisEnabledChange,
            )
            Text(stringResource(R.string.local_analysis_content_summary), style = MaterialTheme.typography.bodySmall, modifier = childModifier)
            SettingsSwitchRow(
                stringResource(R.string.local_analysis_cleanup),
                cleanupAnalysisEnabled,
                modifier = childModifier.testTag("cleanup_analysis_switch"),
                onCheckedChange = onCleanupAnalysisEnabledChange,
            )
            Text(stringResource(R.string.local_analysis_cleanup_summary), style = MaterialTheme.typography.bodySmall, modifier = childModifier)
            SettingsSwitchRow(
                petCollectionsLabel,
                petCollectionsEnabled,
                modifier = childModifier.testTag("pet_collections_switch"),
                onCheckedChange = onPetCollectionsEnabledChange,
            )
            Text(stringResource(R.string.pet_collections_no_identity), style = MaterialTheme.typography.bodySmall, modifier = childModifier)
        }
    }
    SemanticModelsSection(
        state = semanticModels,
        switchModifier = childModifier,
        onEnabledChange = onSemanticEnabledChange,
        onDownload = onSemanticDownload,
        onCancelDownload = onSemanticCancelDownload,
        onActivate = onSemanticActivate,
        onDelete = onSemanticDelete,
        onDeleteAll = onSemanticDeleteAll,
        onAutomaticSelection = onSemanticAutomaticSelection,
    )

    Text(stringResource(R.string.face_analysis_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.face_analysis_no_identity), color = MaterialTheme.colorScheme.primary)
    if (!peopleAnalysisEnabled) {
        GalleryExpressiveButton(onClick = onEnable) { Text(stringResource(R.string.face_analysis_enable)) }
    } else {
        GalleryProgressSlot(state.status == AnalysisStatus.Running)
        Text(pluralStringResource(R.plurals.face_analysis_progress, state.completedItems.toInt(), state.completedItems))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryExpressiveButton(onClick = if (state.paused) onResume else onPause) {
                Text(stringResource(if (state.paused) R.string.face_analysis_resume else R.string.face_analysis_pause))
            }
            OutlinedButton(onClick = onAnalyzeAll) { Text(stringResource(R.string.face_analysis_all)) }
        }
        Text(stringResource(R.string.face_analysis_all_requirements), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDeleteRequested) {
            Text(stringResource(R.string.face_analysis_delete), color = MaterialTheme.colorScheme.error)
        }
    }
    Text(stringResource(R.string.pet_collections_title), style = MaterialTheme.typography.titleLarge)
    if (petCollectionsEnabled) {
        if (petAnalysisState.status == AnalysisStatus.Running ||
            petAnalysisState.status == AnalysisStatus.Ready
        ) {
            GalleryIndeterminateProgressIndicator(
                Modifier.fillMaxWidth().testTag("pet_analysis_progress_indicator"),
            )
        }
        Text(
            pluralStringResource(
                when (petAnalysisState.status) {
                    AnalysisStatus.Complete -> R.plurals.pet_analysis_complete
                    AnalysisStatus.Paused -> R.plurals.pet_analysis_paused
                    AnalysisStatus.Running -> R.plurals.pet_analysis_running
                    AnalysisStatus.Ready, null -> R.plurals.pet_analysis_preparing
                },
                petAnalysisState.completedItems.toInt(),
                petAnalysisState.completedItems,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("pet_analysis_status"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onHideDogResults) { Text(stringResource(R.string.pet_hide_dog)) }
            TextButton(onClick = onHideCatResults) { Text(stringResource(R.string.pet_hide_cat)) }
        }
        TextButton(onClick = onRestorePetResults) { Text(stringResource(R.string.pet_restore)) }
    }
}

private const val DisabledChildAlpha = 0.38f

@Composable
private fun SemanticModelsSection(
    state: SemanticModelSettingsUiState,
    switchModifier: Modifier = Modifier,
    onEnabledChange: (Boolean) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onActivate: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onAutomaticSelection: () -> Unit,
) {
    var activateModel by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteModel by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.semantic_settings_title), style = MaterialTheme.typography.titleLarge)
    SettingsSwitchRow(
        stringResource(R.string.semantic_settings_enabled),
        state.enabled,
        modifier = switchModifier.testTag("semantic_search_switch"),
        onCheckedChange = onEnabledChange,
    )
    Text(stringResource(R.string.semantic_settings_privacy), style = MaterialTheme.typography.bodySmall)
    val active = state.models.firstOrNull { it.active }
    val building = state.models.firstOrNull { it.id == state.buildingModelId }
    Text(
        when {
            active != null -> stringResource(R.string.semantic_settings_active, active.name, active.version)
            building != null -> stringResource(R.string.semantic_settings_preparing, building.name, building.version)
            else -> stringResource(R.string.semantic_settings_no_active)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    state.indexError?.let {
        Text(stringResource(R.string.semantic_index_error), color = MaterialTheme.colorScheme.error)
    }
    if (!state.automaticSelection) {
        OutlinedButton(onClick = onAutomaticSelection) { Text(stringResource(R.string.semantic_settings_automatic)) }
    }
    state.models.forEach { model ->
        Surface(
            color = if (model.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (model.active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().testTag("semantic_model_${model.id}"),
        ) {
            Column(Modifier.padding(GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            model.active -> stringResource(R.string.semantic_model_active)
                            state.buildingModelId == model.id -> stringResource(R.string.semantic_model_building)
                            model.compatibility == SemanticModelCompatibilityUi.Recommended -> stringResource(R.string.semantic_model_recommended)
                            model.compatibility == SemanticModelCompatibilityUi.Unsupported -> stringResource(R.string.semantic_model_unsupported)
                            else -> stringResource(R.string.semantic_model_supported)
                        },
                        color = androidx.compose.material3.LocalContentColor.current,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    stringResource(
                        R.string.semantic_model_details,
                        model.version,
                        model.sizeBytes / (1024 * 1024),
                        model.quality,
                        model.languages,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                model.error?.let { Text(stringResource(R.string.semantic_model_error, it), style = MaterialTheme.typography.bodySmall) }
                if (state.buildingModelId == model.id) {
                    Text(
                        stringResource(
                            if (active == null) R.string.semantic_model_building_first_description
                            else R.string.semantic_model_building_description,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when {
                    model.downloading -> {
                        Text(
                            model.waiting?.let { modelDownloadWaitText(it) }
                                ?: stringResource(R.string.semantic_model_downloading, model.downloadedBytes / (1024 * 1024)),
                        )
                        TextButton(colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.LocalContentColor.current), modifier = Modifier.testTag("semantic_model_cancel_${model.id}"), onClick = { onCancelDownload(model.id) }) { Text(stringResource(R.string.semantic_model_cancel)) }
                    }
                    !model.installed -> GalleryExpressiveButton(modifier = Modifier.testTag("semantic_model_download_${model.id}"), onClick = { onDownload(model.id) }) {
                        Text(stringResource(R.string.semantic_model_download))
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                        if (
                            !model.active &&
                            state.buildingModelId != model.id &&
                            model.compatibility != SemanticModelCompatibilityUi.Unsupported
                        ) {
                            OutlinedButton(colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.material3.LocalContentColor.current), modifier = Modifier.testTag("semantic_model_use_${model.id}"), onClick = {
                                if (model.compatibility == SemanticModelCompatibilityUi.Recommended) onActivate(model.id, false)
                                else activateModel = model.id
                            }) { Text(stringResource(R.string.semantic_model_use)) }
                        }
                        TextButton(colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.LocalContentColor.current), modifier = Modifier.testTag("semantic_model_delete_${model.id}"), onClick = { deleteModel = model.id }) {
                            Text(stringResource(R.string.semantic_model_delete))
                        }
                    }
                }
            }
        }
    }
    if (state.models.any { it.installed }) {
        TextButton(onClick = { confirmDeleteAll = true }) {
            Text(stringResource(R.string.semantic_models_delete_all), color = MaterialTheme.colorScheme.error)
        }
    }
    activateModel?.let { id ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { activateModel = null },
            title = { Text(stringResource(R.string.semantic_override_title)) },
            text = { Text(stringResource(R.string.semantic_override_body)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("semantic_model_override_confirm"), onClick = { activateModel = null; onActivate(id, true) }) {
                    Text(stringResource(R.string.semantic_override_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { activateModel = null }) { Text(stringResource(R.string.face_analysis_cancel)) } },
        )
    }
    deleteModel?.let { id ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { deleteModel = null },
            title = { Text(stringResource(R.string.semantic_delete_title)) },
            text = { Text(stringResource(R.string.semantic_delete_body)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("semantic_model_delete_confirm"), onClick = { deleteModel = null; onDelete(id) }) { Text(stringResource(R.string.semantic_model_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteModel = null }) { Text(stringResource(R.string.face_analysis_cancel)) } },
        )
    }
    if (confirmDeleteAll) AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text(stringResource(R.string.semantic_delete_all_title)) },
        text = { Text(stringResource(R.string.semantic_delete_all_body)) },
        confirmButton = {
            TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text(stringResource(R.string.semantic_models_delete_all)) }
        },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.face_analysis_cancel)) } },
    )
}

@Composable
internal fun SemanticModelsTestContent(state: SemanticModelSettingsUiState) {
    Column {
        SemanticModelsSection(
            state = state,
            onEnabledChange = {},
            onDownload = {},
            onCancelDownload = {},
            onActivate = { _, _ -> },
            onDelete = {},
            onDeleteAll = {},
            onAutomaticSelection = {},
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(
            // The switch already communicates the state. Keeping the row on a
            // neutral surface avoids mixing unrelated dynamic primary and
            // secondary container palettes on the same control.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).heightIn(min = settingsRowMinHeight()),
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.semantics { contentDescription = label },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                ),
            )
        },
    ) { Text(label) }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cards = LocalSettingsCards.current
    ListItem(
        onClick = onClick,
        modifier = modifier.clip(MaterialTheme.shapes.large).heightIn(min = settingsRowMinHeight()),
        // Inside a card the rows share the tone of the switch rows.
        colors = if (cards) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        } else ListItemDefaults.colors(),
        trailingContent = if (cards) {
            { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
    ) {
        // The value sits beside the label when both fit and drops below it otherwise, so
        // neither a long translation nor a narrow pane squeezes the label.
        GalleryLabelValueLayout(
            label = { Text(label) },
            value = {
                if (cards) {
                    GalleryValueChip(value)
                } else {
                    Text(value, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
        )
    }
}
