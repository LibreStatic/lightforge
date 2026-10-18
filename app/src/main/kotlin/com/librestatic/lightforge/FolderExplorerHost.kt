package com.librestatic.lightforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.folders.FolderExplorerContent

/** Hilt-scoped to the activity, so the explorer location outlives route switches. */
@Composable
internal fun rememberFolderExplorerViewModel(): FolderExplorerViewModel = viewModel()

/** The "On device" surface: the folder explorer bound to its view model. */
@Composable
internal fun FolderExplorerHost(
    viewModel: FolderExplorerViewModel,
    thumbnails: ThumbnailLoader?,
    onOpenFiles: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    FolderExplorerContent(
        state = state,
        thumbnails = thumbnails,
        onEnter = viewModel::open,
        onOpenFiles = onOpenFiles,
        modifier = modifier,
    )
}
