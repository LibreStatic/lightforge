package com.ugallery.feature.videoeditor

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.ugallery.feature.viewer.VideoViewerController
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryMonoTypography
import com.ugallery.feature.viewer.VideoViewerState

data class VideoEditorContentState(
    val currentMillis: Long = 0,
    val durationMillis: Long = 0,
    val trimStartMillis: Long = 0,
    val trimEndMillis: Long = 0,
    val speed: Float = 1f,
    val originalAudioVolume: Float = 1f,
    val selectedMusicName: String? = null,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
)

@Composable
fun VideoEditorContent(
    state: VideoEditorContentState,
    controller: VideoViewerController?,
    onBack: () -> Unit,
    onSaveCopy: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.video_editor_cancel))
            }
            Text(stringResource(R.string.video_editor_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onSaveCopy, enabled = !state.isExporting) {
                Text(stringResource(R.string.video_editor_save_copy))
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    VideoPreview(controller, Modifier.weight(1f).fillMaxSize())
                    Column(Modifier.weight(0.44f).verticalScroll(rememberScrollState())) {
                        VideoTimeline(state, onSeek, onTrimChange)
                        VideoControls(state, onSpeedChange, onOriginalVolumeChange, onChooseMusic, onRemoveMusic)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    VideoPreview(controller, Modifier.weight(1f).fillMaxWidth())
                    VideoTimeline(state, onSeek, onTrimChange)
                    VideoControls(state, onSpeedChange, onOriginalVolumeChange, onChooseMusic, onRemoveMusic)
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(controller: VideoViewerController?, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (controller == null) {
            Text(stringResource(R.string.video_editor_preview_unavailable), color = Color.White)
            return
        }
        val description = stringResource(R.string.video_editor_preview_description)
        AndroidView(
            factory = { context -> SurfaceView(context).also(controller::attachSurface) },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
        )
        DisposableEffect(controller) { onDispose { controller.attachSurface(null) } }
    }
}

@Composable
private fun VideoTimeline(
    state: VideoEditorContentState,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
) {
    val duration = state.durationMillis.coerceAtLeast(1)
    val position = state.currentMillis.coerceIn(0, duration)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMillis(position), style = GalleryMonoTypography)
            Text(formatMillis(duration), style = GalleryMonoTypography)
        }
        Slider(
            value = position.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "${formatMillis(position)} / ${formatMillis(duration)}"
            },
        )
        val trimStart = state.trimStartMillis.coerceIn(0, duration)
        val trimEnd = (state.trimEndMillis.takeIf { it > 0 } ?: duration).coerceIn(trimStart + 1, duration)
        val trimDescription = stringResource(
            R.string.video_editor_trim_description,
            formatMillis(trimStart),
            formatMillis(trimEnd),
        )
        RangeSlider(
            value = trimStart.toFloat()..trimEnd.toFloat(),
            onValueChange = { range -> onTrimChange(range.start.toLong(), range.endInclusive.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = trimDescription
            },
        )
    }
}

@Composable
private fun VideoControls(
    state: VideoEditorContentState,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        TabRow(selectedTabIndex = selectedTab) {
            listOf(
                R.string.video_editor_speed to GalleryIcons.Speed,
                R.string.video_editor_audio to GalleryIcons.Volume,
                R.string.video_editor_music to GalleryIcons.Music,
            ).forEachIndexed { index, (label, icon) ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = { Icon(icon, contentDescription = null) },
                    text = { Text(stringResource(label)) },
                )
            }
        }
        when (selectedTab) {
            0 -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0.5f to R.string.video_editor_speed_slow, 1f to R.string.video_editor_speed_normal, 2f to R.string.video_editor_speed_fast)
                    .forEach { (speed, label) ->
                        FilterChip(state.speed == speed, { onSpeedChange(speed) }, label = { Text(stringResource(label)) })
                    }
            }
            1 -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.video_editor_original_audio))
                val audioDescription = stringResource(R.string.video_editor_audio_description)
                Slider(
                    value = state.originalAudioVolume,
                    onValueChange = onOriginalVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = audioDescription },
                )
            }
            2 -> Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.selectedMusicName == null) {
                    OutlinedButton(onClick = onChooseMusic) {
                        Icon(GalleryIcons.Music, contentDescription = null)
                        Text(stringResource(R.string.video_editor_choose_music))
                    }
                } else {
                    Text(state.selectedMusicName)
                    TextButton(onClick = onRemoveMusic) { Text(stringResource(R.string.video_editor_remove_music)) }
                }
            }
        }
        state.statusMessage?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMillis(value: Long): String {
    val seconds = (value / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
