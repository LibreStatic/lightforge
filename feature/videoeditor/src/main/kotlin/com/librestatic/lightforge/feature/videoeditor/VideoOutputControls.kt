package com.librestatic.lightforge.feature.videoeditor

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.editing.video.VideoAspectMode
import com.librestatic.lightforge.core.editing.video.VideoAspectOverride
import com.librestatic.lightforge.core.editing.video.VideoOutputAudio
import com.librestatic.lightforge.core.editing.video.VideoOutputBitrate
import com.librestatic.lightforge.core.editing.video.VideoOutputCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputFrameRate
import com.librestatic.lightforge.core.editing.video.VideoOutputResolution
import com.librestatic.lightforge.core.editing.video.VideoOutputSettings
import com.librestatic.lightforge.core.editing.video.VideoQualityPreset

internal const val VideoOutputPanelTag = "video-output-panel"
private val AacBitrates = listOf(64_000, 96_000, 128_000, 192_000, 256_000)
private const val DefaultAacBitrate = 128_000
private const val DefaultTargetBitrate = 4_000_000

/**
 * Output tool: converter-style export options (codec, quality, size, frame rate, forced aspect
 * ratio, audio) with a source/result summary on top. Changes are recipe edits like any other tool,
 * so they are undoable, mark the draft as changed and show in the preview (forced aspect).
 */
