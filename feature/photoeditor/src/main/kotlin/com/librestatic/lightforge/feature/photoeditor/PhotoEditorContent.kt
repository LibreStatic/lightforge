package com.librestatic.lightforge.feature.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.cameraFrame
import com.librestatic.lightforge.core.editing.image.PhotoAutoEnhancementSuggestions
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawMetadata
import com.librestatic.lightforge.core.model.RawOutputFormat
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.MediaEditorHistory
import com.librestatic.lightforge.core.designsystem.MediaEditorZoomResetChip
import com.librestatic.lightforge.core.designsystem.mediaEditorZoomGestures
import com.librestatic.lightforge.core.designsystem.mediaEditorZoomLayer
import com.librestatic.lightforge.core.designsystem.rememberMediaEditorZoomState
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSliderRules
import com.librestatic.lightforge.core.designsystem.MediaEditorScaffold
import com.librestatic.lightforge.core.designsystem.MediaEditorShortcut
import com.librestatic.lightforge.core.designsystem.mediaEditorShortcuts
import com.librestatic.lightforge.core.designsystem.MediaEditorToolChip
import com.librestatic.lightforge.core.designsystem.MediaEditorToolChips
import com.librestatic.lightforge.core.designsystem.MediaEditorTopBar
import com.librestatic.lightforge.feature.objecteraser.ObjectEraser
import com.librestatic.lightforge.feature.subjectclip.SubjectClipper
import kotlin.math.abs

data class PhotoEditorContentState(
    val preview: Bitmap? = null,
    val originalPreview: Bitmap? = null,
    val cropSourcePreview: Bitmap? = null,
    /** True when [cropSourcePreview] shows the full sensor area instead of the camera framing. */
    val cropSourceFullSensor: Boolean = false,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val isDirty: Boolean = false,
    val statusMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedFilter: String = "none",
    val tone: EditOperation.Tone = EditOperation.Tone(),
    val colorOperations: List<EditOperation> = emptyList(),
    val autoEnhancementSuggestions: PhotoAutoEnhancementSuggestions? = null,
    val isAutoEnhancementAnalyzing: Boolean = false,
    val crop: EditOperation.Crop? = null,
    val straightenDegrees: Float = 0f,
    /** The recipe's pixel-moving operations ([photoGeometryOperations]); pending dabs follow them. */
    val geometry: List<EditOperation> = emptyList(),
    val isRaw: Boolean = false,
    val rawMetadata: RawMetadata? = null,
    val rawSettings: RawDevelopmentSettings = RawDevelopmentSettings(),
    val rawOutputFormat: RawOutputFormat = RawOutputFormat.JpegSrgb,
    /** Experimental object eraser result over [preview]; Save copy re-applies it at full size. */
    val erasePreview: Bitmap? = null,
    val eraseMethod: ObjectEraser.EraseMethod? = null,
    /** Experimental subject cut-out of [preview] around [subjectClipSeed]. */
    val subjectClipPreview: Bitmap? = null,
    val subjectClipSeed: PhotoPoint? = null,
    val subjectClipMethod: SubjectClipper.ClipMethod? = null,
    val isExperimentalProcessing: Boolean = false,
    /** The photo with the whole colour grade applied by the GPU/CPU grader; wins over the legacy colour filter. */
    val gradedPreview: Bitmap? = null,
    val adjust: EditOperation.Adjust = EditOperation.Adjust(emptyMap()),
    val colorMix: EditOperation.ColorMix = EditOperation.ColorMix.Neutral,
    val look: EditOperation.Look? = null,
    /** Look thumbnails of the current photo keyed by look id (`builtin:vivid`, `custom:<uuid>`). */
    val lookThumbnails: Map<String, Bitmap> = emptyMap(),
    val customLooks: List<PhotoCustomLook> = emptyList(),
    /** The auto-straighten detector's answer in degrees, or null while unknown; clear it before each request. */
    val autoStraightenDegrees: Float? = null,
    /** The committed perspective correction, or null for none. */
    val perspective: EditOperation.Perspective? = null,
)

