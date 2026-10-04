package com.librestatic.lightforge.feature.videoeditor

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.EditorColorSwatch
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.editing.video.BuiltInLook
import com.librestatic.lightforge.core.editing.video.HueBand
import com.librestatic.lightforge.core.editing.video.LogInputProfile
import com.librestatic.lightforge.core.editing.video.LogWheel
import com.librestatic.lightforge.core.editing.video.LogWheels
import com.librestatic.lightforge.core.editing.video.LutReference
import com.librestatic.lightforge.core.editing.video.VideoColorGrade
import com.librestatic.lightforge.core.editing.video.VideoColorGradeEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LookTileWidth = 84.dp
private val LookThumbnailHeight = 56.dp
private const val LookThumbnailWidthPx = 84
private const val LookThumbnailHeightPx = 56

/**
 * The Color tool: creative looks first, then the everyday sliders grouped as Light and Color, and a
 * single entry into the advanced controls (input profile, log wheels, color bands) so they are
 * available without crowding the main view.
 */
@Composable
internal fun ColorControls(
    state: VideoEditorContentState,
    thumbnailFrame: Bitmap?,
    onChange: (VideoColorGrade) -> Unit,
    onImportLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    if (showAdvanced) {
        AdvancedColorControls(state, onChange, onBack = { showAdvanced = false }, modifier)
    } else {
        BasicColorControls(state, thumbnailFrame, onChange, onImportLut, onOpenAdvanced = { showAdvanced = true }, modifier)
    }
}

@Composable
private fun BasicColorControls(
    state: VideoEditorContentState,
    thumbnailFrame: Bitmap?,
    onChange: (VideoColorGrade) -> Unit,
    onImportLut: () -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier,
) {
    val grade = state.colorGrade
    val lookThumbnails = rememberLookThumbnails(thumbnailFrame)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WideColorControlsBreakpoint
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(GallerySpacing.Md),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
        ) {
            Text(stringResource(R.string.video_editor_looks), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                BuiltInLook.entries.forEach { look ->
                    LookTile(
                        label = stringResource(look.labelResource()),
                        thumbnail = lookThumbnails[look],
                        icon = null,
                        selected = grade.lut.builtIn == look && grade.lut.customId == null,
                        onClick = { onChange(grade.copy(lut = LutReference(builtIn = look, intensity = grade.lut.intensity))) },
                    )
                }
                state.customLuts.forEach { lut ->
                    LookTile(
                        label = lut.displayName,
                        thumbnail = null,
                        icon = GalleryIcons.Palette,
                        selected = grade.lut.customId == lut.id,
                        onClick = { onChange(grade.copy(lut = LutReference(customId = lut.id, intensity = grade.lut.intensity))) },
                    )
                }
                LookTile(
                    label = stringResource(R.string.video_editor_import_lut),
                    thumbnail = null,
                    icon = GalleryIcons.Plus,
                    selected = false,
                    onClick = onImportLut,
                )
            }
            if (grade.lut.builtIn != BuiltInLook.None || grade.lut.customId != null) {
                GradeSlider(stringResource(R.string.video_editor_look_intensity), grade.lut.intensity, 0f..1f, sliderIcon(GalleryIcons.AdjustIntensity)) {
                    onChange(grade.copy(lut = grade.lut.copy(intensity = it)))
                }
            }

            ColorGroup(
                title = R.string.video_editor_light,
                canReset = grade.exposureEv != 0f || grade.contrast != 0f || grade.highlights != 0f || grade.shadows != 0f,
                onReset = { onChange(grade.copy(exposureEv = 0f, contrast = 0f, highlights = 0f, shadows = 0f)) },
                wide = wide,
                first = {
                    GradeSlider(stringResource(R.string.video_editor_exposure), grade.exposureEv, -5f..5f, sliderIcon(GalleryIcons.AdjustExposure)) { onChange(grade.copy(exposureEv = it)) }
                    GradeSlider(stringResource(R.string.video_editor_contrast), grade.contrast, -1f..1f, sliderIcon(GalleryIcons.AdjustContrast)) { onChange(grade.copy(contrast = it)) }
                },
                second = {
                    GradeSlider(stringResource(R.string.video_editor_highlights), grade.highlights, -1f..1f, sliderIcon(GalleryIcons.AdjustHighlights)) { onChange(grade.copy(highlights = it)) }
                    GradeSlider(stringResource(R.string.video_editor_shadows), grade.shadows, -1f..1f, sliderIcon(GalleryIcons.AdjustShadows)) { onChange(grade.copy(shadows = it)) }
                },
            )
            ColorGroup(
                title = R.string.video_editor_color,
                canReset = grade.temperature != 0f || grade.tint != 0f || grade.saturation != 0f || grade.vibrance != 0f,
                onReset = { onChange(grade.copy(temperature = 0f, tint = 0f, saturation = 0f, vibrance = 0f)) },
                wide = wide,
                first = {
                    GradeSlider(stringResource(R.string.video_editor_temperature), grade.temperature, -1f..1f, sliderIcon(GalleryIcons.AdjustTemperature)) { onChange(grade.copy(temperature = it)) }
                    GradeSlider(stringResource(R.string.video_editor_tint), grade.tint, -1f..1f, sliderIcon(GalleryIcons.AdjustTint)) { onChange(grade.copy(tint = it)) }
                },
                second = {
                    GradeSlider(stringResource(R.string.video_editor_saturation), grade.saturation, -1f..1f, sliderIcon(GalleryIcons.AdjustSaturation)) { onChange(grade.copy(saturation = it)) }
                    GradeSlider(stringResource(R.string.video_editor_vibrance), grade.vibrance, -1f..1f, sliderIcon(GalleryIcons.AutoAwesome)) { onChange(grade.copy(vibrance = it)) }
                },
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    colors = editorFilterChipColors(),
                    selected = grade.bypass,
                    onClick = { onChange(grade.copy(bypass = !grade.bypass)) },
                    label = { Text(stringResource(R.string.video_editor_bypass_grade)) },
                    modifier = EditorChipModifier,
                )
                TextButton(onClick = { onChange(resetGrade(grade)) }, enabled = grade.hasChanges || grade.bypass) {
                    Text(stringResource(R.string.video_editor_reset_grade))
                }
            }

            Surface(
                onClick = onOpenAdvanced,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.padding(GallerySpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
                ) {
                    Icon(GalleryIcons.Tune, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.video_editor_advanced_color), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.video_editor_advanced_color_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(GalleryIcons.ChevronForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ColorGroup(
    @StringRes title: Int,
    canReset: Boolean,
    onReset: () -> Unit,
    wide: Boolean,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(GallerySpacing.Md), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onReset, enabled = canReset) { Text(stringResource(R.string.video_editor_reset)) }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Lg)) {
                    Column(Modifier.weight(1f), content = first)
                    Column(Modifier.weight(1f), content = second)
                }
            } else {
                Column(content = first)
                Column(content = second)
            }
        }
    }
}

