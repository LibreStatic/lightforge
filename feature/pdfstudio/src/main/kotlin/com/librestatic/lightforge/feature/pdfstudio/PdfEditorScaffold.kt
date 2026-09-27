package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar

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
    onShowShortcuts: () -> Unit = {},
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_shortcuts)) },
                                leadingIcon = {
                                    Icon(GalleryIcons.Keyboard, contentDescription = null)
                                },
                                onClick = {
                                    showActions = false
                                    onShowShortcuts()
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
internal fun PdfLibraryTopBar(
    onBack: () -> Unit,
    onQueue: () -> Unit,
    exportJobs: List<PdfExportJob> = emptyList(),
) {
    // A dot appears only when an export needs the user's attention: it failed, or it is a
    // legacy "Ready" job from before destination-first export (Phase B) that still needs a
    // destination chosen. A running/queued/published job never lights the dot.
    val needsAction =
        exportJobs.any { it.phase in setOf(PdfExportPhase.Failed, PdfExportPhase.Ready) }
    GalleryTopAppBar(
        title = stringResource(R.string.pdf_studio),
        onBack = onBack,
        navigationContentDescription = stringResource(R.string.pdf_close),
        actions = {
            val queueLabel =
                if (needsAction) stringResource(R.string.pdf_library_history_needs_action)
                else stringResource(R.string.pdf_queue)
            IconButton(
                onClick = onQueue,
                modifier = Modifier.semantics { contentDescription = queueLabel },
            ) {
                if (needsAction) {
                    BadgedBox(badge = { Badge() }) {
                        Icon(GalleryIcons.History, contentDescription = null)
                    }
                } else {
                    Icon(GalleryIcons.History, contentDescription = null)
                }
            }
        },
    )
}

@Composable
internal fun InsertControls(
    vm: PdfStudioViewModel,
    s: PdfStudioState,
    portable: () -> Unit,
    import: () -> Unit,
    // Round-2 fix: the compact bottom-sheet Insert panel never closed itself after adding a text
    // (unlike Import, whose own call site already dismisses the sheet before launching the picker)
    // - the sheet's scrim stayed up over the canvas, hiding the newly-added text and its inline
    // editor entirely, and swallowing/misrouting any tap meant for the canvas underneath. Defaults
    // to a no-op for the expanded/hinge/tabletop call sites, which aren't sheets.
    onAddedText: () -> Unit = {},
) {
    Text(stringResource(R.string.pdf_insert), style = MaterialTheme.typography.titleMedium)
    Button(onClick = import, enabled = !s.editorLocked) {
        Text(stringResource(R.string.pdf_importfiles))
    }
    // Item 4: "Text" in Insert, disabled with an explanation on an imported-PDF page (texts
    // aren't allowed there) or once the page already has 24 elements (images + texts).
    val page = s.project?.pages?.getOrNull(s.page)
    val importedExplanation = stringResource(R.string.pdf_imported_page_note)
    val fullExplanation = stringResource(R.string.pdf_page_full_note)
    val textDisabledReason =
        when {
            page == null -> null
            page.source != null -> importedExplanation
            !PdfMediaPlacement.hasRoomForOneMore(page.images.size + page.texts.size) -> fullExplanation
            else -> null
        }
    val textPlaceholder = stringResource(R.string.pdf_text_placeholder)
    TextButton(
        onClick = {
            vm.addText(textPlaceholder)
            onAddedText()
        },
        enabled = !s.editorLocked && textDisabledReason == null,
    ) {
        Icon(GalleryIcons.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.pdf_add_text))
    }
    if (textDisabledReason != null)
        Text(
            textDisabledReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onExportSelectedPages: () -> Unit,
    onPortable: () -> Unit,
    onShowQueue: () -> Unit,
    onLaunchImport: () -> Unit,
    onAdjustImage: () -> Unit,
    onReplaceImage: () -> Unit,
    commands: PdfEditorCommandDispatcher,
    modifier: Modifier = Modifier,
    mediaSource: PdfMediaSource? = null,
    feedback: @Composable () -> Unit,
) {
    // Phase F item 1: a horizontal separating fold (device half-opened, laid flat) puts the
    // canvas in the top half and every tool (page strip + tool bar + panel content) in the
    // bottom half, with nothing drawn on the crease. This is a distinct composition from the
    // side-by-side rail/canvas/inspector Row below, which the hinge-less and vertical-hinge
    // (HingeSplit) modes share.
    if (layout.mode == PdfStudioLayoutMode.Tabletop) {
        PdfTabletopEditorBody(
            vm = vm,
            state = state,
            project = project,
            layout = layout,
            onDeletePages = onDeletePages,
            onExportSelectedPages = onExportSelectedPages,
            onPortable = onPortable,
            onLaunchImport = onLaunchImport,
            onAdjustImage = onAdjustImage,
            onReplaceImage = onReplaceImage,
            commands = commands,
            modifier = modifier,
            mediaSource = mediaSource,
            feedback = feedback,
        )
        return
    }
    // Phase F review fix (HingeSplit blocker): a vertical separating hinge gets its own layout,
    // whose pane widths are derived from the fold's actual left/right bounds instead of the fixed
    // 220/280dp rail/inspector widths below — those fixed widths made the canvas cross the hinge
    // whenever it didn't land near the middle of the window. The canvas (with rulers) goes alone
    // in whichever side is larger; the pages rail + inspector share the other, smaller side.
    if (layout.mode == PdfStudioLayoutMode.HingeSplit) {
        PdfHingeSplitEditorBody(
            vm = vm,
            state = state,
            project = project,
            layout = layout,
            onDeletePages = onDeletePages,
            onExportSelectedPages = onExportSelectedPages,
            onPortable = onPortable,
            onLaunchImport = onLaunchImport,
            onAdjustImage = onAdjustImage,
            onReplaceImage = onReplaceImage,
            commands = commands,
            modifier = modifier,
            mediaSource = mediaSource,
            feedback = feedback,
        )
        return
    }
    val sidePanelsVisible = layout.mode == PdfStudioLayoutMode.ExpandedThreePane
    Box(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (sidePanelsVisible)
                    Column(
                        Modifier.width(220.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        PdfPagesPanel(vm, state, onDeletePages, onExportSelectedPages, columns = 2)
                    }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // Item A: rulers along the canvas in expanded modes only, and only when the
                    // canvas keeps at least 120dp of height afterward (matches the existing probe
                    // threshold at 840x320 — see PdfRulers's own height budget check).
                    val showRulers = sidePanelsVisible
                    PdfCanvasWithRulers(
                        page = project.pages[state.page],
                        selected = state.image,
                        vm = vm,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        busy = state.editorLocked,
                        pageIndex = state.page,
                        pageCount = project.pages.size,
                        onAdjustImage = onAdjustImage,
                        onReplaceImage = onReplaceImage,
                        commands = commands,
                        showRulers = showRulers,
                    )
                    if (sidePanelsVisible)
                        PdfStatusBar(state = state, project = project, commands = commands)
                }
                if (sidePanelsVisible)
                    Column(
                        Modifier.width(280.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        PdfInspectorColumn(vm, state, project, mediaSource, onPortable, onLaunchImport)
                    }
            }
            if (!layout.expanded || panel == 3 || panel == 4) {
                if (panel >= 0)
                    ModalBottomSheet(
                        onDismissRequest = { onPanelChange(-1) },
                        // Fully expanded (D4 review fix): a partially expanded sheet hid the
                        // Layout panel's sticky "Apply to / Arrange" row and the Pages panel's
                        // selection contextual bar below the fold. M3 still renders the drag
                        // handle by default, so 'Drag handle' stays reachable for the scripts.
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            when (panel) {
                                0 -> PdfPagesPanel(vm, state, onDeletePages, onExportSelectedPages)
                                1 ->
                                    InsertControls(
                                        vm,
                                        state,
                                        portable = onPortable,
                                        import = {
                                            onPanelChange(-1)
                                            onLaunchImport()
                                        },
                                        onAddedText = { onPanelChange(-1) },
                                    )
                                2 -> PdfLayoutPanel(vm, state)
                                3 -> PdfAdjustPanel(vm, state)
                                4 -> PdfMediaPanel(vm, state, mediaSource)
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
    if (!layout.expanded) PdfPageStrip(vm, state, project)
    // A single NavigationBar for all four destinations at every width/font scale: it evenly
    // divides the available width among the items instead of a horizontally-scrolling chip row,
    // so labels wrap to a second line (never clipped at the edge with no scroll affordance) even
    // at 200% font, while keeping 48dp touch targets and the same tappable-by-text labels.
    if (!layout.expanded)
        NavigationBar {
            pdfToolBarTabs(hasMediaSource = mediaSource != null).forEachIndexed { n, tab ->
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

/** The expanded/hinge inspector's contents (Insert controls + Page/Photo/Media tabs + the
 * selected panel), factored out of [PdfEditorBody] so [PdfHingeSplitEditorBody] can lay it out in
 * its own, fold-derived pane instead of duplicating this logic. */
@Composable
private fun PdfInspectorColumn(
    vm: PdfStudioViewModel,
    state: PdfStudioState,
    project: PdfProject,
    mediaSource: PdfMediaSource?,
    onPortable: () -> Unit,
    onLaunchImport: () -> Unit,
) {
    // Expanded/hinge inspector tabs (Phase F item 3): Page / Photo / Media. Photo only exists
    // while an image is selected, and Media only when a PdfMediaSource was passed in; a manual
    // pick on either sticks until the selection changes (mirrors the existing Adjust-follows-
    // selection rule).
    var inspectorTab by rememberSaveable(project.pages[state.page].id) { mutableStateOf(0) }
    // Round-2 fix: this used to key only on state.image, so selecting a TEXT (not an image) left
    // the tab list without an "Adjust" entry at all in expanded/hinge layouts - state.selected
    // (Phase G1b's generalized image-or-text selection) covers both.
    val hasElementSelected = state.selected != null
    LaunchedEffect(state.selected) { if (hasElementSelected) inspectorTab = 1 }
    val tabs =
        buildList {
            add(stringResource(R.string.pdf_design) to 0)
            if (hasElementSelected) add(stringResource(R.string.pdf_adjust) to 1)
            if (mediaSource != null) add(stringResource(R.string.pdf_media) to 2)
        }
    // Review fix: on a 640dp-tall window the Insert section (a button + a link) pushed the
    // Media tab's grid down far enough that it started below the fold. It's collapsed away
    // while the Media tab is active - Media is now the discoverable way to add gallery/document
    // content, and "Import images / PDF" (a different, file-picker-based source) stays one tap
    // away on every other tab.
    if (inspectorTab != 2 || mediaSource == null)
        InsertControls(vm, state, portable = onPortable, import = onLaunchImport)
    if (tabs.size > 1) {
        val selected = tabs.indexOfFirst { it.second == inspectorTab }.coerceAtLeast(0)
        com.librestatic.lightforge.core.designsystem.GalleryExpressiveChoiceGroup(
            labels = tabs.map { it.first },
            selectedIndex = selected,
            onSelect = { inspectorTab = tabs[it].second },
            minimumItemWidth = 72.dp,
        )
        Spacer(Modifier.height(8.dp))
    }
    when {
        inspectorTab == 2 && mediaSource != null -> PdfMediaPanel(vm, state, mediaSource)
        inspectorTab == 1 && hasElementSelected -> PdfAdjustPanel(vm, state)
        else -> PdfLayoutPanel(vm, state)
    }
}

/**
 * HingeSplit posture (Phase F review fix, BLOCKER): a vertical separating hinge splits the window
 * into a left region `[0, hinge.left]` and a right region `[hinge.right, width]`; nothing is ever
 * drawn on the hinge itself. Unlike the fixed 220/280dp rail/inspector widths the hinge-less
 * [PdfEditorBody] path uses, both region widths here come straight from [layout]'s [foldInfo], so
 * the canvas can never cross the hinge regardless of where it physically sits. The canvas (with
 * rulers) goes alone in whichever region is larger; the pages rail + inspector share the other,
 * smaller region in one scrollable column (both already fit in [PdfStudioLayoutPolicy]'s
 * `MinHingePaneDp` floor, so stacking them is always legible, unlike trying to fit them
 * side-by-side in a possibly-280dp-wide pane).
 */
@Composable
private fun PdfHingeSplitEditorBody(
    vm: PdfStudioViewModel,
    state: PdfStudioState,
    project: PdfProject,
    layout: PdfStudioLayoutPolicy,
    onDeletePages: () -> Unit,
    onExportSelectedPages: () -> Unit,
    onPortable: () -> Unit,
    onLaunchImport: () -> Unit,
    onAdjustImage: () -> Unit,
    onReplaceImage: () -> Unit,
    commands: PdfEditorCommandDispatcher,
    modifier: Modifier = Modifier,
    mediaSource: PdfMediaSource? = null,
    feedback: @Composable () -> Unit,
) {
    Box(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fold = layout.foldInfo
            // Device review fix: derive both pane widths from the SAME pure function
            // (PdfHingeSplitGeometry.compute), which floors every width and subtracts an explicit
            // 8dp gap from the hinge-facing edge, instead of using the hinge's raw bounds
            // directly (that used to leave zero margin for dp->px rounding to shave the canvas
            // a fraction of a pixel into the hinge band).
            val panes =
                if (fold != null)
                    PdfHingeSplitGeometry.compute(maxWidth.value, fold.left.value, fold.right.value)
                else PdfHingeSplitGeometry.compute(maxWidth.value, maxWidth.value / 2, maxWidth.value / 2)
            val hingeWidth = fold?.hingeWidth ?: 0.dp
            val canvasOnLeft = panes.canvasOnLeft
            // The gap subtracted on each side of the hinge leaves the Row narrower than the full
            // window; giving that slack to the pane drawn SECOND (below, always sized by
            // `rightWidth` regardless of canvasOnLeft) is always safe - that pane's far edge is
            // the window's own outer edge, never the hinge - and avoids a dead empty strip at the
            // trailing edge instead.
            val leftoverDp =
                (maxWidth.value - panes.leftWidthDp - panes.rightWidthDp - hingeWidth.value)
                    .coerceAtLeast(0f)
            val leftWidth = panes.leftWidthDp.dp
            val rightWidth = (panes.rightWidthDp + leftoverDp).dp

            @Composable
            fun CanvasPane(w: androidx.compose.ui.unit.Dp) {
                Column(Modifier.width(w).fillMaxHeight()) {
                    PdfCanvasWithRulers(
                        page = project.pages[state.page],
                        selected = state.image,
                        vm = vm,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        busy = state.editorLocked,
                        pageIndex = state.page,
                        pageCount = project.pages.size,
                        onAdjustImage = onAdjustImage,
                        onReplaceImage = onReplaceImage,
                        commands = commands,
                        showRulers = true,
                    )
                    PdfStatusBar(state = state, project = project, commands = commands)
                }
            }

            @Composable
            fun ToolsPane(w: androidx.compose.ui.unit.Dp) {
                Column(
                    Modifier.width(w)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    PdfPagesPanel(vm, state, onDeletePages, onExportSelectedPages, columns = 2)
                    Spacer(Modifier.height(12.dp))
                    PdfInspectorColumn(vm, state, project, mediaSource, onPortable, onLaunchImport)
                }
            }

            Row(Modifier.fillMaxSize()) {
                if (canvasOnLeft) {
                    CanvasPane(leftWidth)
                    if (hingeWidth > 0.dp) Spacer(Modifier.width(hingeWidth).fillMaxHeight())
                    ToolsPane(rightWidth)
                } else {
                    ToolsPane(leftWidth)
                    if (hingeWidth > 0.dp) Spacer(Modifier.width(hingeWidth).fillMaxHeight())
                    CanvasPane(rightWidth)
                }
            }
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
}

/** Tabletop posture (Phase F item 1c): the device is half-opened and laid flat, so the canvas
 * sits in the top half, the page strip + tool tabs + the selected panel's content sit in the
 * bottom half, and a spacer sized to the hinge keeps the crease itself free of controls or
 * canvas content. Unlike the compact bottom-sheet flow, the panel content renders inline (a
 * sheet sliding up from the bottom edge of a horizontally split screen has nowhere sensible to
 * anchor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfTabletopEditorBody(
    vm: PdfStudioViewModel,
    state: PdfStudioState,
    project: PdfProject,
    layout: PdfStudioLayoutPolicy,
    onDeletePages: () -> Unit,
    onExportSelectedPages: () -> Unit,
    onPortable: () -> Unit,
    onLaunchImport: () -> Unit,
    onAdjustImage: () -> Unit,
    onReplaceImage: () -> Unit,
    commands: PdfEditorCommandDispatcher,
    modifier: Modifier,
    mediaSource: PdfMediaSource? = null,
    feedback: @Composable () -> Unit,
) {
    var panel by remember { mutableStateOf(0) }
    // The fold's bounds are window coordinates, but this body starts below the top app bar:
    // measure where it sits in the window so the canvas half ends exactly at the crease.
    val density = LocalDensity.current
    var bodyTopInWindow by remember { mutableStateOf(0.dp) }
    Box(
        modifier.onGloballyPositioned {
            bodyTopInWindow = with(density) { it.positionInWindow().y.toDp() }
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fold = layout.foldInfo
            val topHeight =
                fold?.top?.let { (it - bodyTopInWindow).coerceIn(0.dp, maxHeight) }
                    ?: (maxHeight / 2)
            val hingeHeight = fold?.hingeHeight ?: 0.dp
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(topHeight)) {
                    // Scope A: the tabletop canvas half gets rulers too, but only if that half is
                    // tall enough to spare the room (same 120dp floor as the other expanded modes).
                    PdfCanvasWithRulers(
                        page = project.pages[state.page],
                        selected = state.image,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        busy = state.editorLocked,
                        pageIndex = state.page,
                        pageCount = project.pages.size,
                        onAdjustImage = onAdjustImage,
                        onReplaceImage = onReplaceImage,
                        commands = commands,
                        showRulers = topHeight >= 120.dp + PdfRulerDefaults.Thickness,
                    )
                }
                if (hingeHeight > 0.dp) Spacer(Modifier.fillMaxWidth().height(hingeHeight))
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    PdfPageStrip(vm, state, project)
                    NavigationBar {
                        pdfToolBarTabs(hasMediaSource = mediaSource != null).forEachIndexed { n, tab ->
                            NavigationBarItem(
                                selected = panel == n,
                                onClick = { panel = n },
                                icon = { Icon(tab.first, contentDescription = null) },
                                label = {
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
                    Column(
                        Modifier.fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        when (panel) {
                            0 -> PdfPagesPanel(vm, state, onDeletePages, onExportSelectedPages)
                            1 -> InsertControls(vm, state, portable = onPortable, import = onLaunchImport)
                            2 -> PdfLayoutPanel(vm, state)
                            3 -> PdfAdjustPanel(vm, state)
                            4 -> PdfMediaPanel(vm, state, mediaSource)
                        }
                    }
                }
            }
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
}

private fun pdfToolBarTabs(hasMediaSource: Boolean = false) =
    buildList {
        add(GalleryIcons.Layers to R.string.pdf_pages)
        add(GalleryIcons.Plus to R.string.pdf_insert)
        add(GalleryIcons.Grid to R.string.pdf_design)
        add(GalleryIcons.Tune to R.string.pdf_adjust)
        // Phase F item 3: only shown in the compact bottom-navigation bar when a PdfMediaSource
        // was passed in, matching the expanded/hinge inspector's Media tab gating.
        if (hasMediaSource) add(GalleryIcons.PhotoLibrary to R.string.pdf_media)
    }

/**
 * Contextual toolbar shown above the page strip while an image is selected (Phase C item 4):
 * Replace, Crop/Fit, Rotate, Align, Layer and Delete. Reuses the existing "Adjust" custom action
 * (pdf_adjust) and the panel it opens, which stays the definitive place for numeric edits; this
 * row is the quick, mouse/touch-first path.
 */
@Composable
internal fun PdfImageContextualToolbar(
    vm: PdfStudioViewModel,
    onReplace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAlign by remember { mutableStateOf(false) }
    var showLayer by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val replaceLabel = stringResource(R.string.pdf_replace_image)
            IconButton(
                onClick = onReplace,
                modifier = Modifier.semantics { contentDescription = replaceLabel },
            ) {
                Icon(GalleryIcons.Image, contentDescription = null)
            }
            val cropFitLabel = stringResource(R.string.pdf_toolbar_cropfit)
            IconButton(
                onClick = vm::toggleSelectedImageFit,
                modifier = Modifier.semantics { contentDescription = cropFitLabel },
            ) {
                Icon(GalleryIcons.Crop, contentDescription = null)
            }
            val rotateLabel = stringResource(R.string.pdf_rotate)
            IconButton(
                onClick = vm::rotateSelectedImage,
                modifier = Modifier.semantics { contentDescription = rotateLabel },
            ) {
                Icon(GalleryIcons.RotateRight, contentDescription = null)
            }
            Box {
                val alignLabel = stringResource(R.string.pdf_toolbar_align)
                IconButton(
                    onClick = { showAlign = true },
                    modifier = Modifier.semantics { contentDescription = alignLabel },
                ) {
                    Icon(GalleryIcons.AlignHorizontalCenter, contentDescription = null)
                }
                DropdownMenu(expanded = showAlign, onDismissRequest = { showAlign = false }) {
                    val entries =
                        listOf(
                            PdfGeometry.Align.Left to R.string.pdf_align_left,
                            PdfGeometry.Align.Center to R.string.pdf_align_center,
                            PdfGeometry.Align.Right to R.string.pdf_align_right,
                            PdfGeometry.Align.Top to R.string.pdf_align_top,
                            PdfGeometry.Align.Middle to R.string.pdf_align_middle,
                            PdfGeometry.Align.Bottom to R.string.pdf_align_bottom,
                        )
                    entries.forEach { (align, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                showAlign = false
                                vm.alignSelectedImage(align)
                            },
                        )
                    }
                }
            }
            Box {
                val layerLabel = stringResource(R.string.pdf_toolbar_layer)
                IconButton(
                    onClick = { showLayer = true },
                    modifier = Modifier.semantics { contentDescription = layerLabel },
                ) {
                    Icon(GalleryIcons.Layers, contentDescription = null)
                }
                DropdownMenu(expanded = showLayer, onDismissRequest = { showLayer = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_layer_forward)) },
                        onClick = {
                            showLayer = false
                            vm.bringSelectedForward()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_layer_backward)) },
                        onClick = {
                            showLayer = false
                            vm.sendSelectedBackward()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_front)) },
                        onClick = {
                            showLayer = false
                            vm.bringSelectedToFront()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_backlayer)) },
                        onClick = {
                            showLayer = false
                            vm.sendSelectedToBack()
                        },
                    )
                }
            }
            val deleteLabel = stringResource(R.string.pdf_delete_image)
            // A validated Material role PAIR (errorContainer/onErrorContainer), not `error` tint
            // over the toolbar's own surfaceContainerHigh background — that combination isn't a
            // defined semantic pair and isn't contrast-checked anywhere.
            androidx.compose.material3.FilledTonalIconButton(
                onClick = vm::deleteSelectedImage,
                colors =
                    androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                modifier = Modifier.semantics { contentDescription = deleteLabel },
            ) {
                Icon(GalleryIcons.Trash, contentDescription = null)
            }
        }
    }
}

