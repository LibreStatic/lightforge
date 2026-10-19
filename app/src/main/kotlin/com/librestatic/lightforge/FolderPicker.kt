package com.librestatic.lightforge

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.librestatic.lightforge.feature.folders.RenameFolderDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.folders.FolderExplorerUiState
import com.librestatic.lightforge.feature.folders.FolderLocation
import com.librestatic.lightforge.feature.folders.FolderPickerContent
import com.librestatic.lightforge.feature.folders.NewFolderDialog
import com.librestatic.lightforge.feature.folders.PickPurpose

/** What the destination picker was opened for; kept by the gallery view model until the user decides. */
internal sealed interface FolderPickRequest {
    val purpose: PickPurpose

    /** The folders that travel with this request; they are never offered as a destination. */
    val sourceFolders: List<FolderLocation> get() = emptyList()

    /** Files travel with it. */
    val withFiles: Boolean get() = false

    data class Items(override val purpose: PickPurpose, val keys: List<MediaKey>) : FolderPickRequest {
        override val withFiles: Boolean get() = true
    }

    data class Folder(val source: FolderLocation, val copy: Boolean = false) : FolderPickRequest {
        override val purpose: PickPurpose get() = if (copy) PickPurpose.CopyFolder else PickPurpose.MoveFolder
        override val sourceFolders: List<FolderLocation> get() = listOf(source)
    }

    /** Several folders, with or without ticked files, moved or copied to one destination (plan 7.8). */
    data class Mixed(val folders: List<FolderLocation>, val keys: List<MediaKey>, val copy: Boolean) : FolderPickRequest {
        override val purpose: PickPurpose get() = if (copy) PickPurpose.Copy else PickPurpose.Move
        override val sourceFolders: List<FolderLocation> get() = folders
        override val withFiles: Boolean get() = keys.isNotEmpty()
    }
}

/** A finished advanced operation, shown once as a message. */
internal sealed interface AdvancedOpEvent {
    data class Transfer(val result: TransferResult) : AdvancedOpEvent

    /** [leaveAlbum]: the folder that was open as an album no longer exists under that identity. */
    data class Folder(val message: FolderMessage, val leaveAlbum: Boolean = false) : AdvancedOpEvent
}

internal data class TransferProgress(val purpose: TransferPurpose, val done: Int, val total: Int)

/** The folder being renamed from an album screen. */
internal data class FolderRenameDraft(val target: FolderTarget, val currentName: String)

/**
 * The destination picker as a full-screen dialog over whatever screen asked for it. It runs on its own
 * explorer instance (separate saved location), so the "On device" browse location is untouched.
 */
@Composable
internal fun FolderPickerHost(
    request: FolderPickRequest,
    start: FolderLocation,
    thumbnails: ThumbnailLoader?,
    onPick: (FolderLocation) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val picker: FolderExplorerViewModel = viewModel(key = PickerViewModelKey)
    val state by picker.uiState.collectAsState()
    val canCreate by picker.chrome.collectAsState()
    val newFolderOpen by picker.newFolderOpen.collectAsState()
    var opened by rememberSaveable(request) { mutableStateOf(false) }
    LaunchedEffect(request, opened) {
        if (!opened) {
            picker.open(start)
            opened = true
        }
    }
    LaunchedEffect(picker) {
        picker.messages.collect { Toast.makeText(context, resources.getString(it.textRes()), Toast.LENGTH_SHORT).show() }
    }
    val goBack = { if (!picker.navigateUp()) onCancel() }
    Dialog(
        onDismissRequest = goBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FolderPickerContent(
            purpose = request.purpose,
            sourceFolders = request.sourceFolders,
            withFiles = request.withFiles,
            state = state,
            thumbnails = thumbnails,
            onEnter = picker::open,
            onBack = goBack,
            onNewFolder = if (canCreate.canCreateFolder) picker::beginNewFolder else null,
            onConfirm = onPick,
            onRetry = picker::retry,
        )
        if (newFolderOpen) {
            val siblings = (state as? FolderExplorerUiState.Folder)?.folders?.map { it.name }.orEmpty()
            NewFolderDialog(siblings = siblings, onDismiss = picker::dismissNewFolder, onCreate = picker::createFolder)
        }
    }
}