@Composable
internal fun VideoOutputControls(
    state: VideoEditorContentState,
    onChange: (VideoOutputSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.output
    val estimate = state.outputEstimate()
    val source = state.outputSource
    val editedBase = source?.takeIf { it.width > 0 && it.height > 0 }?.let { editedSourceSize(it, state.geometry) }
    val aspectBase = editedBase?.let { aspectAdjustedSize(it, settings.aspect) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(GallerySpacing.Md)
            .testTag(VideoOutputPanelTag),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        VideoOutputSummaryCard(state, estimate, onReset = { onChange(VideoOutputSettings()) })

        SectionTitle(R.string.video_editor_output_format)
        val unsupported = VideoOutputCodec.entries.filter { codec ->
            codec != VideoOutputCodec.Auto && state.supportedOutputCodecs?.contains(codec) == false
        }
        ChipRow {
            VideoOutputCodec.entries.forEach { codec ->
                OutputChip(
                    selected = settings.codec == codec,
                    enabled = codec !in unsupported,
                    label = if (codec == VideoOutputCodec.Auto) stringResource(R.string.video_editor_output_auto) else codec.displayName(),
                    onClick = { onChange(settings.copy(codec = codec)) },
                )
            }
        }
        if (unsupported.isNotEmpty()) {
            Hint(stringResource(R.string.video_editor_output_codec_unsupported, unsupported.joinToString { it.displayName() }))
        }
        if (settings.codec == VideoOutputCodec.Auto) Hint(stringResource(R.string.video_editor_output_codec_auto_hint))

        SectionTitle(R.string.video_editor_output_quality_section)
        val customQuality = settings.quality is VideoOutputBitrate.Target
        ChipRow {
            VideoQualityPreset.entries.forEach { preset ->
                OutputChip(
                    selected = settings.quality == VideoOutputBitrate.Preset(preset),
                    label = stringResource(preset.label()),
                    onClick = { onChange(settings.copy(quality = VideoOutputBitrate.Preset(preset))) },
                )
            }
            OutputChip(
                selected = customQuality,
                label = stringResource(R.string.video_editor_output_custom),
                onClick = {
                    if (!customQuality) {
                        val start = (estimate.videoBitrate ?: DefaultTargetBitrate).coerceIn(100_000, 200_000_000)
                        onChange(settings.copy(quality = VideoOutputBitrate.Target(start)))
                    }
                },
            )
        }
        (settings.quality as? VideoOutputBitrate.Target)?.let { target ->
            TargetBitrateField(target.bitsPerSecond) { onChange(settings.copy(quality = VideoOutputBitrate.Target(it))) }
        }

        SectionTitle(R.string.video_editor_output_resolution)
        val customSize = settings.resolution is VideoOutputResolution.Custom
        val anyLargerSize = aspectBase != null && VideoOutputResolution.Common.any { it.shortSide > aspectBase.shortSide }
        ChipRow {
            OutputChip(
                selected = settings.resolution == VideoOutputResolution.Original,
                label = stringResource(R.string.video_editor_output_original),
                onClick = { onChange(settings.copy(resolution = VideoOutputResolution.Original)) },
            )
            VideoOutputResolution.Common.forEach { option ->
                // Scaling never upscales, so sizes above the (aspect-adjusted) source do nothing.
                val reachable = aspectBase == null || option.shortSide <= aspectBase.shortSide
                OutputChip(
                    selected = settings.resolution == option,
                    enabled = reachable,
                    label = "${option.shortSide}p",
                    onClick = { onChange(settings.copy(resolution = option)) },
                )
            }
            OutputChip(
                selected = customSize,
                label = stringResource(R.string.video_editor_output_custom),
                onClick = {
                    if (!customSize) {
                        val start = estimate.size ?: VideoPixelSize(1920, 1080)
                        onChange(settings.copy(resolution = VideoOutputResolution.Custom(start.width, start.height)))
                    }
                },
            )
        }
        (settings.resolution as? VideoOutputResolution.Custom)?.let { custom ->
            PairFields(
                first = custom.width,
                second = custom.height,
                firstLabel = R.string.video_editor_output_width,
                secondLabel = R.string.video_editor_output_height,
                range = 16..8192,
                error = R.string.video_editor_output_size_invalid,
                testTag = "video-output-custom-size",
            ) { w, h -> onChange(settings.copy(resolution = VideoOutputResolution.Custom(w, h))) }
        }
        if (anyLargerSize) Hint(stringResource(R.string.video_editor_output_no_upscale))

        SectionTitle(R.string.video_editor_output_frame_rate)
        val sourceFps = source?.frameRate?.takeIf { it > 0f }
        val anyFasterRate = sourceFps != null && VideoOutputFrameRate.Common.any { it.fps > sourceFps + 0.01f }
        ChipRow {
            OutputChip(
                selected = settings.frameRate == VideoOutputFrameRate.Original,
                label = stringResource(R.string.video_editor_output_original),
                onClick = { onChange(settings.copy(frameRate = VideoOutputFrameRate.Original)) },
            )
            VideoOutputFrameRate.Common.forEach { option ->
                val reachable = sourceFps == null || option.fps <= sourceFps + 0.01f
                OutputChip(
                    selected = settings.frameRate == option,
                    enabled = reachable,
                    label = stringResource(R.string.video_editor_output_fps, option.fps.toString()),
                    onClick = { onChange(settings.copy(frameRate = option)) },
                )
            }
        }
        if (anyFasterRate) Hint(stringResource(R.string.video_editor_output_no_faster_rate))

        SectionTitle(R.string.video_editor_output_aspect)
        val forced = settings.aspect as? VideoAspectOverride.Forced
        val mode = forced?.mode ?: VideoAspectMode.Stretch
        val customAspect = forced != null && (forced.width to forced.height) !in VideoAspectOverride.Common
        ChipRow {
            OutputChip(
                selected = forced == null,
                label = stringResource(R.string.video_editor_output_original),
                onClick = { onChange(settings.copy(aspect = VideoAspectOverride.Original)) },
            )
            VideoAspectOverride.Common.forEach { (w, h) ->
                OutputChip(
                    selected = forced != null && forced.width == w && forced.height == h,
                    label = "$w:$h",
                    onClick = { onChange(settings.copy(aspect = VideoAspectOverride.Forced(w, h, mode))) },
                    testTag = "video-output-aspect-${w}x$h",
                )
            }
            OutputChip(
                selected = customAspect,
                label = stringResource(R.string.video_editor_output_custom),
                onClick = { if (!customAspect) onChange(settings.copy(aspect = VideoAspectOverride.Forced(3, 2, mode))) },
            )
        }
        if (forced != null) {
            if (customAspect) {
                PairFields(
                    first = forced.width,
                    second = forced.height,
                    firstLabel = R.string.video_editor_output_width,
                    secondLabel = R.string.video_editor_output_height,
                    range = 1..100,
                    error = R.string.video_editor_output_ratio_invalid,
                    testTag = "video-output-custom-aspect",
                ) { w, h -> onChange(settings.copy(aspect = VideoAspectOverride.Forced(w, h, mode))) }
            }
            val modes = VideoAspectMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == mode,
                        onClick = { onChange(settings.copy(aspect = forced.copy(mode = option))) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("video-output-mode-${option.name.lowercase()}"),
                        label = { Text(stringResource(option.label()), maxLines = 1, textAlign = TextAlign.Center) },
                    )
                }
            }
            Hint(stringResource(mode.description()))
        }

        SectionTitle(R.string.video_editor_output_audio)
        val aac = settings.audio as? VideoOutputAudio.Aac
        ChipRow {
            OutputChip(
                selected = settings.audio == VideoOutputAudio.Keep,
                label = stringResource(R.string.video_editor_output_audio_keep),
                onClick = { onChange(settings.copy(audio = VideoOutputAudio.Keep)) },
            )
            OutputChip(
                selected = settings.audio == VideoOutputAudio.Remove,
                label = stringResource(R.string.video_editor_output_audio_remove),
                onClick = { onChange(settings.copy(audio = VideoOutputAudio.Remove)) },
            )
            OutputChip(
                selected = aac != null,
                label = "AAC",
                onClick = { if (aac == null) onChange(settings.copy(audio = VideoOutputAudio.Aac(DefaultAacBitrate))) },
            )
        }
        if (aac != null) {
            ChipRow {
                AacBitrates.forEach { bps ->
                    OutputChip(
                        selected = aac.bitsPerSecond == bps,
                        label = stringResource(R.string.video_editor_output_kbps, bps / 1_000),
                        onClick = { onChange(settings.copy(audio = VideoOutputAudio.Aac(bps))) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoOutputSummaryCard(
    state: VideoEditorContentState,
    estimate: VideoOutputEstimate,
    onReset: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = GallerySpacing.Md, end = GallerySpacing.Sm, top = GallerySpacing.Md, bottom = GallerySpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) {
            state.outputSource?.let { source ->
                SummaryLine(R.string.video_editor_output_source, videoOutputSourceSummary(source))
            }
            SummaryLine(R.string.video_editor_output_result, videoOutputResultSummary(state.output, estimate))
            if (estimate.isLosslessCopy) {
                Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(GalleryIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.video_editor_output_lossless), style = MaterialTheme.typography.bodySmall)
                }
            }
            // The size estimate and Reset share the last line so the card stays compact.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    estimate.sizeBytes?.takeIf { it > 0 }?.let { bytes ->
                        stringResource(
                            R.string.video_editor_output_estimated_size,
                            Formatter.formatShortFileSize(LocalContext.current, bytes),
                        )
                    }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onReset,
                    enabled = !state.output.isDefault,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("video-output-reset"),
                ) { Text(stringResource(R.string.video_editor_output_reset)) }
            }
        }
    }
}

