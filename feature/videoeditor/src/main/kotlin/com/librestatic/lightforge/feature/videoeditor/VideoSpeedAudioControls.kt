package com.librestatic.lightforge.feature.videoeditor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.editing.video.SlowMotionAudioMode
import com.librestatic.lightforge.core.editing.video.SlowMotionSegment
import kotlin.math.roundToInt

private val BasePlaybackSpeeds = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

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
) {
    val selected = state.slowMotionSegments.firstOrNull { it.id == state.selectedSlowMotionSegmentId }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        Text(stringResource(R.string.video_editor_base_speed), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            BasePlaybackSpeeds.forEach { speed ->
                FilterChip(
                    selected = state.speed == speed,
                    onClick = { onSpeedChange(speed) },
                    label = { Text("${speed}×") },
                    modifier = EditorChipModifier,
                )
            }
        }
        // Slow-motion segments change the length in ways this estimate ignores, so only show it
        // when the base speed is the whole story.
        if (state.slowMotionSegments.isEmpty()) {
            val trimEnd = state.trimEndMillis.takeIf { it > state.trimStartMillis } ?: state.durationMillis
            val resulting = ((trimEnd - state.trimStartMillis) / state.speed).toLong().coerceAtLeast(0)
            Text(
                stringResource(R.string.video_editor_speed_result, formatVideoEditorShortTime(resulting)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(stringResource(R.string.video_editor_slow_segments), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            GalleryExpressiveButton(
                onClick = { onMarkIn(currentMillis) },
                modifier = Modifier.widthIn(min = 104.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.video_editor_mark_in)) }
            GalleryExpressiveButton(
                onClick = { onMarkOut(currentMillis) },
                enabled = state.slowMotionMarkInMillis != null,
                modifier = Modifier.widthIn(min = 104.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.video_editor_mark_out)) }
        }
        state.slowMotionMarkInMillis?.let {
            Text(stringResource(R.string.video_editor_marked_in_at, formatMillis(it)))
        }
        if (state.slowMotionSegments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
                state.slowMotionSegments.forEachIndexed { index, segment ->
                    FilterChip(
                        selected = segment.id == state.selectedSlowMotionSegmentId,
                        onClick = { onSelect(segment.id) },
                        label = { Text("${index + 1}: ${formatMillis(segment.startMillis)}–${formatMillis(segment.endMillis)}") },
                        modifier = EditorChipModifier,
                    )
                }
            }
        }
        selected?.let { segment ->
            Text(stringResource(R.string.video_editor_segment_speed), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
                listOf(0.5f, 0.25f, 0.125f).forEach { speed ->
                    FilterChip(
                        selected = segment.speed == speed,
                        onClick = { onUpdate(segment.copy(speed = speed)) },
                        label = { Text("${speed}×") },
                        modifier = EditorChipModifier,
                    )
                }
            }
            Text(stringResource(R.string.video_editor_segment_audio), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
                listOf(
                    SlowMotionAudioMode.PreservePitch to R.string.video_editor_audio_preserve_pitch,
                    SlowMotionAudioMode.Muted to R.string.video_editor_audio_muted,
                    SlowMotionAudioMode.Varispeed to R.string.video_editor_audio_varispeed,
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = segment.audioMode == mode,
                        onClick = { onUpdate(segment.copy(audioMode = mode)) },
                        label = { Text(stringResource(label)) },
                        modifier = EditorChipModifier,
                    )
                }
            }
            TextButton(onClick = { onDelete(segment.id) }) {
                Text(stringResource(R.string.video_editor_delete_segment))
            }
        }
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