/** The one message a finished transfer is summed up by. */
internal enum class TransferOutcome { Done, Nothing, Partial, Failed, NotGranted, Inactive }

internal fun TransferResult.outcome(): TransferOutcome = when {
    inactive -> TransferOutcome.Inactive
    notGranted -> TransferOutcome.NotGranted
    failed == 0 && moved + copied + relocated + awaitingDelete.size == 0 -> TransferOutcome.Nothing
    failed == 0 -> TransferOutcome.Done
    moved + copied + relocated + awaitingDelete.size + skipped > 0 -> TransferOutcome.Partial
    else -> TransferOutcome.Failed
}

/**
 * Everything the advanced move, copy and rename flows put on screen above the current route: the
 * destination picker, the progress of a running batch, the folder rename prompt and the result messages.
 * [onLeaveAlbum] runs when the open album was a folder that has just been renamed.
 */
@Composable
internal fun AdvancedOperationsHost(
    viewModel: GalleryViewModel,
    folderExplorer: FolderExplorerViewModel,
    thumbnails: ThumbnailLoader?,
    snackbarHostState: SnackbarHostState,
    onOpenStorageAccess: () -> Unit,
    onLeaveAlbum: () -> Unit,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val request by viewModel.folderPickRequest.collectAsState()
    val last by viewModel.lastPickedDestination.collectAsState()
    val progress by viewModel.transferProgress.collectAsState()
    val rename by viewModel.folderRename.collectAsState()
    val currentOnLeave by rememberUpdatedState(onLeaveAlbum)
    LaunchedEffect(viewModel) {
        viewModel.advancedEvents.collect { event ->
            when (event) {
                is AdvancedOpEvent.Folder -> {
                    folderExplorer.diskChanged()
                    folderExplorer.clearFolderSelection()
                    showFolderMessage(context, snackbarHostState, event.message, onOpenStorageAccess)
                    if (event.leaveAlbum) currentOnLeave()
                }
                is AdvancedOpEvent.Transfer -> {
                    folderExplorer.diskChanged()
                    folderExplorer.clearFolderSelection()
                    val result = event.result
                    val done = result.moved + result.copied + result.relocated + result.awaitingDelete.size
                    when (result.outcome()) {
                        TransferOutcome.Inactive -> Unit
                        TransferOutcome.NotGranted ->
                            showFolderMessage(context, snackbarHostState, FolderMessage.NotGranted, onOpenStorageAccess)
                        TransferOutcome.Nothing -> snackbarHostState.showSnackbar(resources.getString(R.string.adv_transfer_nothing))
                        TransferOutcome.Failed -> snackbarHostState.showSnackbar(resources.getString(R.string.adv_transfer_failed))
                        TransferOutcome.Partial -> snackbarHostState.showSnackbar(
                            resources.getString(R.string.adv_transfer_partial, done + result.skipped, result.failed),
                        )
                        TransferOutcome.Done -> snackbarHostState.showSnackbar(
                            if (result.purpose == TransferPurpose.Copy) {
                                resources.getQuantityString(R.plurals.adv_transfer_copied, done, done)
                            } else {
                                resources.getQuantityString(R.plurals.adv_transfer_moved, done, done)
                            },
                        )
                    }
                }
            }
        }
    }
    request?.let { pick ->
        FolderPickerHost(
            request = pick,
            start = last,
            thumbnails = thumbnails,
            onPick = viewModel::completeFolderPick,
            onCancel = viewModel::cancelFolderPick,
        )
    }
    progress?.let { running ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(
                    stringResource(
                        if (running.purpose == TransferPurpose.Copy) R.string.adv_transfer_progress_copy else R.string.adv_transfer_progress_move,
                        (running.done + 1).coerceAtMost(running.total),
                        running.total,
                    ),
                )
            },
            text = {
                LinearProgressIndicator(
                    progress = { if (running.total == 0) 0f else running.done.toFloat() / running.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
        )
    }
    rename?.let { draft ->
        RenameFolderDialog(
            currentName = draft.currentName,
            siblings = emptyList(),
            onDismiss = viewModel::dismissFolderRename,
            onConfirm = viewModel::confirmFolderRename,
        )
    }
}

private const val PickerViewModelKey = "folder-picker"