@Composable
fun PhotoEditorContent(
    state: PhotoEditorContentState,
    onBack: () -> Unit,
    onSaveCopy: () -> Unit,
    onApply: (EditOperation) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRawSettingsChange: (RawDevelopmentSettings) -> Unit = {},
    onRawSettingsChangeFinished: () -> Unit = {},
    onTonePreview: (EditOperation.Tone) -> Unit = { onApply(it) },
    onToneChangeFinished: () -> Unit = {},
    onApplyAutoSuggestion: (EditOperation.Tone?) -> Unit = { tone ->
        onApply(tone ?: EditOperation.Tone())
    },
    onRawOutputFormatChange: (RawOutputFormat) -> Unit = {},
    /** A crop drawn on the full sensor area that leaves the camera frame (null removes the crop). */
    onApplyFullSensorCrop: (EditOperation.Crop?, Float, EditOperation.Perspective?) -> Unit = { _, _, _ -> },
    /** The crop tool opened or closed; a RAW then develops the full sensor area as the crop source. */
    onCropToolActiveChange: (Boolean) -> Unit = {},
    onApplyObjectErase: (List<PhotoPoint>) -> Unit = {},
    onClearObjectErase: () -> Unit = {},
    onPreviewSubjectClip: (PhotoPoint) -> Unit = {},
    onSaveSubjectClip: () -> Unit = {},
    onAdjustPreview: (EditOperation.Adjust) -> Unit = { onApply(it) },
    onAdjustChangeFinished: () -> Unit = {},
    onColorMixPreview: (EditOperation.ColorMix) -> Unit = { onApply(it) },
    onColorMixChangeFinished: () -> Unit = {},
    onLookPreview: (EditOperation.Look?) -> Unit = { look -> look?.let(onApply) },
    onLookChangeFinished: () -> Unit = {},
    onCreateCustomLook: () -> Unit = {},
    onRenameCustomLook: (id: String, name: String) -> Unit = { _, _ -> },
    onDeleteCustomLook: (id: String) -> Unit = {},
    onAutoStraighten: () -> Unit = {},
    /** Hands the photo to another editor app; the More tab only exists while this is set. */
    onOpenWith: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Tool and uncommitted crop survive rotation; the edit history already lives in the view model.
    // Explicit keys: the host composes this under a navigation rail in landscape and a bottom bar
    // in portrait, so positional keys would not match across the rotation (F-E2E-05).
    val initialUi = LocalPhotoEditorInitialUi.current
    var cropDraft by rememberSaveable(stateSaver = PhotoCropDraftSaver, key = "photo-editor-crop-draft") {
        mutableStateOf<PhotoCropDraft?>(if (initialUi.tool == PhotoEditorTool.Crop) cropDraftFromState(state) else null)
    }
    var compareOriginal by remember { mutableStateOf(false) }
    val defaultTool = if (state.isRaw) PhotoEditorTool.Raw else PhotoEditorTool.Automatic
    var selectedTool by rememberSaveable(state.isRaw, key = "photo-editor-tool") {
        mutableStateOf(initialUi.tool ?: defaultTool)
    }
    var adjustTile by rememberSaveable(key = "photo-editor-adjust-tile") { mutableStateOf(initialUi.adjustTile) }
    var cropTool by rememberSaveable(key = "photo-editor-crop-tool") { mutableStateOf(initialUi.cropTool) }
    var looksTab by rememberSaveable(key = "photo-editor-looks-tab") { mutableStateOf(initialUi.looksTab) }
    var searchOpen by rememberSaveable(key = "photo-editor-search-open") { mutableStateOf(initialUi.searchOpen) }
    val nav = PhotoToolNav(adjustTile, cropTool, { cropTool = it }, looksTab, { looksTab = it })
    val panelActions = PhotoPanelActions(
        onAdjustPreview = onAdjustPreview,
        onAdjustChangeFinished = onAdjustChangeFinished,
        onColorMixPreview = onColorMixPreview,
        onColorMixChangeFinished = onColorMixChangeFinished,
        onLookPreview = onLookPreview,
        onLookChangeFinished = onLookChangeFinished,
        onCreateCustomLook = onCreateCustomLook,
        onRenameCustomLook = onRenameCustomLook,
        onDeleteCustomLook = onDeleteCustomLook,
        onAutoStraighten = onAutoStraighten,
        onOpenWith = onOpenWith,
    )
    // Brush dabs not yet applied; applied dabs live in the view model with the erased preview.
    var eraseStrokes by remember { mutableStateOf<List<PhotoPoint>>(emptyList()) }
    // Pending dabs are in edited-image coordinates: re-project them when crop/rotate/flip change.
    var eraseStrokesGeometry by remember { mutableStateOf(state.geometry) }
    if (eraseStrokesGeometry != state.geometry) {
        eraseStrokes = reprojectEditedPoints(eraseStrokes, eraseStrokesGeometry, state.geometry)
        eraseStrokesGeometry = state.geometry
    }
    val experimental = ExperimentalToolActions(
        eraseStrokes = eraseStrokes,
        onEraseStrokesChange = { eraseStrokes = it },
        onApplyErase = {
            onApplyObjectErase(eraseStrokes)
            eraseStrokes = emptyList()
        },
        onClearErase = {
            eraseStrokes = emptyList()
            onClearObjectErase()
        },
        onPreviewSubjectClip = onPreviewSubjectClip,
        onSaveSubjectClip = onSaveSubjectClip,
    )
    // The camera frame inside the full sensor image; the crop tool draws it as the starting crop.
    val cameraFrame = state.rawMetadata?.takeIf { state.isRaw && state.rawSettings.cameraFraming }
        ?.cameraFrame(state.geometry)
    val fullSensorSource = cameraFrame != null && state.cropSourceFullSensor
    // Switch the draft's coordinates together with the crop source bitmap, in the same frame.
    cropDraft?.let { draft ->
        if (fullSensorSource && !draft.fullSensor) cropDraft = draft.toFullSensor(cameraFrame!!)
        else if (!fullSensorSource && draft.fullSensor) cropDraft = draft.toFramed(cameraFrame)
    }
    val cropToolOpen = selectedTool == PhotoEditorTool.Crop
    LaunchedEffect(cropToolOpen) { onCropToolActiveChange(cropToolOpen) }
    fun cropOutcomeOf(draft: PhotoCropDraft) = cropOutcome(draft, cameraFrame, state.crop)
    fun applyCropDraft(draft: PhotoCropDraft) {
        val outcome = cropOutcomeOf(draft)
        if (outcome.needsFullSensor) {
            onApplyFullSensorCrop(outcome.crop.takeUnless { it.isFullFrame }, outcome.straightenDegrees, outcome.perspective)
        } else outcome.operations.forEach(onApply)
    }
    fun commitCropDraft() {
        cropDraft?.let(::applyCropDraft)
        cropDraft = null
    }
    fun selectTool(tool: PhotoEditorTool) {
        if (tool == selectedTool) return
        commitCropDraft()
        selectedTool = tool
        if (tool == PhotoEditorTool.Crop) cropDraft = cropDraftFromState(state)
    }
    fun selectTab(tab: PhotoEditorTab) {
        if (tab != selectedTool.tab()) selectTool(tab.firstTool())
    }
    fun navigateTo(destination: PhotoToolDestination) {
        destination.adjustTile?.let { adjustTile = it }
        destination.cropTool?.let { cropTool = it }
        destination.looksTab?.let { looksTab = it }
        selectTool(destination.tool)
        searchOpen = false
    }
    // A changed crop draft is an edit: Back commits it (so Undo sees it) and then asks the host,
    // which shows its discard confirmation once the committed history reports dirty (V-04).
    var backAfterCropCommit by remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(backAfterCropCommit, state.isDirty) {
        if (backAfterCropCommit && state.isDirty) {
            backAfterCropCommit = false
            currentOnBack()
        }
    }
    fun backFromCropDraft() {
        val draft = cropDraft ?: return
        val outcome = cropOutcomeOf(draft)
        if (outcome.needsFullSensor || outcome.operations != cropEditOperations(cropDraftFromState(state))) {
            commitCropDraft()
            backAfterCropCommit = true
        } else cropDraft = null
        selectedTool = defaultTool
    }
    BackHandler(enabled = cropDraft != null, onBack = ::backFromCropDraft)
    val experimentalPanel = selectedTool.isExperimentalTool()
    MediaEditorScaffold(
        // Ctrl+Z / Ctrl+Shift+Z walk the history and Esc leaves the crop draft or the editor.
        modifier = modifier.mediaEditorShortcuts { command ->
            when (command) {
                MediaEditorShortcut.Undo -> state.canUndo.also { if (it) onUndo() }
                MediaEditorShortcut.Redo -> state.canRedo.also { if (it) onRedo() }
                MediaEditorShortcut.Cancel -> { if (cropDraft != null) backFromCropDraft() else onBack(); true }
                else -> false
            }
        },
        topBar = {
            MediaEditorTopBar(
                title = stringResource(R.string.photo_editor_title),
                onCancel = { if (cropDraft != null) backFromCropDraft() else onBack() },
                cancelLabel = stringResource(R.string.photo_editor_cancel),
                actionLabel = stringResource(R.string.photo_editor_save_copy),
                onAction = {
                    commitCropDraft()
                    onSaveCopy()
                },
                actionEnabled = !state.isExporting,
                history = MediaEditorHistory(
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    undoLabel = stringResource(R.string.photo_editor_undo),
                    redoLabel = stringResource(R.string.photo_editor_redo),
                ),
                actionTestTag = "photo-editor-save-copy",
            )
        },
        media = { mediaModifier ->
            PreviewStage(state, cropDraft, cameraFrame, compareOriginal, { compareOriginal = it }, { cropDraft = it }, selectedTool, cropTool, experimental, mediaModifier)
        },
        inspector = { inspectorModifier, layout ->
            PhotoTools(
                state,
                selectedTool,
                ::selectTool,
                ::selectTab,
                { searchOpen = true },
                nav,
                panelActions,
                cropDraft,
                { cropDraft = it },
                cameraFrame,
                ::applyCropDraft,
                onApply,
                onRawSettingsChange,
                onRawSettingsChangeFinished,
                onApplyAutoSuggestion,
                onRawOutputFormatChange,
                experimental,
                inspectorModifier,
                sideBySide = layout.isSideBySide,
                // Eraser and cut-out panels hold prose (fallback notice + hint) that a 0.42 weight
                // squeezed into a sliver; let them wrap up to a cap so the text reads in full (Z-05).
                wrapPanel = experimentalPanel && !layout.isSideBySide,
            )
        },
        stackedMediaWeight = 1f,
        // The crop panel stacks Rotate/Flip, aspect chips, the Straighten slider and Reset/Cancel/Apply;
        // the default share clipped the slider and left Apply half hidden. The other tools list sliders
        // and suggestion cards that a smaller share cut down to a single visible row.
        stackedInspectorWeight = when {
            experimentalPanel -> null
            selectedTool == PhotoEditorTool.Crop || selectedTool == PhotoEditorTool.Adjust || selectedTool == PhotoEditorTool.Filters -> 1.5f
            else -> 0.8f
        },
        stackedInspectorMaxFraction = 0.5f,
    )
    if (searchOpen) {
        val entries = remember(state.isRaw, onOpenWith != null) { photoToolEntries(state.isRaw, onOpenWith != null) }
        PhotoToolSearchSheet(entries, onDismiss = { searchOpen = false }, onSelect = ::navigateTo)
    }
}

