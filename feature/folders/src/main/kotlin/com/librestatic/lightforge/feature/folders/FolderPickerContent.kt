package com.librestatic.lightforge.feature.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader

/**
 * Full-screen destination picker for Move to folder, Copy to folder and folder moves: the folder
 * explorer in [ExplorerMode.PickDestination] with a top bar (Back and New folder) and a bottom bar
 * whose labelled button confirms the folder being shown. Stateless; the host feeds [state] and owns
 * the location (a separate explorer instance, so the browse location is untouched).
 *
 * [onBack] goes up one level and cancels the picker at the top; [onNewFolder] is null when folders
 * cannot be created.
 */
@Composable
fun FolderPickerContent(
    purpose: PickPurpose,
    sourceFolder: FolderLocation?,
    state: FolderExplorerUiState,
    thumbnails: ThumbnailLoader?,
    onEnter: (FolderLocation) -> Unit,
    onBack: () -> Unit,
    onNewFolder: (() -> Unit)?,
    onConfirm: (FolderLocation) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val mode = ExplorerMode.PickDestination(purpose, sourceFolder)
    val here = (state as? FolderExplorerUiState.Folder)?.crumbs?.lastOrNull()?.location
    val canChoose = mode.canChoose(here)
    val title = stringResource(
        when (purpose) {
            PickPurpose.Move -> R.string.folders_picker_title_move
            PickPurpose.Copy -> R.string.folders_picker_title_copy
            PickPurpose.MoveFolder -> R.string.folders_picker_title_move_folder
        },
    )
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(
                title = title,
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.folders_picker_back),
                actions = {
                    if (onNewFolder != null) {
                        IconButton(
                            onClick = onNewFolder,
                            enabled = here != null && here.relativePath?.let(::isAndroidAppTree) != true,
                            modifier = Modifier.testTag("folder-picker-new-folder"),
                        ) {
                            Icon(GalleryIcons.CreateNewFolder, contentDescription = stringResource(R.string.folders_new_title))
                        }
                    }
                },
            )
            FolderExplorerContent(
                state = state,
                thumbnails = thumbnails,
                onEnter = onEnter,
                onOpenFiles = {},
                modifier = Modifier.weight(1f),
                onRetry = onRetry,
                mode = mode,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { here?.let(onConfirm) },
                        enabled = canChoose,
                        modifier = Modifier.testTag("folder-picker-confirm"),
                    ) {
                        Icon(
                            if (purpose == PickPurpose.Copy) GalleryIcons.ContentCopy else GalleryIcons.DriveFileMove,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(if (purpose == PickPurpose.Copy) R.string.folders_picker_copy_here else R.string.folders_picker_move_here),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
