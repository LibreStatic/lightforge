package com.librestatic.lightforge.feature.photoeditor

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.editing.image.GifOutputOptions
import com.librestatic.lightforge.core.editing.image.PhotoEncoding
import com.librestatic.lightforge.core.editing.image.PhotoOutputAdjustment
import com.librestatic.lightforge.core.editing.image.PhotoOutputFormat
import com.librestatic.lightforge.core.editing.image.PhotoOutputMetadata
import com.librestatic.lightforge.core.editing.image.PhotoOutputPlan
import com.librestatic.lightforge.core.editing.image.PhotoOutputResize
import com.librestatic.lightforge.core.editing.image.PhotoOutputSettings
import kotlin.math.max
import kotlin.math.roundToInt

internal const val PhotoOutputPanelTag = "photo-output-panel"

/**
 * What Save copy will write with the current settings: the edited frame size, the resolved
 * [plan] and an approximate file size, or null while unknown.
 */
data class PhotoOutputSummary(
    val editedWidth: Int,
    val editedHeight: Int,
    val plan: PhotoOutputPlan,
    val estimateBytes: Long? = null,
)

/**
 * Export tool: converter-style options for Save copy (format, quality, size, metadata and the GIF
 * palette) with a summary of the result on top. Settings belong to this editing session only.
 */
@Composable
internal fun PhotoOutputControls(
    state: PhotoEditorContentState,
    onChange: (PhotoOutputSettings) -> Unit,
) {
    val settings = state.output
    val summary = state.outputSummary
    // Opening the panel refreshes the summary for the current edit (crop changes the size).
    LaunchedEffect(Unit) { onChange(settings) }
    val encoding = summary?.plan?.encoding ?: PhotoEncoding.of(settings.format)
    Column(
        Modifier.fillMaxWidth().padding(bottom = GallerySpacing.Sm).testTag(PhotoOutputPanelTag),
        // Sections breathe; items inside a section use OutputSection's tighter spacing.
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xl),
    ) {
        PhotoOutputSummaryCard(settings, summary, onReset = { onChange(PhotoOutputSettings()) })

        OutputSection(R.string.photo_editor_output_format) {
            ChipRow(
                state.outputFormats.map { format ->
                    ChipOption(
                        label = formatLabel(format),
                        selected = settings.format == format,
                        testTag = "photo-output-format-${format.name.lowercase()}",
                    ) { onChange(settings.copy(format = format)) }
                },
            )
            if (PhotoOutputFormat.Heic !in state.outputFormats || PhotoOutputFormat.Avif !in state.outputFormats) {
                Hint(stringResource(R.string.photo_editor_output_formats_unavailable))
            }
        }

        if (settings.format.usesQuality || (settings.format == PhotoOutputFormat.Original && encoding?.usesQuality == true)) {
            EditorAdjustmentSlider(
                label = stringResource(R.string.photo_editor_output_quality),
                value = settings.quality.toFloat(),
                onValueChange = { onChange(settings.copy(quality = it.roundToInt().coerceIn(1, 100))) },
                valueRange = 1f..100f,
                neutral = PhotoOutputSettings.DefaultQuality.toFloat(),
                displayValue = { it.roundToInt().toString() },
                testTag = "photo-output-quality",
            )
        }

        OutputSection(R.string.photo_editor_output_size) { ResizeChips(settings, summary, onChange) }

        OutputSection(R.string.photo_editor_output_metadata) {
            ChipRow(
                PhotoOutputMetadata.entries.map { metadata ->
                    ChipOption(
                        label = stringResource(metadata.label()),
                        selected = settings.metadata == metadata,
                        testTag = "photo-output-metadata-${metadata.name.lowercase()}",
                    ) { onChange(settings.copy(metadata = metadata)) }
                },
            )
        }

        if (encoding == PhotoEncoding.Gif) {
            OutputSection(R.string.photo_editor_output_gif_colors) {
                ChipRow(
                    GifOutputOptions.CommonColors.map { colors ->
                        ChipOption(
                            label = colors.toString(),
                            selected = settings.gif.colors == colors,
                            testTag = "photo-output-gif-colors-$colors",
                        ) { onChange(settings.copy(gif = settings.gif.copy(colors = colors))) }
                    },
                )
                DitherRow(settings.gif.dither) { onChange(settings.copy(gif = settings.gif.copy(dither = it))) }
            }
        }
        if (state.isRaw) Hint(stringResource(R.string.photo_editor_output_raw_hint))
    }
}

@Composable
private fun ResizeChips(
    settings: PhotoOutputSettings,
    summary: PhotoOutputSummary?,
    onChange: (PhotoOutputSettings) -> Unit,
) {
    val resize = settings.resize
    val isCustom = resize is PhotoOutputResize.LongSide && resize !in PhotoOutputResize.CommonLongSides
    var showCustom by rememberSaveable { mutableStateOf(isCustom) }
    val editedLongSide = summary?.let { max(it.editedWidth, it.editedHeight) }
    val options = buildList {
        add(
            ChipOption(
                label = stringResource(R.string.photo_editor_output_size_original),
                selected = resize == PhotoOutputResize.Original && !showCustom,
                testTag = "photo-output-size-original",
            ) { showCustom = false; onChange(settings.copy(resize = PhotoOutputResize.Original)) },
        )
        PhotoOutputResize.CommonLongSides
            // Sizes at or above the edited photo would not change anything.
            .filter { editedLongSide == null || it.pixels < editedLongSide }
            .forEach { option ->
                add(
                    ChipOption(
                        label = stringResource(R.string.photo_editor_output_size_long_side, option.pixels),
                        selected = resize == option && !showCustom,
                        testTag = "photo-output-size-${option.pixels}",
                    ) { showCustom = false; onChange(settings.copy(resize = option)) },
                )
            }
        PhotoOutputResize.CommonScales.forEach { option ->
            add(
                ChipOption(
                    label = stringResource(R.string.photo_editor_output_size_percent, option.percent),
                    selected = resize == option && !showCustom,
                    testTag = "photo-output-size-${option.percent}pct",
                ) { showCustom = false; onChange(settings.copy(resize = option)) },
            )
        }
        add(
            ChipOption(
                label = stringResource(R.string.photo_editor_output_size_custom),
                selected = showCustom,
                testTag = "photo-output-size-custom",
            ) { showCustom = true },
        )
    }
    ChipRow(options)
    if (showCustom) {
        val start = (resize as? PhotoOutputResize.LongSide)?.pixels
            ?: summary?.let { max(it.plan.width, it.plan.height) }
            ?: 2048
        LongSideField(start) { onChange(settings.copy(resize = PhotoOutputResize.LongSide(it))) }
    }
}

