package com.ugallery.feature.videoeditor

import android.view.SurfaceView
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.ugallery.feature.viewer.VideoViewerController
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryMonoTypography
import com.ugallery.feature.viewer.VideoViewerState
import com.ugallery.core.editing.video.BuiltInLook
import com.ugallery.core.editing.video.CubeLut
import com.ugallery.core.editing.video.CustomLutOption
import com.ugallery.core.editing.video.HueBand
import com.ugallery.core.editing.video.LogInputProfile
import com.ugallery.core.editing.video.LutReference
import com.ugallery.core.editing.video.VideoColorGrade
import com.ugallery.core.editing.video.VideoColorGradeEffects
import com.ugallery.core.editing.video.VideoOutputQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    val colorGrade: VideoColorGrade = VideoColorGrade(),
    val customLuts: List<CustomLutOption> = emptyList(),
    val activeCustomLut: CubeLut? = null,
    val outputQuality: VideoOutputQuality = VideoOutputQuality.H264Compatible,
    val logDetectionMessage: String? = null,
    val isHevcMain10Available: Boolean = false,
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
    onColorGradeChange: (VideoColorGrade) -> Unit = {},
    onOutputQualityChange: (VideoOutputQuality) -> Unit = {},
    onImportLut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(controller, state.colorGrade, state.activeCustomLut) {
        delay(24)
        val effects = withContext(Dispatchers.Default) {
            VideoColorGradeEffects.create(state.colorGrade, state.activeCustomLut)
        }
        controller?.setVideoEffects(effects)
    }
    Scaffold(modifier = modifier, topBar = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.video_editor_cancel))
            }
            Text(
                stringResource(R.string.video_editor_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSaveCopy, enabled = !state.isExporting) {
                Text(stringResource(R.string.video_editor_save_copy))
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val previewWeight = if (maxWidth >= 600.dp) 1.7f else 1.25f
            Column(Modifier.fillMaxSize()) {
                VideoPreview(
                    controller,
                    Modifier.weight(previewWeight).fillMaxWidth(),
                )
                VideoTimeline(state, onSeek, onTrimChange)
                VideoControls(
                    state, onSpeedChange, onOriginalVolumeChange, onChooseMusic, onRemoveMusic,
                    onColorGradeChange, onOutputQualityChange, onImportLut, Modifier.weight(1f),
                )
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
        val viewerState by controller.state.collectAsState()
        key(controller) {
            AndroidView(
                factory = { context -> SurfaceView(context).also(controller::attachSurface) },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
        }
        when (val current = viewerState) {
            is VideoViewerState.Ready -> FilledIconButton(
                onClick = { if (current.isPlaying) controller.pause() else controller.play() },
            ) {
                Icon(
                    if (current.isPlaying) GalleryIcons.Pause else GalleryIcons.Play,
                    contentDescription = stringResource(
                        if (current.isPlaying) R.string.video_editor_pause else R.string.video_editor_play,
                    ),
                )
            }
            is VideoViewerState.Failure -> Text(
                stringResource(R.string.video_editor_preview_failed),
                color = MaterialTheme.colorScheme.error,
            )
            VideoViewerState.Idle, is VideoViewerState.Loading -> CircularProgressIndicator()
            VideoViewerState.Released -> Unit
        }
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
    onColorGradeChange: (VideoColorGrade) -> Unit,
    onOutputQualityChange: (VideoOutputQuality) -> Unit,
    onImportLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            listOf(
                R.string.video_editor_speed to GalleryIcons.Speed,
                R.string.video_editor_audio to GalleryIcons.Volume,
                R.string.video_editor_music to GalleryIcons.Music,
                R.string.video_editor_color to GalleryIcons.Palette,
                R.string.video_editor_export to GalleryIcons.Edit,
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
            3 -> ColorControls(state, onColorGradeChange, onImportLut, Modifier.weight(1f))
            4 -> ExportControls(state.outputQuality, state.isHevcMain10Available, onOutputQualityChange)
        }
        state.statusMessage?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColorControls(
    state: VideoEditorContentState,
    onChange: (VideoColorGrade) -> Unit,
    onImportLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grade = state.colorGrade
    var palette by remember { mutableIntStateOf(0) }
    var selectedBand by remember { mutableIntStateOf(0) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.logDetectionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilterChip(
                selected = grade.bypass,
                onClick = { onChange(grade.copy(bypass = !grade.bypass)) },
                label = { Text(stringResource(R.string.video_editor_bypass_grade)) },
            )
            TextButton(onClick = {
                onChange(VideoColorGrade(
                    inputProfile = grade.inputProfile,
                    profileWasAutoDetected = grade.profileWasAutoDetected,
                ))
            }) { Text(stringResource(R.string.video_editor_reset_grade)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                R.string.video_editor_camera to 0,
                R.string.video_editor_primaries to 1,
                R.string.video_editor_log_wheels to 2,
                R.string.video_editor_color_bands to 3,
                R.string.video_editor_luts to 4,
            ).forEach { (label, index) ->
                FilterChip(selected = palette == index, onClick = { palette = index }, label = { Text(stringResource(label)) })
            }
        }
        when (palette) {
            0 -> {
                Text(stringResource(R.string.video_editor_input_profile), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogInputProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = grade.inputProfile == profile,
                            onClick = { onChange(grade.copy(inputProfile = profile, profileWasAutoDetected = false)) },
                            label = { Text(profile.displayName) },
                        )
                    }
                }
            }
            1 -> {
                GradeSlider(stringResource(R.string.video_editor_exposure), grade.exposureEv, -5f..5f) { onChange(grade.copy(exposureEv = it)) }
                GradeSlider(stringResource(R.string.video_editor_temperature), grade.temperature, -1f..1f) { onChange(grade.copy(temperature = it)) }
                GradeSlider(stringResource(R.string.video_editor_tint), grade.tint, -1f..1f) { onChange(grade.copy(tint = it)) }
                GradeSlider(stringResource(R.string.video_editor_contrast), grade.contrast, -1f..1f) { onChange(grade.copy(contrast = it)) }
                GradeSlider(stringResource(R.string.video_editor_pivot), grade.pivot, 0.05f..0.95f) { onChange(grade.copy(pivot = it)) }
                GradeSlider(stringResource(R.string.video_editor_saturation), grade.saturation, -1f..1f) { onChange(grade.copy(saturation = it)) }
            }
            2 -> {
                listOf(
                    R.string.video_editor_shadows to grade.logWheels.shadows,
                    R.string.video_editor_midtones to grade.logWheels.midtones,
                    R.string.video_editor_highlights to grade.logWheels.highlights,
                ).forEachIndexed { index, (label, wheel) ->
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                    listOf(
                        R.string.video_editor_level to wheel.level,
                        R.string.video_editor_band_red to wheel.red,
                        R.string.video_editor_band_green to wheel.green,
                        R.string.video_editor_band_blue to wheel.blue,
                    ).forEachIndexed { channel, (channelLabel, channelValue) ->
                        GradeSlider(stringResource(channelLabel), channelValue, -1f..1f) { value ->
                            val changedWheel = when (channel) {
                                0 -> wheel.copy(level = value)
                                1 -> wheel.copy(red = value)
                                2 -> wheel.copy(green = value)
                                else -> wheel.copy(blue = value)
                            }
                            val updated = when (index) {
                                0 -> grade.logWheels.copy(shadows = changedWheel)
                                1 -> grade.logWheels.copy(midtones = changedWheel)
                                else -> grade.logWheels.copy(highlights = changedWheel)
                            }
                            onChange(grade.copy(logWheels = updated))
                        }
                    }
                }
            }
            3 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HueBand.entries.forEachIndexed { index, band ->
                        FilterChip(selected = selectedBand == index, onClick = { selectedBand = index }, label = { Text(stringResource(band.labelResource())) })
                    }
                }
                val adjustment = grade.hueBands.first { it.band == HueBand.entries[selectedBand] }
                GradeSlider(stringResource(R.string.video_editor_hue), adjustment.hueShiftDegrees, -45f..45f) { value ->
                    onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(hueShiftDegrees = value) else it }))
                }
                GradeSlider(stringResource(R.string.video_editor_saturation), adjustment.saturation, -1f..1f) { value ->
                    onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(saturation = value) else it }))
                }
                GradeSlider(stringResource(R.string.video_editor_luminance), adjustment.luminance, -1f..1f) { value ->
                    onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(luminance = value) else it }))
                }
            }
            4 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuiltInLook.entries.forEach { look ->
                        FilterChip(
                            selected = grade.lut.builtIn == look && grade.lut.customId == null,
                            onClick = { onChange(grade.copy(lut = LutReference(builtIn = look, intensity = grade.lut.intensity))) },
                            label = { Text(stringResource(look.labelResource())) },
                        )
                    }
                    state.customLuts.forEach { lut ->
                        FilterChip(
                            selected = grade.lut.customId == lut.id,
                            onClick = { onChange(grade.copy(lut = LutReference(customId = lut.id, intensity = grade.lut.intensity))) },
                            label = { Text(lut.displayName) },
                        )
                    }
                }
                GradeSlider(stringResource(R.string.video_editor_lut_intensity), grade.lut.intensity, 0f..1f) {
                    onChange(grade.copy(lut = grade.lut.copy(intensity = it)))
                }
                OutlinedButton(onClick = onImportLut) { Text(stringResource(R.string.video_editor_import_lut)) }
            }
        }
    }
}

