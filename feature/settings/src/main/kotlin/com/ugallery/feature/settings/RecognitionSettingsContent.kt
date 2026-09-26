package com.ugallery.feature.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.testTagsAsResourceId

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import com.ugallery.core.preferences.GallerySettings
import com.ugallery.core.preferences.FolderSelectionMode
import com.ugallery.core.preferences.FolderSelectionPolicy
import com.ugallery.core.preferences.FolderSelectionTarget
import com.ugallery.core.preferences.LibraryFilter
import com.ugallery.core.preferences.LibraryGrouping
import com.ugallery.core.preferences.LibrarySort
import com.ugallery.core.preferences.VideoScrubbingMode
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryIndeterminateProgressIndicator

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
    val error: String? = null,
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
    onSemanticDownload: (String, Boolean) -> Unit = { _, _ -> },
    onSemanticCancelDownload: (String) -> Unit = {},
    onSemanticActivate: (String, Boolean) -> Unit = { _, _ -> },
    onSemanticDelete: (String) -> Unit = {},
    onSemanticDeleteAll: () -> Unit = {},
    onSemanticAutomaticSelection: () -> Unit = {},
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
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

    BackHandler {
        when (page) {
            SettingsPage.Root -> onBack()
            SettingsPage.LibraryFolders -> leaveFolderLevel()
            SettingsPage.Library -> page = SettingsPage.Root
            else -> page = SettingsPage.Root
        }
    }

    Box(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxSize().widthIn(max = 720.dp),
        ) {
            when (page) {
                SettingsPage.Root -> SettingsRootPage(onBack) {
                    SettingsCategoryList(
                        settings = settings,
                        aiConsentGranted = peopleAnalysisEnabled || contentAnalysisEnabled || petCollectionsEnabled,
                        onOpen = { page = it },
                        onOpenAbout = onOpenAbout,
                    )
                }
                SettingsPage.Library -> SettingsSubPage(title = stringResource(R.string.settings_library), onBack = { page = SettingsPage.Root }) {
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
                SettingsPage.Playback -> SettingsSubPage(title = stringResource(R.string.settings_playback), onBack = { page = SettingsPage.Root }) {
                    PlaybackSection(settings, onSettingsChange)
                }
                SettingsPage.Gestures -> SettingsSubPage(title = stringResource(R.string.settings_gestures), onBack = { page = SettingsPage.Root }) {
                    GesturesSection(settings, onSettingsChange)
                }
                SettingsPage.Thumbnails -> SettingsSubPage(title = stringResource(R.string.settings_thumbnails), onBack = { page = SettingsPage.Root }) {
                    ThumbnailsSection(settings, onSettingsChange)
                }
                SettingsPage.Operations -> SettingsSubPage(title = stringResource(R.string.settings_operations), onBack = { page = SettingsPage.Root }) {
                    OperationsSection(settings, onSettingsChange)
                }
                SettingsPage.Security -> SettingsSubPage(title = stringResource(R.string.settings_security), onBack = { page = SettingsPage.Root }) {
                    SecuritySection(settings, onSettingsChange)
                }
                SettingsPage.Backup -> SettingsSubPage(title = stringResource(R.string.settings_backup), onBack = { page = SettingsPage.Root }) {
                    BackupSection(onExportSettings, onImportSettings, onResetSettings, onLocalBackup, onRemoteBackup, onOwnSync, onOfflinePlaces, onLocalSharing)
                }
                SettingsPage.AiAnalysis -> SettingsSubPage(title = stringResource(R.string.settings_page_ai), onBack = { page = SettingsPage.Root }) {
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
private fun SettingsRootPage(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
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
    val columnsSummary = stringResource(R.string.settings_summary_columns, settings.thumbnails.gridColumns)
    val enabledPattern = stringResource(R.string.settings_summary_enabled)
    val onLabel = stringResource(R.string.settings_summary_on)
    val offLabel = stringResource(R.string.settings_summary_off)

    Column(
        Modifier.fillMaxSize().widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(GallerySpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xl),
    ) {
        SettingsCategoryGroup(stringResource(R.string.settings_group_viewing)) {
            SettingsCategoryRow(GalleryIcons.Collections, stringResource(R.string.settings_library), "$sortLabel · $filterLabel", 0, 4) { onOpen(SettingsPage.Library) }
            SettingsCategoryRow(
                GalleryIcons.Play,
                stringResource(R.string.settings_playback),
                enabledPattern.format(playbackCount, 5),
                1,
                4,
                Modifier.testTag("settings_playback_row"),
            ) { onOpen(SettingsPage.Playback) }
            SettingsCategoryRow(GalleryIcons.Tune, stringResource(R.string.settings_gestures), enabledPattern.format(gestureCount, 8), 2, 4) { onOpen(SettingsPage.Gestures) }
            SettingsCategoryRow(GalleryIcons.Image, stringResource(R.string.settings_thumbnails), "$columnsSummary · " + enabledPattern.format(thumbnailCount, 5), 3, 4) { onOpen(SettingsPage.Thumbnails) }
        }
        SettingsCategoryGroup(stringResource(R.string.settings_group_management)) {
            SettingsCategoryRow(GalleryIcons.Settings, stringResource(R.string.settings_operations), enabledPattern.format(operationsCount, 3), 0, 3) { onOpen(SettingsPage.Operations) }
            SettingsCategoryRow(GalleryIcons.Lock, stringResource(R.string.settings_security), if (securityOn) onLabel else offLabel, 1, 3) { onOpen(SettingsPage.Security) }
            SettingsCategoryRow(GalleryIcons.Download, stringResource(R.string.settings_backup), null, 2, 3) { onOpen(SettingsPage.Backup) }
        }
        AppLanguageGroup()
        SettingsCategoryGroup(stringResource(R.string.settings_group_intelligence)) {
            SettingsCategoryRow(GalleryIcons.Analyze, stringResource(R.string.settings_page_ai), if (aiConsentGranted) onLabel else offLabel, 0, 1) { onOpen(SettingsPage.AiAnalysis) }
        }
        SettingsCategoryGroup(stringResource(R.string.settings_group_about)) {
            SettingsCategoryRow(
                GalleryIcons.Info,
                stringResource(R.string.settings_about),
                stringResource(R.string.settings_about_summary),
                0,
                1,
                Modifier.testTag("settings_about_row"),
                onClick = onOpenAbout,
            )
        }
    }
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

/** App language picker. Android 13+ applies it per app and recreates the activity. */
@Composable
private fun AppLanguageGroup() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val current = remember(manager) {
        val language = manager.applicationLocales.get(0)?.language.orEmpty()
        AppLanguageTags.indexOf(language).coerceAtLeast(0)
    }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    SettingsCategoryGroup(stringResource(R.string.settings_language)) {
        SettingsCategoryRow(
            GalleryIcons.Settings,
            stringResource(R.string.settings_language),
            stringResource(AppLanguageLabels[current]),
            0,
            1,
            Modifier.testTag("settings_language_row"),
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
        manager.applicationLocales =
            if (index == 0) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(AppLanguageTags[index])
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
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier.fillMaxWidth(),
        leadingContent = {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        supportingContent = summary?.let { value -> { Text(value) } },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { Text(title) }
}

@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(title, onBack)
        Column(
            Modifier.fillMaxSize().widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(GallerySpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Lg),
        ) {
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
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = GallerySpacing.Xl,
                    vertical = GallerySpacing.Lg,
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
        modifier = Modifier.fillMaxWidth().clickable {
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
        modifier = Modifier.fillMaxWidth().clickable { onToggle(state != ToggleableState.On) },
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
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!selected) },
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
    SettingsSwitchRow(stringResource(R.string.settings_double_tap_zoom), settings.gestures.doubleTapZoom) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(doubleTapZoom = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_pinch_zoom), settings.gestures.pinchZoom) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(pinchZoom = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_swipe_down), settings.gestures.swipeDownToClose) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(swipeDownToClose = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_photo_brightness), settings.gestures.photoBrightness) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(photoBrightness = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_video_brightness), settings.gestures.videoBrightness) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoBrightness = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_video_volume), settings.gestures.videoVolume) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoVolume = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_video_seek), settings.gestures.videoSeek) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoSeek = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_rotate_photos), settings.gestures.rotatePhotos) {
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(rotatePhotos = it)) }
    }
    SettingsValueRow(stringResource(R.string.settings_photo_zoom_limit), stringResource(R.string.settings_zoom_value, settings.gestures.photoMaxZoom.toInt())) {
        val next = when (settings.gestures.photoMaxZoom.toInt()) { 2 -> 4f; 4 -> 8f; else -> 2f }
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(photoMaxZoom = next)) }
    }
    SettingsValueRow(stringResource(R.string.settings_video_zoom_limit), stringResource(R.string.settings_zoom_value, settings.gestures.videoMaxZoom.toInt())) {
        val next = when (settings.gestures.videoMaxZoom.toInt()) { 2 -> 4f; 4 -> 8f; else -> 2f }
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoMaxZoom = next)) }
    }
    SettingsValueRow(stringResource(R.string.settings_skip_seconds), stringResource(R.string.settings_seconds, settings.gestures.videoSkipSeconds)) {
        val next = when (settings.gestures.videoSkipSeconds) { 5 -> 10; 10 -> 15; 15 -> 30; else -> 5 }
        onSettingsChange { current -> current.copy(gestures = current.gestures.copy(videoSkipSeconds = next)) }
    }
}