@Composable
private fun LookTile(
    label: String,
    thumbnail: ImageBitmap?,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(LookTileWidth)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(LookThumbnailHeight),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            // The selected look is outlined, and its label is stronger, so it never relies on color alone.
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            when {
                thumbnail != null -> Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                icon != null -> Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Renders the current frame through every built-in look, so each tile previews what it will do. */
@Composable
private fun rememberLookThumbnails(frame: Bitmap?): Map<BuiltInLook, ImageBitmap> {
    val thumbnails by produceState(emptyMap<BuiltInLook, ImageBitmap>(), frame) {
        val source = frame
        value = if (source == null || source.isRecycled) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val small = Bitmap.createScaledBitmap(source, LookThumbnailWidthPx, LookThumbnailHeightPx, true)
                    val pixels = IntArray(LookThumbnailWidthPx * LookThumbnailHeightPx)
                    small.getPixels(pixels, 0, LookThumbnailWidthPx, 0, 0, LookThumbnailWidthPx, LookThumbnailHeightPx)
                    BuiltInLook.entries.associateWith { look ->
                        val graded = VideoColorGradeEffects.gradePixels(pixels, VideoColorGrade(lut = LutReference(builtIn = look)))
                        Bitmap.createBitmap(graded, LookThumbnailWidthPx, LookThumbnailHeightPx, Bitmap.Config.ARGB_8888)
                            .asImageBitmap()
                    }
                }.getOrDefault(emptyMap())
            }
        }
    }
    return thumbnails
}

private fun resetGrade(grade: VideoColorGrade) = VideoColorGrade(
    inputProfile = grade.inputProfile,
    profileWasAutoDetected = grade.profileWasAutoDetected,
)

