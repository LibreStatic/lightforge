package com.librestatic.lightforge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.feature.folders.ExplorerSelection
import com.librestatic.lightforge.feature.folders.FolderBrowseActions
import com.librestatic.lightforge.feature.folders.FolderCardUi
import com.librestatic.lightforge.feature.folders.FolderViewOptionsDialog
import com.librestatic.lightforge.feature.folders.FolderExplorerActions
import com.librestatic.lightforge.feature.folders.FolderExplorerContent
import com.librestatic.lightforge.feature.folders.FolderExplorerUiState
import com.librestatic.lightforge.feature.folders.FolderMediaScope
import com.librestatic.lightforge.feature.folders.NewFolderDialog

/** The slice of the gallery selection the explorer needs: whether files are selected and which. */
internal class MediaSelectionAccess(
    val selecting: Boolean,
    val isSelected: (MediaKey) -> Boolean,
    val toggle: (TimelineMedia) -> Unit,
) {
    companion object {
        val None = MediaSelectionAccess(false, { false }, {})
    }
}

/** Hilt-scoped to the activity, so the explorer location outlives route switches. */
@Composable
internal fun rememberFolderExplorerViewModel(): FolderExplorerViewModel = viewModel()

/** The "On device" surface: the folder explorer bound to its view model. */
@Composable
internal fun FolderExplorerHost(
    viewModel: FolderExplorerViewModel,
    library: FolderLibrary,
    thumbnails: ThumbnailLoader?,
    snackbarHostState: SnackbarHostState,
    onOpenFiles: (AlbumSummary) -> Unit,
    onOpenMedia: (TimelineMedia, FolderMediaScope) -> Unit,
    onOpenStorageAccess: () -> Unit,
    onMoveFolder: (FolderCardUi) -> Unit,
    onCopyFolder: (FolderCardUi) -> Unit,
    /** The gallery selection that carries the file actions; the explorer only ticks and reads it. */
    mediaSelection: MediaSelectionAccess,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val active by viewModel.advancedActive.collectAsState()
    val revoked by viewModel.revokedWithHidden.collectAsState()
    val newFolderOpen by viewModel.newFolderOpen.collectAsState()
    val viewOptionsOpen by viewModel.viewOptionsOpen.collectAsState()
    val chrome by viewModel.chrome.collectAsState()
    val selectedFolders by viewModel.selectedFolders.collectAsState()
    val media = viewModel.media.collectAsLazyPagingItems()
    LaunchedEffect(viewModel, library) { viewModel.bind(library) }
    LaunchedEffect(viewModel) {
        viewModel.restoreLastFolder()
        viewModel.trackLastFolder()
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message -> showFolderMessage(context, snackbarHostState, message, onOpenStorageAccess) }
    }
    val actions = remember(viewModel, active) {
        if (!active) {
            null
        } else {
            FolderExplorerActions(
                onHide = viewModel::hide,
                onUnhide = viewModel::unhide,
                onRename = viewModel::rename,
                onDelete = viewModel::delete,
                onMove = onMoveFolder,
                onCopy = onCopyFolder,
                onToggleShowEvenIfHidden = viewModel::toggleShowEvenIfHidden,
                onOpenHiddenItem = { item ->
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(item.uri), item.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(view)
                    } catch (_: ActivityNotFoundException) {
                        // No viewer for this type: nothing to open.
                    }
                },
            )
        }
    }
    val browse = remember(viewModel, chrome.searching) {
        FolderBrowseActions(
            onTogglePin = viewModel::togglePin,
            onSetExcluded = viewModel::setExcluded,
            onQueryChange = viewModel::setQuery,
            onCloseSearch = viewModel::closeSearch,
            searching = chrome.searching,
        )
    }
    val selection = ExplorerSelection(
        folders = selectedFolders,
        mediaSelecting = mediaSelection.selecting,
        isMediaSelected = mediaSelection.isSelected,
        onToggleFolder = viewModel::toggleFolder,
        onToggleMedia = mediaSelection.toggle,
        onSelectAllFolders = viewModel::selectAllFolders,
        onClearFolders = viewModel::clearFolderSelection,
        onPinFolders = viewModel::pinSelected,
        onExcludeFolders = viewModel::excludeSelected,
        onHideFolders = if (active) viewModel::hideSelected else null,
        onDeleteFolders = if (active) viewModel::deleteSelected else null,
    )
    FolderExplorerContent(
        state = state,
        thumbnails = thumbnails,
        onEnter = viewModel::open,
        onOpenFiles = onOpenFiles,
        modifier = modifier,
        actions = actions,
        media = media,
        onMediaClick = onOpenMedia,
        onRetry = viewModel::retry,
        revokedWithHidden = revoked,
        onUnhideFolders = onOpenStorageAccess,
        browse = browse,
        selection = selection,
        scrollMemory = viewModel.scrollMemory,
    )
    if (viewOptionsOpen) {
        FolderViewOptionsDialog(
            options = viewModel.currentViewOptions,
            onChange = viewModel::setViewOptions,
            onDismiss = viewModel::dismissViewOptions,
        )
    }
    if (newFolderOpen) {
        val siblings = (state as? FolderExplorerUiState.Folder)?.folders?.map { it.name }.orEmpty()
        NewFolderDialog(siblings = siblings, onDismiss = viewModel::dismissNewFolder, onCreate = viewModel::createFolder)
    }
}

/** Shows [message] in the snackbar; "not granted" offers a shortcut to the permission screen. */
internal suspend fun showFolderMessage(
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    message: FolderMessage,
    onOpenStorageAccess: () -> Unit,
) {
    val text = context.getString(message.textRes())
    if (message == FolderMessage.NotGranted) {
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = context.getString(R.string.folder_op_grant_action),
        )
        if (result == SnackbarResult.ActionPerformed) onOpenStorageAccess()
    } else {
        snackbarHostState.showSnackbar(text)
    }
}

internal fun FolderMessage.textRes(): Int = when (this) {
    is FolderMessage.Done -> when (op) {
        FolderOp.Hide -> R.string.folder_op_hidden
        FolderOp.Unhide -> R.string.folder_op_unhidden
        FolderOp.Rename -> R.string.folder_op_renamed
        FolderOp.Delete -> R.string.folder_op_deleted
        FolderOp.Create -> R.string.folder_op_created
        FolderOp.Move -> R.string.folder_op_moved
        FolderOp.Copy -> R.string.folder_op_copied
    }
    FolderMessage.NotGranted -> R.string.folder_op_not_granted
    FolderMessage.AlreadyHiddenByOthers -> R.string.folder_op_hidden_by_others
    is FolderMessage.Failed -> R.string.folder_op_failed
}
