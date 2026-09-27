package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GalleryTopAppBar

/**
 * Editor top bar: back, tappable project title (opens rename), an autosave subtitle, Undo/Redo,
 * a filled Export action, and an overflow limited to the less frequent actions. Replaces the
 * always-visible project-name field and the manual Save action; autosave (`scheduleSave`) covers
 * persistence and `persistCurrent` flushes it on the way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfEditorTopBar(
    project: PdfProject,
    state: PdfStudioState,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onQueue: () -> Unit,
    onDetails: () -> Unit,
    onPortable: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    val subtitle =
        stringResource(
            when (state.saveState) {
                PdfSaveState.Saving -> R.string.pdf_savestate_saving
                PdfSaveState.Error -> R.string.pdf_savestate_error
                PdfSaveState.Idle, PdfSaveState.Saved -> R.string.pdf_savestate_saved
            }
        )
    GalleryTopAppBar(
        title = project.name,
        subtitle = subtitle,
        onBack = onBack,
        navigationContentDescription = stringResource(R.string.pdf_projects),
        onTitleClick = onRename,
        actions = {
            if (state.backgroundBusy) {
                GalleryLoadingIndicator(Modifier.size(20.dp).padding(end = 8.dp))
            }
            val undoLabel = stringResource(R.string.pdf_undo)
            IconButton(
                onClick = onUndo,
                enabled = state.canUndo && !state.editorLocked,
                modifier = Modifier.semantics { contentDescription = undoLabel },
            ) {
                Icon(GalleryIcons.Undo, contentDescription = null)
            }
            val redoLabel = stringResource(R.string.pdf_redo)
            IconButton(
                onClick = onRedo,
                enabled = state.canRedo && !state.editorLocked,
                modifier = Modifier.semantics { contentDescription = redoLabel },
            ) {
                Icon(GalleryIcons.Redo, contentDescription = null)
            }
            Button(onClick = onExport, enabled = !state.editorLocked) {
                Text(stringResource(R.string.pdf_export))
            }
            Box {
                val actionsLabel = stringResource(R.string.pdf_project_actions)
                IconButton(
                    onClick = { showActions = true },
                    modifier = Modifier.semantics { contentDescription = actionsLabel },
                ) {
                    Icon(GalleryIcons.More, contentDescription = null)
                }
                DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_queue)) },
                        onClick = {
                            showActions = false
                            onQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_project_details)) },
                        onClick = {
                            showActions = false
                            onDetails()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_portable)) },
                        enabled = !state.editorLocked,
                        onClick = {
                            showActions = false
                            onPortable()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfLibraryTopBar(onBack: () -> Unit, onQueue: () -> Unit) {
    GalleryTopAppBar(
        title = stringResource(R.string.pdf_studio),
        onBack = onBack,
        navigationContentDescription = stringResource(R.string.pdf_close),
        actions = {
            val queueLabel = stringResource(R.string.pdf_queue)
            IconButton(
                onClick = onQueue,
                modifier = Modifier.semantics { contentDescription = queueLabel },
            ) {
                Icon(GalleryIcons.Folder, contentDescription = null)
            }
        },
    )
}

@Composable
internal fun InsertControls(s: PdfStudioState, portable: () -> Unit, import: () -> Unit) {
    Text(stringResource(R.string.pdf_insert), style = MaterialTheme.typography.titleMedium)
    Button(onClick = import, enabled = !s.editorLocked) {
        Text(stringResource(R.string.pdf_importfiles))
    }
    TextButton(onClick = portable, enabled = !s.editorLocked) {
        Text(stringResource(R.string.pdf_portable))
    }
}

/** The editor body: side panels (expanded layouts) or a bottom sheet/tool bar (compact), around
 * the fixed-position canvas. Feedback is drawn as an overlay so it never displaces the canvas. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PdfEditorBody(
    vm: PdfStudioViewModel,
    state: PdfStudioState,
    project: PdfProject,
    layout: PdfStudioLayoutPolicy,
    panel: Int,
    onPanelChange: (Int) -> Unit,
    onDeletePages: () -> Unit,
    onPortable: () -> Unit,
    onShowQueue: () -> Unit,
    onLaunchImport: () -> Unit,
    onAdjustImage: () -> Unit,
    modifier: Modifier = Modifier,
    feedback: @Composable () -> Unit,
) {
    Box(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (layout.expanded)
                    Column(
                        Modifier.width(156.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        PdfPagesPanel(vm, state, onDeletePages)
                    }
                PdfCanvas(
                    project.pages[state.page],
                    state.image,
                    vm,
                    Modifier.weight(1f).fillMaxHeight(),
                    state.editorLocked,
                    onAdjustImage = onAdjustImage,
                )
                if (layout.expanded)
                    Column(
                        Modifier.width(280.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        InsertControls(state, portable = onPortable, import = onLaunchImport)
                        PdfLayoutPanel(vm, state)
                        PdfAdjustPanel(vm, state)
                    }
            }
            if (!layout.expanded || panel == 3) {
                if (panel >= 0)
                    ModalBottomSheet(onDismissRequest = { onPanelChange(-1) }) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            when (panel) {
                                0 -> PdfPagesPanel(vm, state, onDeletePages)
                                1 ->
                                    InsertControls(
                                        state,
                                        portable = onPortable,
                                        import = {
                                            onPanelChange(-1)
                                            onLaunchImport()
                                        },
                                    )
                                2 -> PdfLayoutPanel(vm, state)
                                3 -> PdfAdjustPanel(vm, state)
                            }
                        }
                    }
            }
            // Capped and independently scrollable so several stacked cards (gallery issue, a
            // message/readyExport notice, the busy row) at 200% font never overflow the screen;
            // the canvas above keeps its fixed position regardless.
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .heightIn(max = maxHeight * 0.6f)
                    .verticalScroll(rememberScrollState())
            ) {
                feedback()
            }
        }
    }
    if (!layout.expanded && layout.compactChrome) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pdfToolBarTabs().forEachIndexed { n, tab ->
                    FilterChip(
                        selected = panel == n,
                        onClick = { onPanelChange(n) },
                        enabled = !state.editorLocked,
                        label = { Text(stringResource(tab.second), maxLines = 1) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    } else if (!layout.expanded)
        NavigationBar {
            pdfToolBarTabs().forEachIndexed { n, tab ->
                NavigationBarItem(
                    selected = panel == n,
                    onClick = { onPanelChange(n) },
                    icon = { Icon(tab.first, contentDescription = null) },
                    label = { Text(stringResource(tab.second)) },
                    enabled = !state.editorLocked,
                )
            }
        }
}

private fun pdfToolBarTabs() =
    listOf(
        GalleryIcons.Layers to R.string.pdf_pages,
        GalleryIcons.Plus to R.string.pdf_insert,
        GalleryIcons.Grid to R.string.pdf_design,
        GalleryIcons.Tune to R.string.pdf_adjust,
    )
