package com.librestatic.lightforge.feature.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader

/**
 * Full-screen chooser for a folder's cover: every item in the folder and below it, one tap picks it.
 * Stateless; the host feeds the paged [media] of the folder. [onReset] is null while the cover is automatic.
 */
@Composable
fun FolderCoverDialog(
    folderName: String,
    media: LazyPagingItems<TimelineMedia>,
    thumbnails: ThumbnailLoader?,
    onPick: (MediaKey) -> Unit,
    onReset: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            Modifier.fillMaxSize().testTag("folder-cover-dialog"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize()) {
                GalleryTopAppBar(
                    title = stringResource(R.string.folders_cover_title, folderName),
                    onBack = onDismiss,
                    navigationContentDescription = stringResource(R.string.folders_picker_back),
                    actions = {
                        if (onReset != null) {
                            IconButton(onClick = onReset, modifier = Modifier.testTag("folder-cover-reset")) {
                                Icon(GalleryIcons.Undo, contentDescription = stringResource(R.string.folders_menu_cover_reset))
                            }
                        }
                    },
                )
                Box(Modifier.weight(1f).fillMaxSize()) {
                    val loading = media.loadState.refresh is LoadState.Loading
                    when {
                        media.itemCount == 0 && loading -> {
                            val description = stringResource(R.string.folders_loading)
                            Box(Modifier.fillMaxSize().semantics { contentDescription = description }, Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        media.itemCount == 0 -> GalleryStateContent(
                            title = stringResource(R.string.folders_cover_empty_title),
                            body = stringResource(R.string.folders_cover_empty_body),
                            illustrationDescription = stringResource(R.string.folders_cover_empty_title),
                            modifier = Modifier.fillMaxSize().testTag("folder-cover-empty"),
                            heroIcon = GalleryIcons.Photo,
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(96.dp),
                            modifier = Modifier.fillMaxSize().testTag("folder-cover-grid"),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                count = media.itemCount,
                                key = media.itemKey { "cover:${it.key.volumeName}:${it.key.mediaStoreId}" },
                            ) { index ->
                                media[index]?.let { item ->
                                    val label = stringResource(R.string.folders_cover_use, item.displayName.orEmpty())
                                    Cover(
                                        item.key,
                                        thumbnails,
                                        Modifier
                                            .aspectRatio(1f)
                                            .testTag("folder-cover-${item.key.volumeName}-${item.key.mediaStoreId}")
                                            .clickable(onClickLabel = label, role = Role.Button) { onPick(item.key) }
                                            .semantics { contentDescription = item.displayName.orEmpty() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
