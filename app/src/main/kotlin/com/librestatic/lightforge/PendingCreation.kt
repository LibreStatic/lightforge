package com.librestatic.lightforge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A creation the user started from the Create sheet before selecting anything. While it is
 * pending, the Photos grid stays in "pick for this creation" mode and the selection toolbar
 * offers the creation as its primary action, so the hint never points at a hidden menu item.
 */
internal enum class PendingCreation { Memory, MemoryVideo, Gif, Collage }

@androidx.compose.runtime.Composable
internal fun pendingCreationTitle(creation: PendingCreation): String =
    androidx.compose.ui.res.stringResource(
        when (creation) {
            PendingCreation.Memory -> com.librestatic.lightforge.feature.collections.R.string.manual_moment_title
            PendingCreation.MemoryVideo -> com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_title
            PendingCreation.Gif -> com.librestatic.lightforge.feature.collage.R.string.creation_gif_title
            PendingCreation.Collage -> R.string.m6_collage
        },
    )

internal fun pendingCreationIcon(creation: PendingCreation): androidx.compose.ui.graphics.vector.ImageVector =
    when (creation) {
        PendingCreation.Memory -> com.librestatic.lightforge.core.designsystem.GalleryIcons.PhotoLibrary
        PendingCreation.MemoryVideo -> com.librestatic.lightforge.core.designsystem.GalleryIcons.Video
        PendingCreation.Gif -> com.librestatic.lightforge.core.designsystem.GalleryIcons.Repeat
        PendingCreation.Collage -> com.librestatic.lightforge.core.designsystem.GalleryIcons.GridView
    }

/** Shown over the grid until the first item is picked for a [PendingCreation]. */
@androidx.compose.runtime.Composable
internal fun PendingCreationHint(creation: PendingCreation, hint: String, onCancel: () -> Unit) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
        modifier = androidx.compose.ui.Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("pending-creation-hint"),
    ) {
        Row(
            androidx.compose.ui.Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(pendingCreationIcon(creation), contentDescription = null)
            androidx.compose.material3.Text(
                hint,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                modifier = androidx.compose.ui.Modifier.weight(1f).padding(vertical = 12.dp),
            )
            androidx.compose.material3.TextButton(
                onClick = onCancel,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = androidx.compose.material3.LocalContentColor.current,
                ),
            ) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) }
        }
    }
}
