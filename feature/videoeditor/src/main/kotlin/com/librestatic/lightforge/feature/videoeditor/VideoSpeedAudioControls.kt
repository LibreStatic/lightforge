package com.librestatic.lightforge.feature.videoeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.editing.video.SlowMotionAudioMode
import com.librestatic.lightforge.core.editing.video.SlowMotionSegment
import kotlin.math.roundToInt

private val BasePlaybackSpeeds = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

/** Panel width from which the speed chips and the mark buttons may share one row (the foldable's inner screen). */
private val WideSpeedPanelWidth = 600.dp

// Fixed parts of a chip and a mark button around their measured label: padding, icon, gap and border.
private val ChipChromeWidth = 52.dp
// A label-only chip: 16dp padding on each side plus the outline.
private val PlainChipChromeWidth = 36.dp
private val MarkButtonChromeWidth = 74.dp
private val MarkButtonMinWidth = 120.dp
// The compact (short-label) buttons of the single row trade the 24dp side padding for 16dp.
private val CompactMarkButtonChromeWidth = 58.dp
private val CompactMarkButtonMinWidth = 104.dp
private val CompactMarkButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * Speed tool: a base speed for the whole clip (with the resulting length), plus optional
 * slow-motion ranges that also appear on the timeline strip.
 */
