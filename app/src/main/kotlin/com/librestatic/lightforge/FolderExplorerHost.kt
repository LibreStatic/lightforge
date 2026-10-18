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
import com.librestatic.lightforge.feature.folders.FolderExplorerActions
import com.librestatic.lightforge.feature.folders.FolderExplorerContent
import com.librestatic.lightforge.feature.folders.FolderExplorerUiState
import com.librestatic.lightforge.feature.folders.FolderMediaScope
import com.librestatic.lightforge.feature.folders.NewFolderDialog

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val active by viewModel.advancedActive.collectAsState()
    val revoked by viewModel.revokedWithHidden.collectAsState()
    val newFolderOpen by viewModel.newFolderOpen.collectAsState()
    val media = viewModel.media.collectAsLazyPagingItems()
    LaunchedEffect(viewModel, library) { viewModel.bind(library) }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
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
    )
    if (newFolderOpen) {
        val siblings = (state as? FolderExplorerUiState.Folder)?.folders?.map { it.name }.orEmpty()
        NewFolderDialog(siblings = siblings, onDismiss = viewModel::dismissNewFolder, onCreate = viewModel::createFolder)
    }
}

private fun FolderMessage.textRes(): Int = when (this) {
    is FolderMessage.Done -> when (op) {
        FolderOp.Hide -> R.string.folder_op_hidden
        FolderOp.Unhide -> R.string.folder_op_unhidden
        FolderOp.Rename -> R.string.folder_op_renamed
        FolderOp.Delete -> R.string.folder_op_deleted
        FolderOp.Create -> R.string.folder_op_created
    }
    FolderMessage.NotGranted -> R.string.folder_op_not_granted
    FolderMessage.AlreadyHiddenByOthers -> R.string.folder_op_hidden_by_others
    is FolderMessage.Failed -> R.string.folder_op_failed
}