@Composable
private fun LongSideField(pixels: Int, onValid: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(pixels.toString()) }
    val range = PhotoOutputResize.MinPixels..PhotoOutputResize.MaxPixels
    val parsed = text.trim().toIntOrNull()?.takeIf { it in range }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.takeIf { it in range }?.let(onValid)
        },
        label = { Text(stringResource(R.string.photo_editor_output_size_custom_field)) },
        isError = parsed == null,
        supportingText = if (parsed != null) null else {
            { Text(stringResource(R.string.photo_editor_output_size_custom_invalid, range.first, range.last)) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("photo-output-size-custom-field"),
    )
}

@Composable
private fun DitherRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag("photo-output-gif-dither"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_output_gif_dither), style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(R.string.photo_editor_output_gif_dither_hint))
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PhotoOutputSummaryCard(
    settings: PhotoOutputSettings,
    summary: PhotoOutputSummary?,
    onReset: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.testTag("photo-output-summary"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(
                start = GallerySpacing.Lg,
                end = GallerySpacing.Xs,
                top = GallerySpacing.Xs,
                bottom = GallerySpacing.Md,
            ),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (summary != null) {
                        SummaryLine(R.string.photo_editor_output_edited, dimensions(summary.editedWidth, summary.editedHeight))
                    }
                }
                TextButton(
                    onClick = onReset,
                    enabled = !settings.isDefault,
                    modifier = Modifier.testTag("photo-output-reset"),
                ) { Text(stringResource(R.string.photo_editor_output_reset)) }
            }
            val result = if (summary != null) {
                "${encodingLabel(summary.plan.encoding)} · ${dimensions(summary.plan.width, summary.plan.height)}"
            } else formatLabel(settings.format)
            SummaryLine(R.string.photo_editor_output_result, result)
            summary?.estimateBytes?.takeIf { it > 0 }?.let { bytes ->
                Text(
                    stringResource(
                        R.string.photo_editor_output_estimated_size,
                        Formatter.formatShortFileSize(LocalContext.current, bytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("photo-output-estimate"),
                )
            }
            summary?.plan?.adjustments?.forEach { adjustment ->
                Row(
                    Modifier.padding(top = GallerySpacing.Xs).padding(end = GallerySpacing.Md)
                        .testTag("photo-output-note-${adjustment.name}"),
                    horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
                ) {
                    Icon(GalleryIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(adjustment.note()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(@StringRes label: Int, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun dimensions(width: Int, height: Int): String = "$width × $height"

@Composable
private fun OutputSection(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private class ChipOption(val label: String, val selected: Boolean, val testTag: String, val onClick: () -> Unit)

/** One scrolling line of standard-height chips, starting at the selected one. */
@Composable
private fun ChipRow(options: List<ChipOption>) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0),
    )
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(options.size, key = { options[it].testTag }) { index ->
            val option = options[index]
            FilterChip(
                selected = option.selected,
                onClick = option.onClick,
                label = { Text(option.label, maxLines = 1) },
                modifier = Modifier.testTag(option.testTag),
            )
        }
    }
}

@Composable
private fun formatLabel(format: PhotoOutputFormat): String =
    PhotoEncoding.of(format)?.let { encodingLabel(it) } ?: stringResource(R.string.photo_editor_output_original)

/** Format names are trademarks and stay untranslated, except the WebP lossless qualifier. */
@Composable
private fun encodingLabel(encoding: PhotoEncoding): String = when (encoding) {
    PhotoEncoding.Jpeg -> "JPEG"
    PhotoEncoding.Png -> "PNG"
    PhotoEncoding.WebpLossy -> "WebP"
    PhotoEncoding.WebpLossless -> stringResource(R.string.photo_editor_output_webp_lossless)
    PhotoEncoding.Gif -> "GIF"
    PhotoEncoding.Heic -> "HEIC"
    PhotoEncoding.Avif -> "AVIF"
}

private fun PhotoOutputMetadata.label(): Int = when (this) {
    PhotoOutputMetadata.Keep -> R.string.photo_editor_output_metadata_keep
    PhotoOutputMetadata.RemoveLocation -> R.string.photo_editor_output_metadata_no_location
    PhotoOutputMetadata.RemoveAll -> R.string.photo_editor_output_metadata_none
}

internal fun PhotoOutputAdjustment.note(): Int = when (this) {
    PhotoOutputAdjustment.FormatUnavailable -> R.string.photo_editor_output_note_format_unavailable
    PhotoOutputAdjustment.NoUpscale -> R.string.photo_editor_output_note_no_upscale
    PhotoOutputAdjustment.TooLargeUsesPng -> R.string.photo_editor_output_note_png
    PhotoOutputAdjustment.MetadataUnsupported -> R.string.photo_editor_output_note_metadata
}