@Composable
private fun SummaryLine(@StringRes label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** "960×1088 · HEVC · 25 fps · 0.98 Mbps"; unknown parts are left out. */
@Composable
internal fun videoOutputSourceSummary(source: VideoOutputSource): String = listOfNotNull(
    "${source.width}×${source.height}".takeIf { source.width > 0 && source.height > 0 },
    source.videoMimeType?.let(::videoCodecDisplayName),
    source.frameRate?.takeIf { it > 0f }?.let { stringResource(R.string.video_editor_output_fps, formatFrameRate(it)) },
    (source.totalBitrate ?: source.videoBitrate)?.let { stringResource(R.string.video_editor_output_mbps, formatMbps(it)) },
).joinToString(" · ", transform = ::keepTogether)

/** Non-breaking spaces inside one summary part, so "≈ 1.97 Mbps" never wraps between value and unit. */
private fun keepTogether(part: String): String = part.replace(' ', ' ')

@Composable
internal fun videoOutputResultSummary(settings: VideoOutputSettings, estimate: VideoOutputEstimate): String = listOfNotNull(
    estimate.size?.let { "${it.width}×${it.height}" },
    estimate.codec?.displayName(),
    estimate.frameRate?.let { stringResource(R.string.video_editor_output_fps, formatFrameRate(it)) },
    estimate.videoBitrate?.let { "≈ " + stringResource(R.string.video_editor_output_mbps, formatMbps(it)) },
    when (val audio = settings.audio) {
        VideoOutputAudio.Keep -> null
        VideoOutputAudio.Remove -> stringResource(R.string.video_editor_output_no_audio)
        is VideoOutputAudio.Aac -> "AAC " + stringResource(R.string.video_editor_output_kbps, audio.bitsPerSecond / 1_000)
    },
).joinToString(" · ", transform = ::keepTogether).ifEmpty { stringResource(R.string.video_editor_output_original) }

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = GallerySpacing.Xs))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) { content() }
}