@Composable
private fun AdvancedColorControls(
    state: VideoEditorContentState,
    onChange: (VideoColorGrade) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val grade = state.colorGrade
    var section by rememberSaveable { mutableIntStateOf(0) }
    var selectedBand by rememberSaveable { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wideWheels = maxWidth >= WideLogWheelsBreakpoint
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(GallerySpacing.Md),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.video_editor_back_to_color))
                }
                Text(stringResource(R.string.video_editor_advanced_color), style = MaterialTheme.typography.titleMedium)
            }
            // Only a detected profile is worth a notice, and only while it is still the selected one.
            state.logDetectionMessage?.takeIf { grade.profileWasAutoDetected }?.let { message ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        Modifier.padding(GallerySpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
                    ) {
                        Icon(GalleryIcons.Warning, contentDescription = null)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                stringResource(
                    if (grade.inputProfile.isOpenCineLog) R.string.video_editor_log_gamut_converted_note
                    else R.string.video_editor_log_curve_only_note,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sections = listOf(
                R.string.video_editor_advanced_profile,
                R.string.video_editor_advanced_wheels,
                R.string.video_editor_advanced_bands,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sections.forEachIndexed { index, label ->
                    SegmentedButton(
                        colors = editorSegmentedButtonColors(),
                        selected = section == index,
                        onClick = { section = index },
                        shape = SegmentedButtonDefaults.itemShape(index, sections.size),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            when (section) {
                0 -> {
                    ProfilePicker(grade.inputProfile) { profile ->
                        onChange(grade.copy(inputProfile = profile, profileWasAutoDetected = false))
                    }
                    GradeSlider(stringResource(R.string.video_editor_pivot), grade.pivot, 0.05f..0.95f, sliderIcon(GalleryIcons.AdjustPivot)) {
                        onChange(grade.copy(pivot = it))
                    }
                }
                1 -> {
                    TextButton(
                        onClick = { onChange(grade.copy(logWheels = LogWheels())) },
                        enabled = grade.logWheels != LogWheels(),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.video_editor_reset)) }
                    val wheel: @Composable (Modifier, Int, LogWheel, (LogWheel) -> Unit) -> Unit =
                        { wheelModifier, label, value, update ->
                            val icon = when (label) {
                                R.string.video_editor_shadows -> GalleryIcons.AdjustShadows
                                R.string.video_editor_midtones -> GalleryIcons.AdjustMidtones
                                else -> GalleryIcons.AdjustHighlights
                            }
                            LogWheelControls(label = label, icon = icon, wheel = value, onChange = update, modifier = wheelModifier)
                        }
                    val shadows: @Composable (Modifier) -> Unit = { m ->
                        wheel(m, R.string.video_editor_shadows, grade.logWheels.shadows) {
                            onChange(grade.copy(logWheels = grade.logWheels.copy(shadows = it)))
                        }
                    }
                    val midtones: @Composable (Modifier) -> Unit = { m ->
                        wheel(m, R.string.video_editor_midtones, grade.logWheels.midtones) {
                            onChange(grade.copy(logWheels = grade.logWheels.copy(midtones = it)))
                        }
                    }
                    val highlights: @Composable (Modifier) -> Unit = { m ->
                        wheel(m, R.string.video_editor_highlights, grade.logWheels.highlights) {
                            onChange(grade.copy(logWheels = grade.logWheels.copy(highlights = it)))
                        }
                    }
                    if (wideWheels) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                            shadows(Modifier.weight(1f))
                            midtones(Modifier.weight(1f))
                            highlights(Modifier.weight(1f))
                        }
                    } else {
                        shadows(Modifier.fillMaxWidth())
                        midtones(Modifier.fillMaxWidth())
                        highlights(Modifier.fillMaxWidth())
                    }
                }
                else -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HueBand.entries.forEachIndexed { index, band ->
                            FilterChip(
                                colors = editorFilterChipColors(),
                                selected = selectedBand == index,
                                onClick = { selectedBand = index },
                                label = { Text(stringResource(band.labelResource())) },
                                leadingIcon = { EditorColorSwatch(band.indicatorColor()) },
                                modifier = EditorChipModifier,
                            )
                        }
                    }
                    val adjustment = grade.hueBands.first { it.band == HueBand.entries[selectedBand] }
                    GradeSlider(stringResource(R.string.video_editor_hue), adjustment.hueShiftDegrees, -45f..45f, sliderIcon(GalleryIcons.AdjustHue)) { value ->
                        onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(hueShiftDegrees = value) else it }))
                    }
                    GradeSlider(stringResource(R.string.video_editor_saturation), adjustment.saturation, -1f..1f, sliderIcon(GalleryIcons.AdjustSaturation)) { value ->
                        onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(saturation = value) else it }))
                    }
                    GradeSlider(stringResource(R.string.video_editor_luminance), adjustment.luminance, -1f..1f, sliderIcon(GalleryIcons.AdjustLuminance)) { value ->
                        onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(luminance = value) else it }))
                    }
                }
            }
        }
    }
}

/** The camera input curve as one dropdown instead of a wall of chips. */
@Composable
private fun ProfilePicker(selected: LogInputProfile, onSelect: (LogInputProfile) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Text(stringResource(R.string.video_editor_input_profile), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(selected.labelResource()), modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LogInputProfile.entries.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(stringResource(profile.labelResource())) },
                        onClick = {
                            expanded = false
                            onSelect(profile)
                        },
                    )
                }
            }
        }
    }
}