@Composable
private fun GradeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("%.2f".format(value), style = GalleryMonoTypography)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ExportControls(selected: VideoOutputQuality, isHevcMain10Available: Boolean, onSelected: (VideoOutputQuality) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.video_editor_output_quality), style = MaterialTheme.typography.titleSmall)
        VideoOutputQuality.entries.forEach { quality ->
            FilterChip(
                selected = selected == quality,
                onClick = { onSelected(quality) },
                enabled = quality != VideoOutputQuality.HevcMain10 || isHevcMain10Available,
                label = { Text(stringResource(if (quality == VideoOutputQuality.HevcMain10) R.string.video_editor_hevc_10bit else R.string.video_editor_h264)) },
            )
        }
        if (!isHevcMain10Available) Text(
            stringResource(R.string.video_editor_hevc_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatMillis(value: Long): String {
    val seconds = (value / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@StringRes
private fun HueBand.labelResource(): Int = when (this) {
    HueBand.Red -> R.string.video_editor_band_red
    HueBand.Orange -> R.string.video_editor_band_orange
    HueBand.Yellow -> R.string.video_editor_band_yellow
    HueBand.Green -> R.string.video_editor_band_green
    HueBand.Cyan -> R.string.video_editor_band_cyan
    HueBand.Blue -> R.string.video_editor_band_blue
    HueBand.Purple -> R.string.video_editor_band_purple
    HueBand.Magenta -> R.string.video_editor_band_magenta
}

@StringRes
private fun BuiltInLook.labelResource(): Int = when (this) {
    BuiltInLook.None -> R.string.video_editor_look_none
    BuiltInLook.Clean709 -> R.string.video_editor_look_clean
    BuiltInLook.WarmFilm -> R.string.video_editor_look_warm
    BuiltInLook.CoolFilm -> R.string.video_editor_look_cool
    BuiltInLook.Bleach -> R.string.video_editor_look_bleach
    BuiltInLook.TealOrange -> R.string.video_editor_look_teal_orange
    BuiltInLook.Monochrome -> R.string.video_editor_look_monochrome
}
