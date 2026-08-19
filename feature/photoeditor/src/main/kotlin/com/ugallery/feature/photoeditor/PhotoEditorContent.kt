package com.ugallery.feature.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ugallery.core.model.EditOperation
import com.ugallery.core.designsystem.GalleryIcons

data class PhotoEditorContentState(
    val preview: Bitmap? = null,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedFilter: String = "none",
)

@Composable
fun PhotoEditorContent(
    state: PhotoEditorContentState,
    onBack: () -> Unit,
    onSaveCopy: () -> Unit,
    onApply: (EditOperation) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.photo_editor_cancel))
            }
            Text(stringResource(R.string.photo_editor_title), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onSaveCopy, enabled = !state.isExporting) {
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
                    PhotoTools(state, onApply, onUndo, onRedo, Modifier.weight(0.42f).padding(16.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PreviewStage(state, Modifier.weight(1f).fillMaxWidth())
                    PhotoTools(state, onApply, onUndo, onRedo, Modifier.fillMaxWidth())
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
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.photo_editor_filters), style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = onUndo, enabled = state.canUndo) {
                    Icon(GalleryIcons.Undo, contentDescription = stringResource(R.string.photo_editor_undo))
                }
                IconButton(onClick = onRedo, enabled = state.canRedo) {
                    Icon(GalleryIcons.Redo, contentDescription = stringResource(R.string.photo_editor_redo))
                }
            }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onApply(EditOperation.Crop(80, 80, 920, 920)) }) {
                Icon(GalleryIcons.Crop, contentDescription = null)
                Text(stringResource(R.string.photo_editor_crop))
            }
            OutlinedButton(onClick = { onApply(EditOperation.Rotate(90)) }) {
                Icon(GalleryIcons.Tune, contentDescription = null)
                Text(stringResource(R.string.photo_editor_rotate))
            }
            OutlinedButton(onClick = { onApply(EditOperation.Flip(horizontal = true)) }) {
                Icon(GalleryIcons.Edit, contentDescription = null)
                Text(stringResource(R.string.photo_editor_flip))
            }
        }
        Text(
            stringResource(R.string.photo_editor_copy_policy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