@Composable
private fun OutputChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).let { if (testTag != null) it.testTag(testTag) else it },
    )
}

@Composable
private fun TargetBitrateField(bitsPerSecond: Int, onValid: (Int) -> Unit) {
    var text by remember { mutableStateOf(formatMbps(bitsPerSecond)) }
    val valid = parseTargetMbps(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            parseTargetMbps(value)?.let(onValid)
        },
        label = { Text(stringResource(R.string.video_editor_output_bitrate_mbps)) },
        isError = !valid,
        supportingText = if (valid) null else { { Text(stringResource(R.string.video_editor_output_bitrate_invalid)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("video-output-target-bitrate"),
    )
}

@Composable
private fun PairFields(
    first: Int,
    second: Int,
    @StringRes firstLabel: Int,
    @StringRes secondLabel: Int,
    range: IntRange,
    @StringRes error: Int,
    testTag: String,
    onValid: (Int, Int) -> Unit,
) {
    var firstText by remember(testTag) { mutableStateOf(first.toString()) }
    var secondText by remember(testTag) { mutableStateOf(second.toString()) }
    fun parse(value: String) = value.trim().toIntOrNull()?.takeIf { it in range }
    val valid = parse(firstText) != null && parse(secondText) != null
    fun publish() {
        val a = parse(firstText) ?: return
        val b = parse(secondText) ?: return
        onValid(a, b)
    }
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = firstText,
                onValueChange = { firstText = it.filter(Char::isDigit).take(4); publish() },
                label = { Text(stringResource(firstLabel)) },
                isError = parse(firstText) == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).testTag("$testTag-first"),
            )
            Text("×", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = secondText,
                onValueChange = { secondText = it.filter(Char::isDigit).take(4); publish() },
                label = { Text(stringResource(secondLabel)) },
                isError = parse(secondText) == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f).testTag("$testTag-second"),
            )
        }
        if (!valid) {
            Text(stringResource(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@StringRes
private fun VideoQualityPreset.label(): Int = when (this) {
    VideoQualityPreset.Original -> R.string.video_editor_output_original
    VideoQualityPreset.High -> R.string.video_editor_output_quality_high
    VideoQualityPreset.Medium -> R.string.video_editor_output_quality_medium
    VideoQualityPreset.Low -> R.string.video_editor_output_quality_low
}

@StringRes
private fun VideoAspectMode.label(): Int = when (this) {
    VideoAspectMode.Stretch -> R.string.video_editor_output_mode_stretch
    VideoAspectMode.Crop -> R.string.video_editor_output_mode_crop
    VideoAspectMode.Pad -> R.string.video_editor_output_mode_pad
}

@StringRes
private fun VideoAspectMode.description(): Int = when (this) {
    VideoAspectMode.Stretch -> R.string.video_editor_output_mode_stretch_description
    VideoAspectMode.Crop -> R.string.video_editor_output_mode_crop_description
    VideoAspectMode.Pad -> R.string.video_editor_output_mode_pad_description
}

// ---- Preview frame for a forced aspect ratio -------------------------------------------------

/**
 * Preview boxes for the current output aspect, or null to keep the plain video fit. [playerAspect]
 * is what the player reports; before it is ready the source size (after crop and rotation) is used.
 */
internal fun videoOutputPreviewBoxes(
    state: VideoEditorContentState,
    playerAspect: Float?,
    maxWidth: Dp,
    maxHeight: Dp,
): VideoPreviewBoxes? {
    val forced = state.output.aspect as? VideoAspectOverride.Forced ?: return null
    val sourceAspect = playerAspect?.takeIf { it > 0f }
        ?: state.outputSource?.takeIf { it.width > 0 && it.height > 0 }?.let { editedSourceSize(it, state.geometry).aspect }
        ?: forced.ratio
    return videoPreviewBoxes(maxWidth.value, maxHeight.value.coerceAtLeast(1f), sourceAspect, forced)
}

/** The padded output frame: bars around the video, drawn behind the video surface. */
@Composable
internal fun VideoOutputPadBars(boxes: VideoPreviewBoxes, aspect: VideoAspectOverride) {
    if ((aspect as? VideoAspectOverride.Forced)?.mode != VideoAspectMode.Pad) return
    Box(
        Modifier
            .size(boxes.frameWidth.dp, boxes.frameHeight.dp)
            .background(MaterialTheme.colorScheme.scrim)
            .testTag("video-output-pad-bars"),
    )
}

/** Dims the parts of the frame a Crop will cut, drawn over the video. */
@Composable
internal fun VideoOutputCropMask(boxes: VideoPreviewBoxes, aspect: VideoAspectOverride) {
    if ((aspect as? VideoAspectOverride.Forced)?.mode != VideoAspectMode.Crop) return
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    Canvas(Modifier.size(boxes.videoWidth.dp, boxes.videoHeight.dp).testTag("video-output-crop-mask")) {
        val frameW = size.width * boxes.frameWidth / boxes.videoWidth
        val frameH = size.height * boxes.frameHeight / boxes.videoHeight
        val left = (size.width - frameW) / 2f
        val top = (size.height - frameH) / 2f
        if (left > 0f) {
            drawRect(scrim, Offset.Zero, Size(left, size.height))
            drawRect(scrim, Offset(left + frameW, 0f), Size(left, size.height))
        }
        if (top > 0f) {
            drawRect(scrim, Offset(left, 0f), Size(frameW, top))
            drawRect(scrim, Offset(left, top + frameH), Size(frameW, top))
        }
    }
}

/**
 * Stand-in for the video when there is no player (previews, tests): a box with the edited shape,
 * framed like the real surface would be for a forced aspect.
 */
@Composable
internal fun VideoOutputPreviewPlaceholder(state: VideoEditorContentState) {
    val source = state.outputSource?.takeIf { it.width > 0 && it.height > 0 } ?: return
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxes = videoOutputPreviewBoxes(state, null, maxWidth, maxHeight) ?: run {
            val aspect = editedSourceSize(source, state.geometry).aspect
            val container = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val (w, h) = if (aspect >= container) maxWidth.value to maxWidth.value / aspect else maxHeight.value * aspect to maxHeight.value
            VideoPreviewBoxes(w, h, w, h)
        }
        VideoOutputPadBars(boxes, state.output.aspect)
        Box(
            Modifier
                .size(boxes.videoWidth.dp, boxes.videoHeight.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .testTag("video-output-preview-frame"),
        )
        VideoOutputCropMask(boxes, state.output.aspect)
    }
}
