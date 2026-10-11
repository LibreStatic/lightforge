package com.librestatic.lightforge.feature.photoeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing

/** Actions tab: the experimental eraser and cut-out as a carousel of sub-tools. */
@Composable
internal fun ActionsTools(selected: PhotoEditorTool, onSelect: (PhotoEditorTool) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        item {
            PhotoToolTile(
                icon = GalleryIcons.Cleanup,
                label = stringResource(R.string.photo_editor_object_eraser),
                selected = selected == PhotoEditorTool.ObjectEraser,
                onClick = { onSelect(PhotoEditorTool.ObjectEraser) },
                testTag = "photo-editor-tool-objecteraser",
            )
        }
        item {
            PhotoToolTile(
                icon = GalleryIcons.CenterFocusStrong,
                label = stringResource(R.string.photo_editor_subject_clip),
                selected = selected == PhotoEditorTool.SubjectClip,
                onClick = { onSelect(PhotoEditorTool.SubjectClip) },
                testTag = "photo-editor-tool-subjectclip",
            )
        }
    }
}

/** More tab: hand the photo to another editor app. */
@Composable
internal fun MoreControls(onOpenWith: (() -> Unit)?) {
    androidx.compose.material3.Text(
        stringResource(R.string.photo_editor_open_with_body),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (onOpenWith != null) {
        Button(onClick = onOpenWith, modifier = Modifier.fillMaxWidth().testTag("photo-editor-open-with")) {
            Icon(GalleryIcons.OpenInNew, contentDescription = null)
            Text(stringResource(R.string.photo_editor_open_with))
        }
    }
}
