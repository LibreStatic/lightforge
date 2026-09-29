package com.librestatic.lightforge.feature.videoeditor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing

/**
 * The editing tools shown in the bottom tool bar. [index] is the value persisted as the selected
 * tab, so the order must stay stable across releases (draft restore reads it back).
 */
internal enum class VideoEditorTool(
    val index: Int,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Speed(0, R.string.video_editor_speed, GalleryIcons.Speed),
    Audio(1, R.string.video_editor_audio, GalleryIcons.Volume),
    Music(2, R.string.video_editor_music, GalleryIcons.Music),
    Color(3, R.string.video_editor_color, GalleryIcons.Palette),
    Transform(4, R.string.video_editor_transform, GalleryIcons.Crop),
    Draw(5, R.string.video_editor_draw, GalleryIcons.Edit),
    ;

    companion object {
        /** Maps a persisted tab index to a tool, falling back to [Speed] for unknown values. */
        fun fromIndex(index: Int): VideoEditorTool = entries.firstOrNull { it.index == index } ?: Speed
    }
}

internal const val VideoEditorToolBarTag = "video-editor-tool-bar"

/**
 * Persistent bottom bar with every editing tool visible at once (icon plus label), replacing the
 * horizontally scrolling chip row that cut tools off at the screen edge.
 */
@Composable
internal fun VideoEditorToolBar(
    selected: VideoEditorTool,
    onSelect: (VideoEditorTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(VideoEditorToolBarTag),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = GallerySpacing.Xs, vertical = GallerySpacing.Xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            VideoEditorTool.entries.forEach { tool ->
                VideoEditorToolItem(
                    tool = tool,
                    selected = tool == selected,
                    onClick = { onSelect(tool) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VideoEditorToolItem(
    tool: VideoEditorTool,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = GallerySpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The selected state is a filled pill behind the icon and a stronger label, so it never
        // relies on color alone.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Box(Modifier.width(56.dp).heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
                Icon(tool.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            stringResource(tool.label),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