@Composable
internal fun SpeedControls(
    state: VideoEditorContentState,
    currentMillis: Long,
    onSpeedChange: (Float) -> Unit,
    onMarkIn: (Long) -> Unit,
    onMarkOut: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onUpdate: (SlowMotionSegment) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    onInterpolateSlowMotionChange: (Boolean) -> Unit = {},
) {
    val selected = state.slowMotionSegments.firstOrNull { it.id == state.selectedSlowMotionSegmentId }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= WideSpeedPanelWidth
        // Panel padding is GallerySpacing.Md on each side.
        val contentWidth = maxWidth - GallerySpacing.Md * 2
        // Measured labels, so the layout holds in every language and font scale.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelStyle = MaterialTheme.typography.labelLarge
        fun labelWidth(text: String) = with(density) { measurer.measure(text, labelStyle).size.width.toDp() }
        val chipWidths = BasePlaybackSpeeds.map { labelWidth(speedMultiplierLabel(it)) + ChipChromeWidth }
        val chipGaps = GallerySpacing.Sm * (BasePlaybackSpeeds.size - 1)
        // Equal columns need the widest label everywhere; the single row sizes each chip to its own label.
        val chipsWidth = chipWidths.max() * BasePlaybackSpeeds.size + chipGaps
        val chipsFittedWidth = chipWidths.sumOf { it.value.toDouble() }.dp + chipGaps
        val markInFull = stringResource(R.string.video_editor_mark_in)
        val markOutFull = stringResource(R.string.video_editor_mark_out)
        val markInShort = stringResource(R.string.video_editor_mark_in_short)
        val markOutShort = stringResource(R.string.video_editor_mark_out_short)
        fun buttonsWidth(first: String, second: String, compact: Boolean) = listOf(first, second).sumOf {
            val chrome = if (compact) CompactMarkButtonChromeWidth else MarkButtonChromeWidth
            (labelWidth(it) + chrome).coerceAtLeast(if (compact) CompactMarkButtonMinWidth else MarkButtonMinWidth)
                .value.toDouble()
        }.dp + GallerySpacing.Sm
        fun fitsOneRow(first: String, second: String, compact: Boolean) =
            wide && contentWidth >= chipsFittedWidth + GallerySpacing.Md + buttonsWidth(first, second, compact)
        // Short labels ("In"/"Out") keep the single row on narrower inner screens; the section title gives context.
        val fullLabelsFit = fitsOneRow(markInFull, markOutFull, compact = false)
        val singleRow = fullLabelsFit || fitsOneRow(markInShort, markOutShort, compact = true)
        val compactButtons = singleRow && !fullLabelsFit
        val markInLabel = if (singleRow && !fullLabelsFit) markInShort else markInFull
        val markOutLabel = if (singleRow && !fullLabelsFit) markOutShort else markOutFull
        val chipColumns = if (contentWidth >= chipsWidth) BasePlaybackSpeeds.size else 3
        val chipWeights = if (singleRow) chipWidths.map { it.value } else List(BasePlaybackSpeeds.size) { 1f }
        val speedChips: @Composable () -> Unit = { SpeedChipGrid(state.speed, chipColumns, chipWeights, onSpeedChange) }
        val markButtons: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                MarkButton(
                    label = markInLabel,
                    description = markInFull,
                    compact = compactButtons,
                    icon = GalleryIcons.MarkIn,
                    enabled = true,
                    onClick = { onMarkIn(currentMillis) },
                )
                MarkButton(
                    label = markOutLabel,
                    description = markOutFull,
                    compact = compactButtons,
                    icon = GalleryIcons.MarkOut,
                    enabled = state.slowMotionMarkInMillis != null,
                    onClick = { onMarkOut(currentMillis) },
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            val resultText: @Composable () -> Unit = {
                Text(
                    stringResource(R.string.video_editor_speed_result, formatVideoEditorLengthTime(state.outputLengthMillis())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val markedInText: @Composable () -> Unit = {
                state.slowMotionMarkInMillis?.let {
                    Text(
                        stringResource(R.string.video_editor_marked_in_at, formatMillis(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val interpolationToggle: @Composable () -> Unit = {
                if (state.speed < 1f || state.slowMotionSegments.isNotEmpty()) {
                    InterpolationToggle(state.interpolateSlowMotion, onInterpolateSlowMotionChange)
                }
            }
            val segmentList: @Composable (iconOnlyDelete: Boolean) -> Unit = { iconOnlyDelete ->
                if (state.slowMotionSegments.isNotEmpty()) {
                    // Delete acts on the selected range, so it sits at the end of the range list.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FlowRow(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
                            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
                        ) {
                            state.slowMotionSegments.forEachIndexed { index, segment ->
                                FilterChip(
                                    colors = editorFilterChipColors(),
                                    selected = segment.id == state.selectedSlowMotionSegmentId,
                                    onClick = { onSelect(segment.id) },
                                    label = { Text("${index + 1}: ${formatMillis(segment.startMillis)}–${formatMillis(segment.endMillis)}") },
                                    modifier = EditorChipModifier,
                                )
                            }
                        }
                        selected?.let { segment ->
                            val deleteLabel = stringResource(R.string.video_editor_delete_segment)
                            if (iconOnlyDelete) {
                                IconButton(onClick = { onDelete(segment.id) }) {
                                    Icon(GalleryIcons.Trash, contentDescription = deleteLabel)
                                }
                            } else {
                                TextButton(onClick = { onDelete(segment.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(GalleryIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(deleteLabel, modifier = Modifier.padding(start = GallerySpacing.Sm))
                                }
                            }
                        }
                    }
                }
            }
            if (singleRow) {
                // Two columns: the whole clip on the left, the slow-motion ranges on the right.
                Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                        Text(stringResource(R.string.video_editor_base_speed), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        speedChips()
                        resultText()
                        interpolationToggle()
                    }
                    // Sized by the mark buttons; more ranges wrap instead of widening the column.
                    Column(Modifier.width(IntrinsicSize.Min), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                        Text(stringResource(R.string.video_editor_slow_segments), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        markButtons()
                        markedInText()
                        segmentList(true)
                    }
                }
            } else {
                Text(stringResource(R.string.video_editor_base_speed), style = MaterialTheme.typography.titleSmall)
                speedChips()
                resultText()
                interpolationToggle()
                Text(stringResource(R.string.video_editor_slow_segments), style = MaterialTheme.typography.titleSmall)
                if (wide) {
                    // The range list continues the mark buttons' row instead of starting one of its own.
                    Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                        markButtons()
                        Box(Modifier.weight(1f)) { segmentList(false) }
                    }
                } else {
                    markButtons()
                    segmentList(false)
                }
                markedInText()
            }
            selected?.let { segment ->
                val segmentSpeeds = listOf(0.5f, 0.25f, 0.125f)
                val audioModes = listOf(
                    SlowMotionAudioMode.PreservePitch to stringResource(R.string.video_editor_audio_preserve_pitch),
                    SlowMotionAudioMode.Muted to stringResource(R.string.video_editor_audio_muted),
                    SlowMotionAudioMode.Varispeed to stringResource(R.string.video_editor_audio_varispeed),
                )
                // Equal chips per group on wide panels: the two groups share one row in proportion to what they
                // need, or each takes a full row when they do not fit together.
                val speedGroupWidth = (segmentSpeeds.maxOf { labelWidth(speedMultiplierLabel(it)) } + ChipChromeWidth) * 3 + GallerySpacing.Xs * 2
                val audioGroupWidth = (audioModes.maxOf { labelWidth(it.second) } + PlainChipChromeWidth) * 3 + GallerySpacing.Xs * 2
                val sideBySide = wide && contentWidth >= speedGroupWidth + GallerySpacing.Lg + audioGroupWidth
                val speedGroup: @Composable (Modifier) -> Unit = { groupModifier ->
                    Column(groupModifier, verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                        Text(stringResource(R.string.video_editor_segment_speed), style = MaterialTheme.typography.titleSmall)
                        ChipRow(stretch = wide) { chipModifier ->
                            segmentSpeeds.forEach { speed ->
                                FilterChip(
                                    colors = editorFilterChipColors(),
                                    selected = segment.speed == speed,
                                    leadingIcon = {
                                        Icon(GalleryIcons.SlowMotion, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                                    },
                                    onClick = { onUpdate(segment.copy(speed = speed)) },
                                    label = { Text(speedMultiplierLabel(speed), maxLines = 1, softWrap = false) },
                                    modifier = chipModifier(),
                                )
                            }
                        }
                    }
                }
                val audioGroup: @Composable (Modifier) -> Unit = { groupModifier ->
                    Column(groupModifier, verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                        Text(stringResource(R.string.video_editor_segment_audio), style = MaterialTheme.typography.titleSmall)
                        ChipRow(stretch = wide) { chipModifier ->
                            audioModes.forEach { (mode, label) ->
                                FilterChip(
                                    colors = editorFilterChipColors(),
                                    selected = segment.audioMode == mode,
                                    onClick = { onUpdate(segment.copy(audioMode = mode)) },
                                    label = { Text(label, maxLines = 1, softWrap = false) },
                                    modifier = chipModifier(),
                                )
                            }
                        }
                    }
                }
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Lg)) {
                        speedGroup(Modifier.weight(speedGroupWidth.value))
                        audioGroup(Modifier.weight(audioGroupWidth.value))
                    }
                } else {
                    speedGroup(Modifier)
                    audioGroup(Modifier)
                }
            }
        }
    }
}

/** Chips in one stretched row of equal widths, or a wrapping flow at their natural width. */
@Composable
private fun ChipRow(stretch: Boolean, content: @Composable (chipModifier: @Composable () -> Modifier) -> Unit) {
    if (stretch) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
            content { Modifier.weight(1f).heightIn(min = 48.dp) }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
            content { EditorChipModifier }
        }
    }
}

@Composable
private fun SpeedChipGrid(speed: Float, columns: Int, weights: List<Float>, onSpeedChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        BasePlaybackSpeeds.chunked(columns).forEach { rowSpeeds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                rowSpeeds.forEach { value ->
                    FilterChip(
                        colors = editorFilterChipColors(),
                        selected = speed == value,
                        onClick = { onSpeedChange(value) },
                        leadingIcon = {
                            Icon(speedIcon(value), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                        },
                        label = { Text(speedMultiplierLabel(value), maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(weights[BasePlaybackSpeeds.indexOf(value)]).heightIn(min = 48.dp),
                    )
                }
                // A short last row keeps the same column width instead of stretching its chips.
                repeat(columns - rowSpeeds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun speedIcon(speed: Float): ImageVector = when {
    speed < 1f -> GalleryIcons.SlowMotion
    speed > 1f -> GalleryIcons.FastForward
    else -> GalleryIcons.Play
}

@Composable
private fun MarkButton(
    label: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    GalleryExpressiveButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = if (compact) CompactMarkButtonPadding else ButtonDefaults.ContentPadding,
        modifier = Modifier.widthIn(min = if (compact) CompactMarkButtonMinWidth else MarkButtonMinWidth).heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = GallerySpacing.Sm))
    }
}

/** One toggleable row: the whole row is the switch's touch target. */
@Composable
private fun InterpolationToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
    ) {
        Icon(GalleryIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.video_editor_interpolate_frames), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.video_editor_interpolate_frames_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Audio tool: one control for the clip's own sound, with a mute toggle and a readable percentage. */
@Composable
internal fun AudioControls(
    state: VideoEditorContentState,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = state.originalAudioVolume <= 0f
    val audioDescription = stringResource(R.string.video_editor_audio_description)
    Column(modifier.fillMaxSize().padding(GallerySpacing.Md)) {
        VolumeCard(
            title = stringResource(R.string.video_editor_original_audio),
            volume = state.originalAudioVolume,
            sliderDescription = audioDescription,
            muteDescription = stringResource(
                if (muted) R.string.video_editor_unmute_original else R.string.video_editor_mute_original,
            ),
            muted = muted,
            onVolumeChange = onVolumeChange,
            onToggleMute = { onVolumeChange(if (muted) 1f else 0f) },
        )
    }
}

/** Music tool: choose a track, then manage it as a card (name, volume, replace or remove). */
@Composable
internal fun MusicControls(
    state: VideoEditorContentState,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        if (state.selectedMusicName == null) {
            FilledTonalButton(onClick = onChooseMusic, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(GalleryIcons.Music, contentDescription = null)
                Text(stringResource(R.string.video_editor_choose_music), modifier = Modifier.padding(start = GallerySpacing.Sm))
            }
        } else {
            val musicVolumeDescription = stringResource(R.string.video_editor_music_volume)
            VolumeCard(
                title = state.selectedMusicName,
                volume = state.musicVolume,
                sliderDescription = musicVolumeDescription,
                muteDescription = null,
                muted = false,
                onVolumeChange = onMusicVolumeChange,
                onToggleMute = {},
                leadingIcon = GalleryIcons.Music,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                OutlinedButton(onClick = onChooseMusic, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.video_editor_replace_music))
                }
                TextButton(onClick = onRemoveMusic, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.video_editor_remove_music))
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(
    title: String,
    volume: Float,
    sliderDescription: String,
    muteDescription: String?,
    muted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(GallerySpacing.Md), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                leadingIcon?.let { Icon(it, contentDescription = null) }
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (muteDescription != null) {
                    IconButton(onClick = onToggleMute) {
                        Icon(
                            if (muted) GalleryIcons.VolumeOff else GalleryIcons.Volume,
                            contentDescription = muteDescription,
                        )
                    }
                }
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f).semantics { contentDescription = sliderDescription },
                )
            }
        }
    }
}
