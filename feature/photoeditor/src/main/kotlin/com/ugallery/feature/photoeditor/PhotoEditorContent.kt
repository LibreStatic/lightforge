package com.ugallery.feature.photoeditor

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.RawDevelopmentSettings
import com.ugallery.core.model.RawMetadata
import com.ugallery.core.model.RawOutputFormat
import com.ugallery.core.designsystem.GalleryIcons

data class PhotoEditorContentState(
    val preview: Bitmap? = null,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedFilter: String = "none",
    val isRaw: Boolean = false,
    val rawMetadata: RawMetadata? = null,
    val rawSettings: RawDevelopmentSettings = RawDevelopmentSettings(),
    val rawOutputFormat: RawOutputFormat = RawOutputFormat.Tiff16Srgb,
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
    onRawOutputFormatChange: (RawOutputFormat) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.photo_editor_cancel))
            }
            Text(
                stringResource(R.string.photo_editor_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSaveCopy, enabled = !state.isExporting) {
                Text(stringResource(R.string.photo_editor_save_copy))
            }
        }
    }) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
        ) {
            val expanded = maxWidth >= 840.dp
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    PreviewStage(state, Modifier.weight(1f).fillMaxSize())
                    PhotoTools(state, onApply, onUndo, onRedo, onRawSettingsChange, onRawOutputFormatChange, Modifier.weight(0.42f).padding(16.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PreviewStage(state, Modifier.weight(1f).fillMaxWidth())
                    PhotoTools(state, onApply, onUndo, onRedo, onRawSettingsChange, onRawOutputFormatChange, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PreviewStage(state: PhotoEditorContentState, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val preview = state.preview
        if (preview != null) {
            val description = stringResource(R.string.photo_editor_preview_description)
            AndroidView(
                factory = { context -> android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                } },
                update = { it.setImageBitmap(preview) },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
        } else if (state.isRendering) {
            CircularProgressIndicator()
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
    onApply: (EditOperation) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRawSettingsChange: (RawDevelopmentSettings) -> Unit,
    onRawOutputFormatChange: (RawOutputFormat) -> Unit,
    modifier: Modifier,
) {
    var selectedTab by remember(state.isRaw) { mutableIntStateOf(if (state.isRaw) 0 else 1) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.isRaw) stringResource(R.string.photo_editor_raw_title) else stringResource(R.string.photo_editor_filters), style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = onUndo, enabled = state.canUndo) {
                    Icon(GalleryIcons.Undo, contentDescription = stringResource(R.string.photo_editor_undo))
                }
                IconButton(onClick = onRedo, enabled = state.canRedo) {
                    Icon(GalleryIcons.Redo, contentDescription = stringResource(R.string.photo_editor_redo))
                }
            }
        }
        if (state.isRaw) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                listOf(R.string.photo_editor_raw_tab, R.string.photo_editor_export_tab)
                    .forEachIndexed { index, label ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(stringResource(label)) })
                    }
            }
            when (selectedTab) {
                0 -> RawControls(state.rawSettings, state.rawMetadata, onRawSettingsChange)
                1 -> RawExportControls(state.rawOutputFormat, onRawOutputFormatChange)
            }
            Text(
                stringResource(R.string.photo_editor_copy_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "none" to R.string.photo_editor_filter_original,
                "natural" to R.string.photo_editor_filter_natural,
                "vivid" to R.string.photo_editor_filter_vivid,
                "mono" to R.string.photo_editor_filter_mono,
            ).forEach { (name, label) ->
                FilterChip(
                    selected = state.selectedFilter == name,
                    onClick = { onApply(EditOperation.Filter(name)) },
                    label = { Text(stringResource(label)) },
                    leadingIcon = { Icon(GalleryIcons.Palette, contentDescription = null) },
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          item { OutlinedButton(onClick = { onApply(EditOperation.Crop(80, 80, 920, 920)) }) {
                Icon(GalleryIcons.Crop, contentDescription = null)
                Text(stringResource(R.string.photo_editor_crop))
            } }
          item { OutlinedButton(onClick = { onApply(EditOperation.Rotate(90)) }) {
                Icon(GalleryIcons.Tune, contentDescription = null)
                Text(stringResource(R.string.photo_editor_rotate))
            } }
          item { OutlinedButton(onClick = { onApply(EditOperation.Flip(horizontal = true)) }) {
                Icon(GalleryIcons.Edit, contentDescription = null)
                Text(stringResource(R.string.photo_editor_flip))
            } }
        }
        Text(
            stringResource(R.string.photo_editor_copy_policy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RawControls(
    settings: RawDevelopmentSettings,
    metadata: RawMetadata?,
    onChange: (RawDevelopmentSettings) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onChange(RawDevelopmentSettings()) }) {
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
    RawSlider(stringResource(R.string.photo_editor_exposure), settings.exposureEv, -5f..5f) {
        onChange(settings.copy(exposureEv = it))
    }
    RawSlider(stringResource(R.string.photo_editor_temperature), settings.temperatureKelvin.toFloat(), 2_000f..14_000f) {
        onChange(settings.copy(temperatureKelvin = it.toInt()))
    }
    RawSlider(stringResource(R.string.photo_editor_tint), settings.tint, -150f..150f) { onChange(settings.copy(tint = it)) }
    RawSlider(stringResource(R.string.photo_editor_highlights), settings.highlights, -1f..1f) { onChange(settings.copy(highlights = it)) }
    RawSlider(stringResource(R.string.photo_editor_shadows), settings.shadows, -1f..1f) { onChange(settings.copy(shadows = it)) }
    RawSlider(stringResource(R.string.photo_editor_contrast), settings.contrast, -1f..1f) { onChange(settings.copy(contrast = it)) }
    RawSlider(stringResource(R.string.photo_editor_saturation), settings.saturation, -1f..1f) { onChange(settings.copy(saturation = it)) }
    RawSlider(stringResource(R.string.photo_editor_noise_reduction), settings.luminanceNoiseReduction, 0f..1f) {
        onChange(settings.copy(luminanceNoiseReduction = it))
    }
}

@Composable
private fun RawSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(if (range.endInclusive > 100f) value.toInt().toString() else "%.2f".format(value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RawExportControls(selected: RawOutputFormat, onSelected: (RawOutputFormat) -> Unit) {
    Text(stringResource(R.string.photo_editor_output_format), style = MaterialTheme.typography.titleSmall)
    RawOutputFormat.entries.forEach { format ->
        FilterChip(
            selected = selected == format,
            onClick = { onSelected(format) },
            label = { Text(stringResource(when (format) {
                RawOutputFormat.JpegSrgb -> R.string.photo_editor_output_jpeg
                RawOutputFormat.Tiff16Srgb -> R.string.photo_editor_output_tiff_srgb
            })) },
        )
    }
}
