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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GalleryTopAppBar

/**
 * Editor top bar: back, tappable project title (opens rename), an autosave subtitle, Undo/Redo,
 * an Export action, and an overflow limited to the less frequent actions. Replaces the
 * always-visible project-name field and the manual Save action; autosave (`scheduleSave`) covers
 * persistence and `persistCurrent` flushes it on the way out.
 *
 * The title must always stay legible: a bare [GalleryTopAppBar] gives it only whatever width is
 * left over after the navigation icon and the actions row measure themselves, which can reach
 * zero once Undo/Redo/Export/overflow (and Export's own label) don't fit — exactly what happened
 * at narrow widths and large font scales. So Export collapses from a labelled button to an
 * icon-only button (same contentDescription) whenever its label would leave the title below its
 * minimum width; Undo/Redo/overflow always stay as icon buttons since they are core and already at
 * their minimum size. The actions row is never width-capped, so no action is ever clipped.
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
    watchedJob: PdfExportJob? = null,
    onReopenProgress: () -> Unit = {},
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        // Icon buttons (nav, Undo, Redo, overflow) keep their 48dp minimum touch target
        // regardless of font scale; only text-bearing controls (title and a labelled Export
        // button) grow with it, so the budget below scales just the parts that actually do.
        val iconSlot = 48.dp
        // nav + Undo + Redo + overflow, plus the app bar's own start/end/title insets.
        val fixedChrome = iconSlot * 4 + 24.dp
        val minTitleWidth = 96.dp * fontScale.coerceIn(1f, 1.5f)
        // A labelled filled button: 24dp horizontal padding on each side plus the label.
        val exportLabelWidth = 48.dp + 56.dp * fontScale.coerceAtLeast(1f)
        // Collapse Export to an icon before the title would drop below its minimum width.
        val exportCollapsed = maxWidth - fixedChrome - exportLabelWidth < minTitleWidth
        GalleryTopAppBar(
            title = project.name,
            subtitle = subtitle,
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.pdf_projects),
            onTitleClick = onRename,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.backgroundBusy) {
                        val exportingPhases =
                            setOf(PdfExportPhase.Queued, PdfExportPhase.Running, PdfExportPhase.Publishing)
                        if (watchedJob != null && watchedJob.phase in exportingPhases) {
                            val chipLabel =
                                stringResource(
                                    R.string.pdf_export_progress_chip,
                                    watchedJob.completed,
                                    watchedJob.total,
                                )
                            IconButton(
                                onClick = onReopenProgress,
                                modifier = Modifier.semantics { contentDescription = chipLabel },
                            ) {
                                GalleryLoadingIndicator(Modifier.size(20.dp))
                            }
                        } else {
                            GalleryLoadingIndicator(Modifier.size(20.dp).padding(end = 8.dp))
                        }
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
                    val exportLabel = stringResource(R.string.pdf_export)
                    if (exportCollapsed) {
                        FilledIconButton(
                            onClick = onExport,
                            enabled = !state.editorLocked,
                            modifier = Modifier.semantics { contentDescription = exportLabel },
                        ) {
                            Icon(GalleryIcons.Download, contentDescription = null)
                        }
                    } else {
                        Button(onClick = onExport, enabled = !state.editorLocked) {
                            Text(exportLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                        val actionsLabel = stringResource(R.string.pdf_project_actions)
                        IconButton(
                            onClick = { showActions = true },
                            modifier = Modifier.semantics { contentDescription = actionsLabel },
                        ) {
                            Icon(GalleryIcons.More, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showActions,
                            onDismissRequest = { showActions = false },
                        ) {
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
                }
            },
        )
    }
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
                Icon(GalleryIcons.History, contentDescription = null)
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
    // A single NavigationBar for all four destinations at every width/font scale: it evenly
    // divides the available width among the items instead of a horizontally-scrolling chip row,
    // so labels wrap to a second line (never clipped at the edge with no scroll affordance) even
    // at 200% font, while keeping 48dp touch targets and the same tappable-by-text labels.
    if (!layout.expanded)
        NavigationBar {
            pdfToolBarTabs().forEachIndexed { n, tab ->
                NavigationBarItem(
                    selected = panel == n,
                    onClick = { onPanelChange(n) },
                    icon = { Icon(tab.first, contentDescription = null) },
                    label = {
                        // One line that shrinks to fit: at large font scales a wrapping label
                        // would break inside words ("Página/s"), which reads as broken UI.
                        Text(
                            stringResource(tab.second),
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            autoSize =
                                TextAutoSize.StepBased(
                                    minFontSize = 9.sp,
                                    maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                                ),
                        )
                    },
                    enabled = !state.editorLocked,
                    modifier = Modifier.heightIn(min = 48.dp),
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
