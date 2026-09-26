package com.ugallery.feature.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.ugallery.core.model.EditOperation
import com.ugallery.core.editing.image.PhotoAutoEnhancementSuggestions
import com.ugallery.core.model.RawDevelopmentSettings
import com.ugallery.core.model.RawMetadata
import com.ugallery.core.model.RawOutputFormat
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.GalleryExpressiveIconButton
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GalleryTopAppBar
import kotlin.math.abs

data class PhotoEditorContentState(
    val preview: Bitmap? = null,
    val originalPreview: Bitmap? = null,
    val cropSourcePreview: Bitmap? = null,
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
    val isRaw: Boolean = false,
    val rawMetadata: RawMetadata? = null,
    val rawSettings: RawDevelopmentSettings = RawDevelopmentSettings(),
    val rawOutputFormat: RawOutputFormat = RawOutputFormat.JpegSrgb,
)

private enum class PhotoEditorTool { Automatic, Crop, Adjust, Filters, Raw, Export }

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
    modifier: Modifier = Modifier,
) {
    // Tool and uncommitted crop survive rotation; the edit history already lives in the view model.
    var cropDraft by rememberSaveable(stateSaver = PhotoCropDraftSaver) { mutableStateOf<PhotoCropDraft?>(null) }
    var compareOriginal by remember { mutableStateOf(false) }
    val defaultTool = if (state.isRaw) PhotoEditorTool.Raw else PhotoEditorTool.Automatic
    var selectedTool by rememberSaveable(state.isRaw) { mutableStateOf(defaultTool) }
    fun commitCropDraft() {
        cropDraft?.let { cropEditOperations(it).forEach(onApply) }
        cropDraft = null
    }
    fun selectTool(tool: PhotoEditorTool) {
        if (tool == selectedTool) return
        commitCropDraft()
        selectedTool = tool
        if (tool == PhotoEditorTool.Crop) cropDraft = cropDraftFromState(state)
    }
    Scaffold(modifier = modifier, topBar = {
        GalleryTopAppBar(
            title = stringResource(R.string.photo_editor_title),
            onBack = {
                if (cropDraft != null) {
                    cropDraft = null
                    selectedTool = defaultTool
                } else onBack()
            },
            navigationContentDescription = stringResource(R.string.photo_editor_cancel),
            actions = {
                TextButton(onClick = {
                    commitCropDraft()
                    onSaveCopy()
                }, enabled = !state.isExporting) {
                    Text(stringResource(R.string.photo_editor_save_copy))
                }
            },
        )
    }) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
        ) {
            // Landscape phones are too short for preview, tabs and tool rows stacked vertically.
            val useSidePanel = maxWidth >= 600.dp && maxWidth >= maxHeight * 1.2f
            if (useSidePanel) {
                Row(Modifier.fillMaxSize()) {
                    PreviewStage(state, cropDraft, compareOriginal, { compareOriginal = it }, { cropDraft = it }, Modifier.weight(1f).fillMaxSize())
                    PhotoTools(state, selectedTool, ::selectTool, cropDraft, { cropDraft = it }, onApply, onUndo, onRedo, onRawSettingsChange, onRawSettingsChangeFinished, onTonePreview, onToneChangeFinished, onApplyAutoSuggestion, onRawOutputFormatChange, Modifier.weight(0.42f).padding(16.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PreviewStage(state, cropDraft, compareOriginal, { compareOriginal = it }, { cropDraft = it }, Modifier.weight(1f).fillMaxWidth())
                    PhotoTools(
                        state,
                        selectedTool,
                        ::selectTool,
                        cropDraft,
                        { cropDraft = it },
                        onApply,
                        onUndo,
                        onRedo,
                        onRawSettingsChange,
                        onRawSettingsChangeFinished,
                        onTonePreview,
                        onToneChangeFinished,
                        onApplyAutoSuggestion,
                        onRawOutputFormatChange,
                        Modifier.weight(0.42f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewStage(
    state: PhotoEditorContentState,
    cropDraft: PhotoCropDraft?,
    compareOriginal: Boolean,
    onCompareOriginalChange: (Boolean) -> Unit,
    onCropDraftChange: (PhotoCropDraft) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val preview = when {
            cropDraft != null -> state.cropSourcePreview ?: state.originalPreview ?: state.preview
            compareOriginal -> state.originalPreview ?: state.preview
            else -> state.preview
        }
        if (preview != null) {
            val description = stringResource(R.string.photo_editor_preview_description)
            val colorFilter = remember(state.colorOperations, cropDraft, compareOriginal) {
                if (cropDraft != null || compareOriginal || state.colorOperations.isEmpty()) null
                else ColorFilter.colorMatrix(ColorMatrix(
                    com.ugallery.core.editing.image.PhotoColorTransform.combinedValues(state.colorOperations),
                ))
            }
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(rotationZ = cropDraft?.straightenDegrees ?: 0f)
                    .pointerInput(state.originalPreview, cropDraft) {
                    if (cropDraft != null || state.originalPreview == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onCompareOriginalChange(true)
                        try {
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        } finally {
                            onCompareOriginalChange(false)
                        }
                        down.consume()
                    }
                },
            )
            cropDraft?.let {
                InteractiveCropOverlay(
                    bitmapSize = IntSize(preview.width, preview.height),
                    draft = it,
                    onChange = onCropDraftChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (state.isRendering) {
            GalleryLoadingIndicator()
        } else {
            Text(stringResource(R.string.photo_editor_preview_unavailable), color = Color.White)
        }
        state.statusMessage?.let { message ->
            Text(
                message,
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PhotoTools(
    state: PhotoEditorContentState,
    selectedTool: PhotoEditorTool,
    onSelectTool: (PhotoEditorTool) -> Unit,
    cropDraft: PhotoCropDraft?,
    onCropDraftChange: (PhotoCropDraft?) -> Unit,
    onApply: (EditOperation) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRawSettingsChange: (RawDevelopmentSettings) -> Unit,
    onRawSettingsChangeFinished: () -> Unit,
    onTonePreview: (EditOperation.Tone) -> Unit,
    onToneChangeFinished: () -> Unit,
    onApplyAutoSuggestion: (EditOperation.Tone?) -> Unit,
    onRawOutputFormatChange: (RawOutputFormat) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when {
                    selectedTool == PhotoEditorTool.Automatic -> stringResource(R.string.photo_editor_automatic)
                    selectedTool == PhotoEditorTool.Crop -> stringResource(R.string.photo_editor_crop)
                    selectedTool == PhotoEditorTool.Adjust -> stringResource(R.string.photo_editor_adjust)
                    selectedTool == PhotoEditorTool.Filters -> stringResource(R.string.photo_editor_filters)
                    selectedTool == PhotoEditorTool.Raw -> stringResource(R.string.photo_editor_raw_title)
                    else -> stringResource(R.string.photo_editor_export_tab)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Row {
                GalleryExpressiveIconButton(onClick = onUndo, enabled = state.canUndo) {
                    Icon(GalleryIcons.Undo, contentDescription = stringResource(R.string.photo_editor_undo))
                }
                GalleryExpressiveIconButton(onClick = onRedo, enabled = state.canRedo) {
                    Icon(GalleryIcons.Redo, contentDescription = stringResource(R.string.photo_editor_redo))
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
        ) {
            when (selectedTool) {
                PhotoEditorTool.Automatic -> AutomaticControls(state, onApplyAutoSuggestion)
                PhotoEditorTool.Crop -> cropDraft?.let { draft ->
                    CropControls(
                        draft = draft,
                        sourceAspectRatio = (state.cropSourcePreview ?: state.preview)
                            ?.let { it.width.toFloat() / it.height } ?: 1f,
                        onChange = { onCropDraftChange(it) },
                        // The overlay frames the rotated image, so the draft turns with it.
                        onRotate = {
                            onCropDraftChange(draft.rotatedClockwise())
                            onApply(EditOperation.Rotate(90))
                        },
                        onFlip = {
                            onCropDraftChange(draft.flippedHorizontally())
                            onApply(EditOperation.Flip(horizontal = true))
                        },
                        onCancel = {
                            onCropDraftChange(null)
                            onSelectTool(if (state.isRaw) PhotoEditorTool.Raw else PhotoEditorTool.Automatic)
                        },
                        onApply = {
                            cropEditOperations(draft).forEach(onApply)
                            onCropDraftChange(null)
                            onSelectTool(if (state.isRaw) PhotoEditorTool.Raw else PhotoEditorTool.Automatic)
                        },
                    )
                }
                PhotoEditorTool.Adjust -> {
                    PhotoToneSlider(
                        label = stringResource(R.string.photo_editor_brightness),
                        value = state.tone.brightness,
                        range = -1f..1f,
                        onChange = { onTonePreview(state.tone.copy(brightness = it)) },
                        onFinished = onToneChangeFinished,
                    )
                    PhotoToneSlider(
                        label = stringResource(R.string.photo_editor_contrast),
                        value = state.tone.contrast,
                        range = 0f..2f,
                        onChange = { onTonePreview(state.tone.copy(contrast = it)) },
                        onFinished = onToneChangeFinished,
                    )
                    PhotoToneSlider(
                        label = stringResource(R.string.photo_editor_saturation),
                        value = state.tone.saturation,
                        range = 0f..2f,
                        onChange = { onTonePreview(state.tone.copy(saturation = it)) },
                        onFinished = onToneChangeFinished,
                    )
                }
                PhotoEditorTool.Filters -> FilterControls(state, onApply)
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

        PhotoToolNavigation(state.isRaw, selectedTool, onSelectTool)
    }
}

@Composable
private fun PhotoToolNavigation(
    isRaw: Boolean,
    selectedTool: PhotoEditorTool,
    onSelectTool: (PhotoEditorTool) -> Unit,
) {
    val tools = if (isRaw) {
        listOf(PhotoEditorTool.Raw, PhotoEditorTool.Crop, PhotoEditorTool.Export)
    } else {
        listOf(
            PhotoEditorTool.Automatic,
            PhotoEditorTool.Crop,
            PhotoEditorTool.Adjust,
            PhotoEditorTool.Filters,
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        items(tools) { tool ->
            FilterChip(
                modifier = Modifier.testTag("photo-editor-tool-${tool.name.lowercase()}"),
                selected = selectedTool == tool,
                onClick = { onSelectTool(tool) },
                label = { Text(toolLabel(tool)) },
                leadingIcon = when (tool) {
                    PhotoEditorTool.Crop -> ({ Icon(GalleryIcons.Crop, contentDescription = null) })
                    PhotoEditorTool.Adjust, PhotoEditorTool.Raw -> ({ Icon(GalleryIcons.Tune, contentDescription = null) })
                    PhotoEditorTool.Filters, PhotoEditorTool.Automatic -> ({ Icon(GalleryIcons.Palette, contentDescription = null) })
                    PhotoEditorTool.Export -> null
                },
            )
        }
    }
}

@Composable
private fun toolLabel(tool: PhotoEditorTool): String = when (tool) {
    PhotoEditorTool.Automatic -> stringResource(R.string.photo_editor_automatic)
    PhotoEditorTool.Crop -> stringResource(R.string.photo_editor_crop)
    PhotoEditorTool.Adjust -> stringResource(R.string.photo_editor_adjust)
    PhotoEditorTool.Filters -> stringResource(R.string.photo_editor_filters)
    PhotoEditorTool.Raw -> stringResource(R.string.photo_editor_raw_title)
    PhotoEditorTool.Export -> stringResource(R.string.photo_editor_export_tab)
}

@Composable
private fun AutomaticControls(
    state: PhotoEditorContentState,
    onApply: (EditOperation.Tone?) -> Unit,
) {
    val source = state.originalPreview ?: state.preview
    if (source == null) {
        if (state.isAutoEnhancementAnalyzing || state.isRendering) GalleryLoadingIndicator()
        return
    }
    val suggestions = state.autoEnhancementSuggestions
    LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        item {
            AutoSuggestionCard(
                tag = "photo-editor-auto-original",
                source = source,
                label = stringResource(R.string.photo_editor_filter_original),
                tone = null,
                selected = state.tone == EditOperation.Tone() && state.selectedFilter == "none",
                enabled = true,
                onClick = { onApply(null) },
            )
        }
        item {
            AutoSuggestionCard(
                tag = "photo-editor-auto-enhance",
                source = source,
                label = stringResource(R.string.photo_editor_auto_enhance),
                tone = suggestions?.enhance,
                selected = suggestions?.enhance == state.tone && state.selectedFilter == "none",
                enabled = suggestions != null,
                onClick = { suggestions?.enhance?.let(onApply) },
            )
        }
        item {
            AutoSuggestionCard(
                tag = "photo-editor-auto-dynamic",
                source = source,
                label = stringResource(R.string.photo_editor_auto_dynamic),
                tone = suggestions?.dynamic,
                selected = suggestions?.dynamic == state.tone && state.selectedFilter == "none",
                enabled = suggestions != null,
                onClick = { suggestions?.dynamic?.let(onApply) },
            )
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
) {
    val matrix = remember(tone) {
        tone?.let { ColorFilter.colorMatrix(ColorMatrix(
            com.ugallery.core.editing.image.PhotoColorTransform.combinedValues(listOf(it)),
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
        modifier = Modifier.width(120.dp).testTag(tag),
    ) {
        Column {
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = stringResource(R.string.photo_editor_auto_preview, label),
                contentScale = ContentScale.Crop,
                colorFilter = matrix,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            Text(label, Modifier.padding(GallerySpacing.Sm), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FilterControls(state: PhotoEditorContentState, onApply: (EditOperation) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        items(
            listOf(
                "none" to R.string.photo_editor_filter_original,
                "natural" to R.string.photo_editor_filter_natural,
                "vivid" to R.string.photo_editor_filter_vivid,
                "mono" to R.string.photo_editor_filter_mono,
            ),
        ) { (name, label) ->
            FilterChip(
                selected = state.selectedFilter == name,
                onClick = { onApply(EditOperation.Filter(name)) },
                label = { Text(stringResource(label)) },
                leadingIcon = { Icon(GalleryIcons.Palette, contentDescription = null) },
            )
        }
    }
}

internal fun cropEditOperations(draft: PhotoCropDraft): List<EditOperation> = listOf(
    EditOperation.Crop(
        (draft.left * 1_000).toInt().coerceIn(0, 999),
        (draft.top * 1_000).toInt().coerceIn(0, 999),
        (draft.right * 1_000).toInt().coerceIn(1, 1_000),
        (draft.bottom * 1_000).toInt().coerceIn(1, 1_000),
    ),
    EditOperation.Straighten(draft.straightenDegrees),
)

@Composable
private fun PhotoToneSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun CropControls(
    draft: PhotoCropDraft,
    sourceAspectRatio: Float,
    onChange: (PhotoCropDraft) -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) {
        OutlinedButton(onClick = onRotate, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_rotate), maxLines = 1)
        }
        OutlinedButton(onClick = onFlip, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_flip), maxLines = 1)
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        items(cropAspectPresets) { (ratio, label) ->
            FilterChip(
                selected = draft.aspectRatio == ratio,
                onClick = { onChange(cropForAspect(draft, ratio, sourceAspectRatio)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    Text(stringResource(R.string.photo_editor_straighten), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = draft.straightenDegrees,
        onValueChange = { onChange(draft.copy(straightenDegrees = it)) },
        valueRange = -45f..45f,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) {
        TextButton(
            onClick = { onChange(PhotoCropDraft()) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.photo_editor_reset_crop), maxLines = 1) }
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_cancel_crop), maxLines = 1)
        }
        Button(onClick = onApply, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_apply_crop), maxLines = 1)
        }
    }
}

private val PhotoCropDraftSaver = listSaver<PhotoCropDraft?, Float>(
    save = { draft ->
        draft?.let { listOf(it.left, it.top, it.right, it.bottom, it.aspectRatio ?: 0f, it.straightenDegrees) }
            ?: emptyList()
    },
    restore = { values ->
        if (values.size < 6) null else PhotoCropDraft(
            left = values[0],
            top = values[1],
            right = values[2],
            bottom = values[3],
            aspectRatio = values[4].takeIf { it > 0f },
            straightenDegrees = values[5],
        )
    },
)

private val cropAspectPresets = listOf(
    null to R.string.photo_editor_crop_free,
    1f to R.string.photo_editor_crop_square,
    (4f / 3f) to R.string.photo_editor_crop_four_three,
    (3f / 4f) to R.string.photo_editor_crop_three_four,
    (16f / 9f) to R.string.photo_editor_crop_sixteen_nine,
    (9f / 16f) to R.string.photo_editor_crop_nine_sixteen,
)

/** The draft after a clockwise quarter turn, matching how the edit history rotates a Crop. */
internal fun PhotoCropDraft.rotatedClockwise(): PhotoCropDraft = copy(
    left = 1f - bottom,
    top = left,
    right = 1f - top,
    bottom = right,
    aspectRatio = aspectRatio?.let { ratio ->
        cropAspectPresets.firstNotNullOfOrNull { (preset, _) -> preset?.takeIf { abs(it * ratio - 1f) < 0.001f } }
            ?: (1f / ratio)
    },
)

/** The draft after a horizontal flip; straightening turns the other way in the mirrored frame. */
internal fun PhotoCropDraft.flippedHorizontally(): PhotoCropDraft = copy(
    left = 1f - right,
    right = 1f - left,
    straightenDegrees = -straightenDegrees,
)

internal fun cropForAspect(draft: PhotoCropDraft, ratio: Float?, sourceAspectRatio: Float): PhotoCropDraft {
    if (ratio == null) return draft.copy(aspectRatio = null)
    val normalizedRatio = ratio / sourceAspectRatio
    val centerX = (draft.left + draft.right) / 2f
    val centerY = (draft.top + draft.bottom) / 2f
    var width = draft.width
    var height = width / normalizedRatio
    if (height > draft.height) {
        height = draft.height
        width = height * normalizedRatio
    }
    return draft.copy(
        left = (centerX - width / 2f).coerceAtLeast(0f),
        right = (centerX + width / 2f).coerceAtMost(1f),
        top = (centerY - height / 2f).coerceAtLeast(0f),
        bottom = (centerY + height / 2f).coerceAtMost(1f),
        aspectRatio = ratio,
    )
}

private fun cropDraftFromState(state: PhotoEditorContentState): PhotoCropDraft {
    val crop = state.crop
    return if (crop == null) {
        PhotoCropDraft(straightenDegrees = state.straightenDegrees)
    } else PhotoCropDraft(
        left = crop.leftPermille / 1_000f,
        top = crop.topPermille / 1_000f,
        right = crop.rightPermille / 1_000f,
        bottom = crop.bottomPermille / 1_000f,
        straightenDegrees = state.straightenDegrees,
    )
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
            onChange(RawDevelopmentSettings())
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
                it.width, it.height, it.bitsPerSample,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun RawSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onFinished: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(if (range.endInclusive > 100f) value.toInt().toString() else "%.2f".format(value), style = MaterialTheme.typography.labelMedium)
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