@Composable
private fun ThumbnailsSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
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
    SettingsValueRow(stringResource(R.string.settings_grid_columns), settings.thumbnails.gridColumns.toString()) {
        val next = if (settings.thumbnails.gridColumns >= 8) 2 else settings.thumbnails.gridColumns + 1
        onSettingsChange { current -> current.copy(thumbnails = current.thumbnails.copy(gridColumns = next)) }
    }
}

@Composable
private fun OperationsSection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
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

@Composable
private fun SecuritySection(
    settings: GallerySettings,
    onSettingsChange: ((GallerySettings) -> GallerySettings) -> Unit,
) {
    SettingsSwitchRow(stringResource(R.string.settings_app_lock), settings.security.appLockEnabled) {
        onSettingsChange { current -> current.copy(security = current.security.copy(appLockEnabled = it)) }
    }
    SettingsSwitchRow(stringResource(R.string.settings_destructive_lock), settings.security.destructiveActionLockEnabled) {
        onSettingsChange { current -> current.copy(security = current.security.copy(destructiveActionLockEnabled = it)) }
    }
    SettingsValueRow(stringResource(R.string.settings_relock_timeout), stringResource(R.string.settings_minutes, settings.security.relockTimeoutMinutes)) {
        val next = when (settings.security.relockTimeoutMinutes) { 0 -> 1; 1 -> 5; 5 -> 15; else -> 0 }
        onSettingsChange { current -> current.copy(security = current.security.copy(relockTimeoutMinutes = next)) }
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
    if (onLocalSharing != null) OutlinedButton(onClick = onLocalSharing, modifier = Modifier.fillMaxWidth().testTag("settings-local-sharing")) { Text(stringResource(R.string.local_sharing_entry)) }
    if (onOwnSync != null) OutlinedButton(onClick = onOwnSync, modifier = Modifier.fillMaxWidth().testTag("settings-own-sync")) { Text(stringResource(R.string.own_sync_entry)) }
    if (onOfflinePlaces != null) OutlinedButton(onClick = onOfflinePlaces, modifier = Modifier.fillMaxWidth().testTag("settings-offline-places")) { Text(stringResource(R.string.offline_places_entry)) }
    if (onRemoteBackup != null) OutlinedButton(onClick = onRemoteBackup, modifier = Modifier.fillMaxWidth().testTag("settings-remote-backup")) {
        Text(stringResource(R.string.remote_backup_entry))
    }
    if (onLocalBackup != null) OutlinedButton(onClick = onLocalBackup, modifier = Modifier.fillMaxWidth().testTag("settings-local-backup")) {
        Text(stringResource(R.string.local_backup_title))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        OutlinedButton(onClick = onExportSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_export)) }
        OutlinedButton(onClick = onImportSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_import)) }
    }
    TextButton(onClick = onResetSettings) { Text(stringResource(R.string.settings_reset)) }
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
    onSemanticDownload: (String, Boolean) -> Unit,
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
        if (state.status == AnalysisStatus.Running) GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
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
    onDownload: (String, Boolean) -> Unit,
    onCancelDownload: (String) -> Unit,
    onActivate: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onAutomaticSelection: () -> Unit,
) {
    var downloadModel by rememberSaveable { mutableStateOf<String?>(null) }
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
                model.error?.let { Text(stringResource(R.string.semantic_index_error)) }
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
                        Text(stringResource(R.string.semantic_model_downloading, model.downloadedBytes / (1024 * 1024)))
                        TextButton(colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.LocalContentColor.current), modifier = Modifier.testTag("semantic_model_cancel_${model.id}"), onClick = { onCancelDownload(model.id) }) { Text(stringResource(R.string.semantic_model_cancel)) }
                    }
                    !model.installed -> GalleryExpressiveButton(modifier = Modifier.testTag("semantic_model_download_${model.id}"), onClick = { downloadModel = model.id }) {
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
    downloadModel?.let { id ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { downloadModel = null },
            title = { Text(stringResource(R.string.semantic_download_title)) },
            text = { Text(stringResource(R.string.semantic_download_body)) },
            confirmButton = {
                TextButton(onClick = { downloadModel = null; onDownload(id, false) }) {
                    Text(stringResource(R.string.semantic_download_wifi))
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadModel = null; onDownload(id, true) }) {
                    Text(stringResource(R.string.semantic_download_any_network))
                }
            },
        )
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
            onDownload = { _, _ -> },
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
        ),
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth(),
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
    ListItem(
        onClick = onClick,
        modifier = modifier,
        trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
    ) { Text(label) }
}
