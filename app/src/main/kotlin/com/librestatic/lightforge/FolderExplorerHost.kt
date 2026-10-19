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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
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
import com.librestatic.lightforge.feature.folders.FolderCoverDialog
import com.librestatic.lightforge.feature.folders.FolderCardUi
import com.librestatic.lightforge.feature.folders.FolderViewOptionsDialog
import com.librestatic.lightforge.feature.folders.FolderExplorerActions
import com.librestatic.lightforge.feature.folders.FolderExplorerContent
import com.librestatic.lightforge.feature.folders.FolderExplorerUiState
import com.librestatic.lightforge.feature.folders.FolderMediaScope
import com.librestatic.lightforge.feature.folders.NewFolderDialog

/** The slice of the gallery selection the explorer needs: whether files are selected, which, and what mixed actions do to them. */
internal class MediaSelectionAccess(
    val selecting: Boolean,
    val isSelected: (MediaKey) -> Boolean,
    val toggle: (TimelineMedia) -> Unit,
    /** How many files are selected. */
    val count: Long = 0,
    /** Ticks every file of the folder being shown; a no-op where the level lists none. */
    val selectAll: () -> Unit = {},
    val clear: () -> Unit = {},
    /** Moves the selected files to the Trash (the system asks for its own approval). */
    val trash: () -> Unit = {},
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
    /** Opens the destination picker for the ticked folders together with the ticked files. */
    onTransferSelection: (List<FolderCardUi>, move: Boolean) -> Unit,
    /** Opens the album chooser for everything in the folders. */
    onAddFoldersToAlbum: (List<FolderCardUi>) -> Unit,
    /** The gallery selection that carries the file actions; the explorer only ticks and reads it. */
    mediaSelection: MediaSelectionAccess,
    /** Runs a destructive action behind the destructive-action lock (biometric prompt when it is on). */
    runDestructive: (() -> Unit) -> Unit,
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
    val coverTarget by viewModel.coverTarget.collectAsState()
    val coverCandidates = viewModel.coverCandidates.collectAsLazyPagingItems()
    val media = viewModel.media.collectAsLazyPagingItems()
    LaunchedEffect(viewModel, library) { viewModel.bind(library) }
    LaunchedEffect(viewModel) {
        viewModel.restoreLastFolder()
        viewModel.trackLastFolder()
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message -> showFolderMessage(context, snackbarHostState, message, onOpenStorageAccess) }
    }
    val currentRunDestructive by rememberUpdatedState(runDestructive)
    val currentMediaSelection by rememberUpdatedState(mediaSelection)
    val canPinShortcut = remember(context) { canPinFolderShortcut(context) }
    val actions = remember(viewModel, active) {
        if (!active) {
            null
        } else {
            FolderExplorerActions(
                onHide = viewModel::hide,
                onUnhide = viewModel::unhide,
                onRename = viewModel::rename,
                onDelete = { cards, alsoTrashFiles ->
                    currentRunDestructive {
                        viewModel.deleteFolders(cards)
                        if (alsoTrashFiles) currentMediaSelection.trash()
                    }
                },
                summarizeDelete = viewModel::summarizeDelete,
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
    val scope = rememberCoroutineScope()
    val currentOnTransfer by rememberUpdatedState(onTransferSelection)
    val currentOnAlbum by rememberUpdatedState(onAddFoldersToAlbum)
    val browse = remember(viewModel, chrome.searching, canPinShortcut) {
        FolderBrowseActions(
            onTogglePin = viewModel::togglePin,
            onSetExcluded = viewModel::setExcluded,
            onQueryChange = viewModel::setQuery,
            onCloseSearch = viewModel::closeSearch,
            searching = chrome.searching,
            onChooseCover = viewModel::beginCover,
            onResetCover = viewModel::resetCover,
            onCreateShortcut = if (canPinShortcut) {
                { card ->
                    viewModel.clearFolderSelection()
                    if (!pinFolderShortcut(context, card.name, card.location)) {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.folder_shortcut_failed)) }
                    }
                }
            } else {
                null
            },
            onAddFoldersToAlbum = { cards -> currentOnAlbum(cards) },
            onTransfer = { cards, move -> currentOnTransfer(cards, move) },
        )
    }
    val selection = ExplorerSelection(
        folders = selectedFolders,
        mediaSelecting = mediaSelection.selecting,
        fileCount = mediaSelection.count,
        advanced = active,
        isMediaSelected = mediaSelection.isSelected,
        onToggleFolder = viewModel::toggleFolder,
        onToggleMedia = mediaSelection.toggle,
        onSelectAll = {
            viewModel.selectAllFolders()
            mediaSelection.selectAll()
        },
        onClear = {
            viewModel.clearFolderSelection()
            mediaSelection.clear()
        },
        canPinShortcut = canPinShortcut,
        onPinFolders = viewModel::pinSelected,
        onExcludeFolders = viewModel::excludeSelected,
        onHideFolders = if (active) viewModel::hideSelected else null,
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
    coverTarget?.let { target ->
        FolderCoverDialog(
            folderName = target.name,
            media = coverCandidates,
            thumbnails = thumbnails,
            onPick = viewModel::setCover,
            onReset = if (target.hasCustomCover) ({ viewModel.resetCover(target) }) else null,
            onDismiss = viewModel::dismissCover,
        )
    }
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
    FolderMessage.FolderHasTrash -> R.string.folder_op_has_trash
    is FolderMessage.Failed -> R.string.folder_op_failed
}