/** Fixed height of [PdfPageStrip], also used to keep the contextual toolbar floating above it. */
private val PAGE_STRIP_HEIGHT = 96.dp

/**
 * Contextual toolbar for a 2+ element multi-selection (Phase G2), replacing
 * [PdfImageContextualToolbar]/`PdfTextContextualToolbar` in the same reserved badge band: an
 * announced "N selected" count, group Align (relative to the selection's own bounding box —
 * [PdfStudioViewModel.alignGroupSelection]), Distribute horizontally/vertically (disabled with a
 * reason below 3 selected), Duplicate, Delete (error role pair) and a Done button that exits the
 * multi-select session ([PdfStudioViewModel.exitMultiSelect]).
 */
@Composable
internal fun PdfMultiSelectBar(vm: PdfStudioViewModel, count: Int, modifier: Modifier = Modifier) {
    var showAlign by remember { mutableStateOf(false) }
    var showDistribute by remember { mutableStateOf(false) }
    val barLabel = stringResource(R.string.pdf_group_selection_bar)
    Surface(
        modifier =
            modifier.semantics {
                contentDescription = barLabel
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.pdf_elements_selected, count, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box {
                val alignLabel = stringResource(R.string.pdf_toolbar_align)
                IconButton(
                    onClick = { showAlign = true },
                    modifier = Modifier.semantics { contentDescription = alignLabel },
                ) {
                    Icon(GalleryIcons.AlignHorizontalCenter, contentDescription = null)
                }
                DropdownMenu(expanded = showAlign, onDismissRequest = { showAlign = false }) {
                    alignEntriesForGroup().forEach { (align, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                showAlign = false
                                vm.alignGroupSelection(align)
                            },
                        )
                    }
                }
            }
            Box {
                val distributeLabel = stringResource(R.string.pdf_distribute)
                IconButton(
                    onClick = { showDistribute = true },
                    modifier = Modifier.semantics { contentDescription = distributeLabel },
                ) {
                    Icon(GalleryIcons.DistributeHorizontal, contentDescription = null)
                }
                DropdownMenu(expanded = showDistribute, onDismissRequest = { showDistribute = false }) {
                    val canDistribute = count >= 3
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_distribute_horizontal)) },
                        enabled = canDistribute,
                        onClick = {
                            showDistribute = false
                            vm.distributeGroupSelection(PdfSnapGuides.Orientation.Horizontal)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_distribute_vertical)) },
                        enabled = canDistribute,
                        onClick = {
                            showDistribute = false
                            vm.distributeGroupSelection(PdfSnapGuides.Orientation.Vertical)
                        },
                    )
                    if (!canDistribute)
                        Text(
                            stringResource(R.string.pdf_distribute_needs_three),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                }
            }
            val duplicateLabel = stringResource(R.string.pdf_shortcut_duplicate)
            IconButton(
                onClick = vm::duplicateGroupSelection,
                modifier = Modifier.semantics { contentDescription = duplicateLabel },
            ) {
                Icon(GalleryIcons.ContentCopy, contentDescription = null)
            }
            val deleteLabel = stringResource(R.string.pdf_delete)
            androidx.compose.material3.FilledTonalIconButton(
                onClick = vm::deleteGroupSelection,
                colors =
                    androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                modifier = Modifier.semantics { contentDescription = deleteLabel },
            ) {
                Icon(GalleryIcons.Trash, contentDescription = null)
            }
            val doneLabel = stringResource(R.string.pdf_multiselect_exit)
            IconButton(
                onClick = vm::exitMultiSelect,
                modifier = Modifier.semantics { contentDescription = doneLabel },
            ) {
                Icon(GalleryIcons.Close, contentDescription = null)
            }
        }
    }
}