@Composable
private fun PreviewStage(
    state: PhotoEditorContentState,
    cropDraft: PhotoCropDraft?,
    cameraFrame: EditOperation.Crop?,
    compareOriginal: Boolean,
    onCompareOriginalChange: (Boolean) -> Unit,
    onCropDraftChange: (PhotoCropDraft) -> Unit,
    selectedTool: PhotoEditorTool,
    cropTool: PhotoCropTool,
    experimental: ExperimentalToolActions,
    modifier: Modifier,
) {
    val zoom = rememberMediaEditorZoomState(state.geometry)
    // The crop handles live in unzoomed stage space, so cropping always works at 1x.
    val zoomEnabled = cropDraft == null
    LaunchedEffect(zoom, zoomEnabled) { if (!zoomEnabled) zoom.resetImmediately() }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .testTag("photo-editor-preview")
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clipToBounds()
            .onSizeChanged { stageSize = it }
            .mediaEditorZoomGestures(
                zoom,
                enabled = zoomEnabled,
                // One finger paints with the eraser, so only two fingers move the photo there.
                singleFingerPan = selectedTool != PhotoEditorTool.ObjectEraser,
                doubleTapZoom = selectedTool != PhotoEditorTool.ObjectEraser &&
                    selectedTool != PhotoEditorTool.SubjectClip,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val erasing = selectedTool == PhotoEditorTool.ObjectEraser && cropDraft == null
        val clipping = selectedTool == PhotoEditorTool.SubjectClip && cropDraft == null
        val preview = when {
            cropDraft != null -> state.cropSourcePreview ?: state.originalPreview ?: state.preview
            compareOriginal -> state.originalPreview ?: state.preview
            clipping && state.subjectClipPreview != null -> state.subjectClipPreview
            else -> state.gradedPreview ?: state.erasePreview ?: state.preview
        }
        SideEffect {
            val bitmapSize = preview?.let { IntSize(it.width, it.height) } ?: IntSize.Zero
            zoom.updateSizes(fittedImageBounds(stageSize, bitmapSize).size, stageSize.toSize())
        }
        if (preview != null) {
            val description = stringResource(R.string.photo_editor_preview_description)
            Box(Modifier.fillMaxSize().mediaEditorZoomLayer(zoom)) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(rotationZ = cropDraft?.straightenDegrees ?: 0f)
                    .pointerInput(state.originalPreview, cropDraft, erasing, clipping) {
                    if (cropDraft != null || state.originalPreview == null || erasing || clipping) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onCompareOriginalChange(true)
                        var comparing = true
                        try {
                            do {
                                val event = awaitPointerEvent()
                                // A pinch or pan is not a compare press: end it, then wait for release.
                                if (comparing && (event.changes.any { it.isConsumed } ||
                                        event.changes.count { it.pressed } >= 2)
                                ) {
                                    comparing = false
                                    onCompareOriginalChange(false)
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            onCompareOriginalChange(false)
                        }
                        down.consume()
                    }
                },
            )
            cropDraft?.let {
                if (cropTool == PhotoCropTool.Perspective) {
                    PhotoPerspectiveOverlay(
                        bitmap = preview,
                        corners = it.perspective,
                        onChange = { corners -> onCropDraftChange(it.withPerspective(corners)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    InteractiveCropOverlay(
                        bitmapSize = IntSize(preview.width, preview.height),
                        draft = it,
                        onChange = onCropDraftChange,
                        modifier = Modifier.fillMaxSize(),
                        referenceFrame = cameraFrame?.takeIf { _ -> it.fullSensor }?.toDraft(),
                    )
                    if (it.hasPerspective) {
                        PerspectiveQuadOutline(IntSize(preview.width, preview.height), it.perspective, Modifier.fillMaxSize())
                    }
                }
            }
            if (erasing || clipping) {
                ExperimentalPreviewOverlay(
                    bitmapSize = IntSize(preview.width, preview.height),
                    erasing = erasing,
                    eraseStrokes = experimental.eraseStrokes,
                    onEraseStrokesChange = experimental.onEraseStrokesChange,
                    subjectClipSeed = state.subjectClipSeed,
                    onSubjectTap = experimental.onPreviewSubjectClip,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            }
            if (state.isExperimentalProcessing) GalleryLoadingIndicator()
        } else if (state.isRendering) {
            GalleryLoadingIndicator()
        } else {
            Text(
                stringResource(R.string.photo_editor_preview_unavailable),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        MediaEditorZoomResetChip(zoom, Modifier.align(Alignment.TopEnd).padding(12.dp))
        state.statusMessage?.let { message ->
            // Drawn over the photo, so it carries its own container instead of bare text.
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    message,
                    Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun PhotoTools(
    state: PhotoEditorContentState,
    selectedTool: PhotoEditorTool,
    onSelectTool: (PhotoEditorTool) -> Unit,
    onSelectTab: (PhotoEditorTab) -> Unit,
    onSearch: () -> Unit,
    nav: PhotoToolNav,
    actions: PhotoPanelActions,
    cropDraft: PhotoCropDraft?,
    onCropDraftChange: (PhotoCropDraft?) -> Unit,
    cameraFrame: EditOperation.Crop?,
    onApplyCrop: (PhotoCropDraft) -> Unit,
    onApply: (EditOperation) -> Unit,
    onRawSettingsChange: (RawDevelopmentSettings) -> Unit,
    onRawSettingsChangeFinished: () -> Unit,
    onApplyAutoSuggestion: (EditOperation.Tone?) -> Unit,
    onRawOutputFormatChange: (RawOutputFormat) -> Unit,
    experimental: ExperimentalToolActions,
    modifier: Modifier,
    sideBySide: Boolean,
    wrapPanel: Boolean = false,
) {
    val tabs = photoEditorTabs(state.isRaw, canOpenWith = actions.onOpenWith != null)
    val selectedTab = selectedTool.tab()
    Surface(
        modifier = modifier,
        color = if (sideBySide) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
    Column(
        Modifier.padding(vertical = GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
    ) {
        // Beside the media the tabs sit in an even grid at the top of the inspector, so none is ever clipped (bug 35).
        if (sideBySide) PhotoToolNavigationPanel(tabs, selectedTab, onSelectTab, onSearch)
        Row(Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg)) {
            Text(tabLabel(selectedTab), style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier
                .weight(1f, fill = !wrapPanel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GallerySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
        ) {
            when (selectedTool) {
                PhotoEditorTool.Automatic -> AutomaticControls(state, onApplyAutoSuggestion, fillWidth = sideBySide)
                PhotoEditorTool.Crop -> cropDraft?.let { draft ->
                    CropControls(
                        draft = draft,
                        cropTool = nav.cropTool,
                        onCropToolChange = nav.onCropTool,
                        sourceAspectRatio = (state.cropSourcePreview ?: state.preview)
                            ?.let { it.width.toFloat() / it.height } ?: 1f,
                        autoStraightenDegrees = state.autoStraightenDegrees,
                        onAutoStraighten = actions.onAutoStraighten,
                        onChange = { onCropDraftChange(it) },
                        // The overlay frames the rotated image, so the draft turns with it.
                        onRotate = { clockwise ->
                            var turned = draft
                            repeat(if (clockwise) 1 else 3) { turned = turned.rotatedClockwise() }
                            onCropDraftChange(turned)
                            onApply(EditOperation.Rotate(if (clockwise) 90 else 270))
                        },
                        onFlip = { horizontal ->
                            onCropDraftChange(if (horizontal) draft.flippedHorizontally() else draft.flippedVertically())
                            onApply(EditOperation.Flip(horizontal = horizontal))
                        },
                        onCameraFraming = cameraFrame?.takeIf { draft.fullSensor }?.let { frame ->
                            { onCropDraftChange(draft.resetToFrame(frame)) }
                        },
                    )
                }
                PhotoEditorTool.Adjust -> AdjustControls(state, nav.adjustTile, actions)
                PhotoEditorTool.Filters -> FiltersControls(state, nav.looksTab, nav.onLooksTab, actions)
                PhotoEditorTool.ObjectEraser -> {
                    ActionsTools(selectedTool, onSelectTool)
                    ObjectEraserControls(state, experimental)
                }
                PhotoEditorTool.SubjectClip -> {
                    ActionsTools(selectedTool, onSelectTool)
                    SubjectClipControls(state, experimental)
                }
                PhotoEditorTool.More -> MoreControls(actions.onOpenWith)
                PhotoEditorTool.Raw -> RawControls(
                    state.rawSettings,
                    state.rawMetadata,
                    onRawSettingsChange,
                    onRawSettingsChangeFinished,
                )
                PhotoEditorTool.Export -> RawExportControls(state.rawOutputFormat, onRawOutputFormatChange)
            }
            Text(
                stringResource(R.string.photo_editor_copy_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Outside the scrolling panel so Apply/Clear, Save cut-out and crop Apply stay on screen (Z-03).
        Box(Modifier.padding(horizontal = GallerySpacing.Lg)) {
            when (selectedTool) {
                PhotoEditorTool.ObjectEraser -> ObjectEraserActions(state, experimental)
                PhotoEditorTool.SubjectClip -> SubjectClipActions(state, experimental)
                PhotoEditorTool.Crop -> cropDraft?.let { draft ->
                    val leaveCrop = { onSelectTool(if (state.isRaw) PhotoEditorTool.Raw else PhotoEditorTool.Automatic) }
                    CropActions(
                        draft = draft,
                        onChange = { onCropDraftChange(it) },
                        onCancel = {
                            onCropDraftChange(null)
                            leaveCrop()
                        },
                        onApply = {
                            onApplyCrop(draft)
                            onCropDraftChange(null)
                            leaveCrop()
                        },
                    )
                }
                else -> Unit
            }
        }

        if (!sideBySide) PhotoToolNavigation(tabs, selectedTab, onSelectTab, onSearch)
    }
    }
}

@Composable
private fun AutomaticControls(
    state: PhotoEditorContentState,
    onApply: (EditOperation.Tone?) -> Unit,
    fillWidth: Boolean,
) {
    val source = state.originalPreview ?: state.preview
    if (source == null) {
        if (state.isAutoEnhancementAnalyzing || state.isRendering) GalleryLoadingIndicator()
        return
    }
    val suggestions = state.autoEnhancementSuggestions
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { cardModifier ->
            AutoSuggestionCard(
                tag = "photo-editor-auto-original",
                source = source,
                label = stringResource(R.string.photo_editor_filter_original),
                tone = null,
                selected = state.tone == EditOperation.Tone() && state.selectedFilter == "none",
                enabled = true,
                onClick = { onApply(null) },
                modifier = cardModifier,
            )
        },
        { cardModifier ->
            AutoSuggestionCard(
                tag = "photo-editor-auto-enhance",
                source = source,
                label = stringResource(R.string.photo_editor_auto_enhance),
                tone = suggestions?.enhance,
                selected = suggestions?.enhance == state.tone && state.selectedFilter == "none",
                enabled = suggestions != null,
                onClick = { suggestions?.enhance?.let(onApply) },
                modifier = cardModifier,
            )
        },
        { cardModifier ->
            AutoSuggestionCard(
                tag = "photo-editor-auto-dynamic",
                source = source,
                label = stringResource(R.string.photo_editor_auto_dynamic),
                tone = suggestions?.dynamic,
                selected = suggestions?.dynamic == state.tone && state.selectedFilter == "none",
                enabled = suggestions != null,
                onClick = { suggestions?.dynamic?.let(onApply) },
                modifier = cardModifier,
            )
        },
    )
    if (fillWidth) {
        // Beside the media the three cards share the inspector width instead of scrolling off its edge.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            cards.forEach { it(Modifier.weight(1f)) }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            items(cards) { it(Modifier.width(120.dp)) }
        }
    }
    if (state.isAutoEnhancementAnalyzing) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            GalleryLoadingIndicator()
            Text(stringResource(R.string.photo_editor_auto_analyzing), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AutoSuggestionCard(
    tag: String,
    source: Bitmap,
    label: String,
    tone: EditOperation.Tone?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val matrix = remember(tone) {
        tone?.let { ColorFilter.colorMatrix(ColorMatrix(
            com.librestatic.lightforge.core.editing.image.PhotoColorTransform.combinedValues(listOf(it)),
        )) }
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.testTag(tag),
    ) {
        Column {
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = stringResource(R.string.photo_editor_auto_preview, label),
                contentScale = ContentScale.Crop,
                colorFilter = matrix,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            Text(
                label,
                Modifier.padding(GallerySpacing.Sm),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RawControls(
    settings: RawDevelopmentSettings,
    metadata: RawMetadata?,
    onChange: (RawDevelopmentSettings) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            // Framing is a choice about the frame, not an adjustment, so reset keeps it.
            onChange(RawDevelopmentSettings(cameraFraming = settings.cameraFraming))
            onChangeFinished()
        }) {
            Text(stringResource(R.string.photo_editor_reset_raw))
        }
    }
    metadata?.let {
        Text(
            stringResource(
                R.string.photo_editor_raw_metadata,
                listOf(it.make, it.model).filter(String::isNotBlank).joinToString(" "),
                it.dimensions(settings.cameraFraming).width,
                it.dimensions(settings.cameraFraming).height,
                it.bitsPerSample,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (it.hasMeaningfulCameraCrop) {
            RawFramingChoice(settings, it, onChange, onChangeFinished)
        }
    }
    RawSlider(stringResource(R.string.photo_editor_exposure), settings.exposureEv, -5f..5f, onChangeFinished) {
        onChange(settings.copy(exposureEv = it))
    }
    RawSlider(stringResource(R.string.photo_editor_temperature), settings.temperatureKelvin.toFloat(), 2_000f..14_000f, onChangeFinished) {
        onChange(settings.copy(temperatureKelvin = it.toInt()))
    }
    RawSlider(stringResource(R.string.photo_editor_tint), settings.tint, -150f..150f, onChangeFinished) { onChange(settings.copy(tint = it)) }
    RawSlider(stringResource(R.string.photo_editor_highlights), settings.highlights, -1f..1f, onChangeFinished) { onChange(settings.copy(highlights = it)) }
    RawSlider(stringResource(R.string.photo_editor_shadows), settings.shadows, -1f..1f, onChangeFinished) { onChange(settings.copy(shadows = it)) }
    RawSlider(stringResource(R.string.photo_editor_whites), settings.whites, -1f..1f, onChangeFinished) { onChange(settings.copy(whites = it)) }
    RawSlider(stringResource(R.string.photo_editor_blacks), settings.blacks, -1f..1f, onChangeFinished) { onChange(settings.copy(blacks = it)) }
    RawSlider(stringResource(R.string.photo_editor_contrast), settings.contrast, -1f..1f, onChangeFinished) { onChange(settings.copy(contrast = it)) }
    RawSlider(stringResource(R.string.photo_editor_saturation), settings.saturation, -1f..1f, onChangeFinished) { onChange(settings.copy(saturation = it)) }
    RawSlider(stringResource(R.string.photo_editor_vibrance), settings.vibrance, -1f..1f, onChangeFinished) { onChange(settings.copy(vibrance = it)) }
    RawSlider(stringResource(R.string.photo_editor_highlight_recovery), settings.highlightRecovery, 0f..1f, onChangeFinished) {
        onChange(settings.copy(highlightRecovery = it))
    }
    RawSlider(stringResource(R.string.photo_editor_noise_reduction), settings.luminanceNoiseReduction, 0f..1f, onChangeFinished) {
        onChange(settings.copy(luminanceNoiseReduction = it))
    }
    RawSlider(stringResource(R.string.photo_editor_chroma_noise_reduction), settings.chromaNoiseReduction, 0f..1f, onChangeFinished) {
        onChange(settings.copy(chromaNoiseReduction = it))
    }
    RawSlider(stringResource(R.string.photo_editor_sharpening), settings.sharpening, 0f..1f, onChangeFinished) {
        onChange(settings.copy(sharpening = it))
    }
}

@Composable
private fun RawFramingChoice(
    settings: RawDevelopmentSettings,
    metadata: RawMetadata,
    onChange: (RawDevelopmentSettings) -> Unit,
    onChangeFinished: () -> Unit,
) {
    val options = listOf(true to R.string.photo_editor_raw_camera_framing, false to R.string.photo_editor_raw_full_sensor)
    Column(
        Modifier.fillMaxWidth().testTag("raw-camera-framing"),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (framing, label) ->
                SegmentedButton(
                    selected = settings.cameraFraming == framing,
                    onClick = {
                        if (settings.cameraFraming != framing) {
                            onChange(settings.copy(cameraFraming = framing))
                            onChangeFinished()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.testTag(if (framing) "raw-framing-camera" else "raw-framing-full"),
                ) {
                    Text(stringResource(label))
                }
            }
        }
        Text(
            stringResource(
                R.string.photo_editor_raw_framing_summary,
                metadata.width, metadata.height, metadata.fullWidth, metadata.fullHeight,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RawSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onFinished: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val wholeNumbers = range.endInclusive > 100f
    if (range.start < 0f && range.endInclusive > 0f) {
        // Bidirectional (exposure, tint, highlights…): centred fill and a detent at 0.
        EditorAdjustmentSlider(
            label = label,
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            displayValue = { EditorAdjustmentSliderRules.format(it, 0f, decimals = if (wholeNumbers) 0 else 2) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(if (wholeNumbers) value.toInt().toString() else "%.2f".format(value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

@Composable
private fun RawExportControls(selected: RawOutputFormat, onSelected: (RawOutputFormat) -> Unit) {
    Text(stringResource(R.string.photo_editor_output_format), style = MaterialTheme.typography.titleSmall)
    // Capability-gate TIFF until its 16-bit post-processing path can reproduce every preview
    // operation; JPEG currently has verified preview/export parity.
    FilterChip(
        selected = selected == RawOutputFormat.JpegSrgb,
        onClick = { onSelected(RawOutputFormat.JpegSrgb) },
        label = { Text(stringResource(R.string.photo_editor_output_jpeg)) },
    )
}

private fun PhotoEditorTool.isExperimentalTool(): Boolean =
    this == PhotoEditorTool.ObjectEraser || this == PhotoEditorTool.SubjectClip
