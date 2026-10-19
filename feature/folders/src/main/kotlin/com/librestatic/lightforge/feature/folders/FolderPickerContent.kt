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
    sourceFolders: List<FolderLocation>,
    state: FolderExplorerUiState,
    thumbnails: ThumbnailLoader?,
    onEnter: (FolderLocation) -> Unit,
    onBack: () -> Unit,
    onNewFolder: (() -> Unit)?,
    onConfirm: (FolderLocation) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    /** Files travel with the folders, so a destination that is only the folders' own parent still changes something. */
    withFiles: Boolean = false,
    /**
     * A folder named by the user that does not exist yet (the mode-off New folder): the system creates it
     * when the first item arrives, so confirming chooses it, inside the folder being shown.
     */
    pendingFolder: String? = null,
    onClearPendingFolder: () -> Unit = {},
    /** Opens the system folder picker for a destination the library does not list; null when not offered. */
    onOtherLocation: (() -> Unit)? = null,
    /** The system takes no new folder at a volume root, so a mode-off New folder needs a folder to go in. */
    newFolderNeedsParent: Boolean = false,
) {
    val mode = ExplorerMode.PickDestination(purpose, sourceFolders, withFiles)
    val shown = (state as? FolderExplorerUiState.Folder)?.crumbs?.lastOrNull()?.location
    val here = if (pendingFolder != null && shown != null) pendingFolderLocation(shown, pendingFolder) else shown
    val canChoose = mode.canChoose(here)
    val title = stringResource(
        when (purpose) {
            PickPurpose.Move -> R.string.folders_picker_title_move
            PickPurpose.Copy -> R.string.folders_picker_title_copy
            PickPurpose.MoveFolder -> R.string.folders_picker_title_move_folder
            PickPurpose.CopyFolder -> R.string.folders_picker_title_copy_folder
        },
    )
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(
                title = title,
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.folders_picker_back),
                actions = {
                    if (onOtherLocation != null) {
                        IconButton(onClick = onOtherLocation, modifier = Modifier.testTag("folder-picker-other-location")) {
                            Icon(GalleryIcons.OpenInNew, contentDescription = stringResource(R.string.folders_picker_other_location))
                        }
                    }
                    if (onNewFolder != null) {
                        IconButton(
                            onClick = onNewFolder,
                            enabled = shown != null && shown.relativePath?.let(::isAndroidAppTree) != true &&
                                (!newFolderNeedsParent || !shown.relativePath.isNullOrBlank()),
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
                    if (pendingFolder != null) {
                        Text(
                            stringResource(R.string.folders_picker_new_pending, pendingFolder),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).testTag("folder-picker-pending"),
                        )
                        IconButton(onClick = onClearPendingFolder, modifier = Modifier.testTag("folder-picker-pending-clear")) {
                            Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.folders_picker_new_pending_clear))
                        }
                    }
                    Button(
                        onClick = { here?.let(onConfirm) },
                        enabled = canChoose,
                        modifier = Modifier.testTag("folder-picker-confirm"),
                    ) {
                        Icon(
                            if (purpose.isCopy) GalleryIcons.ContentCopy else GalleryIcons.DriveFileMove,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(if (purpose.isCopy) R.string.folders_picker_copy_here else R.string.folders_picker_move_here),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