private fun alignEntriesForGroup() =
    listOf(
        PdfGeometry.Align.Left to R.string.pdf_align_left,
        PdfGeometry.Align.Center to R.string.pdf_align_center,
        PdfGeometry.Align.Right to R.string.pdf_align_right,
        PdfGeometry.Align.Top to R.string.pdf_align_top,
        PdfGeometry.Align.Middle to R.string.pdf_align_middle,
        PdfGeometry.Align.Bottom to R.string.pdf_align_bottom,
    )

/**
 * Horizontal page strip under the canvas, above the bottom tool bar (Phase C item 3): numbered
 * thumbnails with the current page outlined, a trailing "Add page" tile, and long-press drag to
 * reorder. Complements (does not replace) the existing Pages panel/sheet, which stays the full
 * management surface (multi-select, duplicate, delete...).
 */
@Composable
internal fun PdfPageStrip(vm: PdfStudioViewModel, s: PdfStudioState, project: PdfProject) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(PAGE_STRIP_HEIGHT),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(project.pages, key = { it.id }) { page ->
                val index = project.pages.indexOf(page)
                val isCurrent = index == s.page
                val thumbLabel =
                    if (isCurrent)
                        stringResource(R.string.pdf_page_thumb_selected, index + 1, project.pages.size)
                    else stringResource(R.string.pdf_page_indicator, index + 1, project.pages.size)
                val moveLeftLabel = stringResource(R.string.pdf_move_left)
                val moveRightLabel = stringResource(R.string.pdf_move_right)
                Box(
                    Modifier.width(56.dp)
                        .fillMaxHeight()
                        // Neighbors sliding into the gap as a drag reorders the list is what
                        // makes the drop position visible; the dragged item itself is excluded
                        // (its own graphicsLayer translation below already tracks the finger).
                        .then(if (dragging == page.id) Modifier else Modifier.animateItem())
                        .graphicsLayer {
                            translationX = if (dragging == page.id) dragOffsetX else 0f
                            shadowElevation = if (dragging == page.id) 8f else 0f
                        }
                        .then(
                            if (isCurrent)
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                )
                            else Modifier
                        )
                        .clearAndSetSemantics {
                            contentDescription = thumbLabel
                            selected = isCurrent
                            role = Role.Button
                            onClick(label = null) {
                                vm.selectPage(index)
                                true
                            }
                            customActions =
                                buildList {
                                    if (index > 0)
                                        add(
                                            androidx.compose.ui.semantics.CustomAccessibilityAction(
                                                moveLeftLabel
                                            ) {
                                                vm.reorderPage(index, index - 1)
                                                true
                                            }
                                        )
                                    if (index < project.pages.lastIndex)
                                        add(
                                            androidx.compose.ui.semantics.CustomAccessibilityAction(
                                                moveRightLabel
                                            ) {
                                                vm.reorderPage(index, index + 1)
                                                true
                                            }
                                        )
                                }
                        }
                        .clickable(enabled = !s.editorLocked) { vm.selectPage(index) }
                        // Phase F item 3: dropping a Media panel item on a page thumbnail in the
                        // rail inserts it into THAT page, auto-placed (no coordinate math needed,
                        // unlike a canvas drop — see PdfCanvas's dragAndDropTarget).
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { !s.editorLocked },
                            target =
                                remember(page.id) {
                                    object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                                        override fun onDrop(
                                            event: androidx.compose.ui.draganddrop.DragAndDropEvent
                                        ): Boolean {
                                            val uri = pdfMediaDropUri(event) ?: return false
                                            vm.insertMediaIntoPage(uri, index)
                                            return true
                                        }
                                    }
                                },
                        )
                        .pointerInput(page.id, project.pages.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = page.id
                                    dragOffsetX = 0f
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                },
                                onDragEnd = {
                                    // Shared with the Pages grid (PdfDragReorder, R8): the target
                                    // is whichever visible item's center the drag now sits
                                    // nearest to, excluding the trailing "add page" tile so a
                                    // drop past the last page still resolves to the last page
                                    // (R3) instead of silently doing nothing.
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val self = visible.firstOrNull { it.key == page.id }
                                    val target =
                                        if (self != null) {
                                            val cx = (self.offset + dragOffsetX + self.size / 2).toDouble()
                                            val order = project.pages.map { it.id }
                                            val centers =
                                                visible.mapNotNull { item ->
                                                    (item.key as? String)?.let { key ->
                                                        key to ((item.offset + item.size / 2).toDouble() to 0.0)
                                                    }
                                                }
                                            PdfDragReorder.nearestIndex(
                                                order,
                                                centers,
                                                cx to 0.0,
                                                excludeKeys = setOf("add-page"),
                                                fallback = index,
                                            )
                                        } else index
                                    dragging = null
                                    dragOffsetX = 0f
                                    if (target != index) vm.reorderPage(index, target)
                                },
                                onDragCancel = {
                                    dragging = null
                                    dragOffsetX = 0f
                                },
                            ) { change, drag ->
                                change.consume()
                                dragOffsetX += drag.x
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PageThumbnail(page, vm, Modifier.size(40.dp, 52.dp))
                        Text(
                            "${index + 1}",
                            // Non-color cue besides the primary border: the current page's own
                            // number is also bold and a size step up, so it still reads as
                            // "selected" without relying on color alone (color-blind users, or a
                            // grayscale/high-contrast override).
                            style =
                                if (isCurrent)
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                else MaterialTheme.typography.labelSmall,
                            color =
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "add-page") {
                val atLimit = project.pages.size >= 100
                val addLabel =
                    if (atLimit) stringResource(R.string.pdf_addpage_limit)
                    else stringResource(R.string.pdf_addpage)
                Box(
                    Modifier.width(56.dp)
                        .fillMaxHeight()
                        .semantics { contentDescription = addLabel }
                        .clickable(enabled = !s.editorLocked && !atLimit, onClick = vm::addPage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GalleryIcons.Plus, contentDescription = null)
                }
            }
        }
    }
}

/**
 * Status bar under the canvas in expanded modes (Phase F2 item B): page indicator, the current
 * snap-grid state, the same zoom control as the compact badge band, and a Fit page action — all
 * reusing Phase C's own badge strings ([R.string.pdf_page_indicator], [R.string.pdf_zoom_percent],
 * [R.string.pdf_fit_view]) so the two surfaces never drift apart. Compact keeps the existing
 * floating badge band inside the canvas unchanged.
 */
@Composable
internal fun PdfStatusBar(
    state: PdfStudioState,
    project: PdfProject,
    commands: PdfEditorCommandDispatcher,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            // Phase F review fix (MAJOR): a fixed-width hinge pane (as narrow as ~280dp) could be
            // tighter than every status item's combined natural width, which previously wrapped
            // "Fit page" mid-word ("Fit pag/e") since nothing here could shrink or scroll.
            // horizontalScroll keeps every item whole and readable at any pane width/font scale
            // instead of wrapping or clipping; softWrap=false + maxLines=1 on each Text is the
            // belt-and-suspenders that guarantees no individual label ever breaks a word either.
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.pdf_page_indicator,
                    state.page + 1,
                    project.pages.size.coerceAtLeast(1),
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
            PdfStatusDot()
            Text(
                if (project.snap) stringResource(R.string.pdf_status_snap, "5 mm")
                else stringResource(R.string.pdf_status_snap_off),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
            PdfStatusDot()
            val zoomOutLabel = stringResource(R.string.pdf_shortcut_zoom_out)
            IconButton(
                onClick = { commands.dispatch(PdfEditorCommand.ZoomOut) },
                enabled = !state.editorLocked,
                modifier = Modifier.size(32.dp).semantics { contentDescription = zoomOutLabel },
            ) {
                Icon(GalleryIcons.Minus, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(R.string.pdf_zoom_percent, (state.zoom * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
            val zoomInLabel = stringResource(R.string.pdf_shortcut_zoom_in)
            IconButton(
                onClick = { commands.dispatch(PdfEditorCommand.ZoomIn) },
                enabled = !state.editorLocked,
                modifier = Modifier.size(32.dp).semantics { contentDescription = zoomInLabel },
            ) {
                Icon(GalleryIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            PdfStatusDot()
            val fitViewLabel = stringResource(R.string.pdf_fit_view)
            IconButton(
                onClick = { commands.dispatch(PdfEditorCommand.FitPage) },
                enabled = !state.editorLocked,
                modifier = Modifier.size(32.dp).semantics { contentDescription = fitViewLabel },
            ) {
                Icon(GalleryIcons.FitScreen, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PdfStatusDot() {
    Text(" · ", style = MaterialTheme.typography.labelMedium)
}

/**
 * Ctrl+/ opens this: every keyboard shortcut mapped to its action, plus the pointer-only hover
 * behavior (Phase F2 item C). A [ModalBottomSheet] (compact/expanded-without-side-panels) or the
 * same content in a plain [androidx.compose.material3.Dialog] on wide/expanded windows, where a
 * sheet sliding up from the bottom edge reads oddly next to a two/three-pane layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfShortcutsSheet(expanded: Boolean, onDismiss: () -> Unit) {
    val rows =
        listOf(
            "Ctrl+Z" to R.string.pdf_undo,
            "Ctrl+Shift+Z" to R.string.pdf_redo,
            "Ctrl+D" to R.string.pdf_shortcut_duplicate,
            "Delete" to R.string.pdf_delete_image,
            "Ctrl+E" to R.string.pdf_export,
            "Ctrl+0" to R.string.pdf_fit_view,
            "Ctrl+=" to R.string.pdf_shortcut_zoom_in,
            "Ctrl+-" to R.string.pdf_shortcut_zoom_out,
            "Ctrl+/" to R.string.pdf_shortcuts,
            "←/→/↑/↓" to R.string.pdf_shortcut_nudge,
            "Shift+←/→/↑/↓" to R.string.pdf_shortcut_nudge_large,
            "Ctrl+A" to R.string.pdf_shortcut_select_all,
            "Shift/Ctrl+Click" to R.string.pdf_shortcut_toggle_selection,
            "Escape" to R.string.pdf_shortcut_exit_selection,
        )
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.pdf_shortcuts), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            rows.forEach { (chord, labelRes) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(labelRes), Modifier.weight(1f))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            chord,
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
    if (expanded) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            ) {
                Box(Modifier.heightIn(max = 520.dp)) { content() }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            content()
        }
    }
}
