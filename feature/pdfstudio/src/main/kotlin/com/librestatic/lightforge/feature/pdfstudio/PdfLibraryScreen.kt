package com.librestatic.lightforge.feature.pdfstudio

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.librestatic.lightforge.core.designsystem.GalleryContentWidths
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.galleryWindowClass
import com.librestatic.lightforge.core.designsystem.GalleryShapeIllustration

internal enum class PdfLibrarySort {
    Recent,
    Name,
}

/** Pure filter/sort for the library list, split out from the composable so it has a plain JVM
 * unit test (see PdfLibraryScreenTest). Name search is case-insensitive substring; Name sort is
 * case-insensitive too, so "abc" and "ABC" interleave by their natural order, not ASCII order. */
internal fun filterAndSortProjects(
    projects: List<PdfProjectRow>,
    query: String,
    sort: PdfLibrarySort,
): List<PdfProjectRow> =
    projects.filter { it.name.contains(query, ignoreCase = true) }.let { list ->
        when (sort) {
            PdfLibrarySort.Recent -> list.sortedByDescending { it.updated }
            PdfLibrarySort.Name -> list.sortedBy { it.name.lowercase() }
        }
    }

/** The "no project open" list (Phase E redesign): first-run empty state with template shortcuts,
 * or a searchable/sortable list of rich project cards. */
@Composable
internal fun ColumnScope.PdfLibraryScreen(
    vm: PdfStudioViewModel,
    projects: List<PdfProjectRow>,
    busy: Boolean,
    pendingImport: Any?,
    defaultName: String,
    onImportProject: () -> Unit,
    onDeleteProject: (PdfProjectRow) -> Unit,
    onRenameProject: (PdfProjectRow, String) -> Unit,
    onExportProject: (PdfProjectRow) -> Unit,
) {
    var showNewProject by rememberSaveable { mutableStateOf(false) }
    var prefillTemplate by remember { mutableStateOf<PdfTemplate?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(PdfLibrarySort.Recent) }
    var renameTarget by remember { mutableStateOf<PdfProjectRow?>(null) }

    fun openNewProject(template: PdfTemplate?) {
        prefillTemplate = template
        showNewProject = true
    }

    if (projects.isEmpty()) {
        PdfLibraryEmptyState(
            busy = busy,
            onNewProject = { openNewProject(null) },
            onImportProject = onImportProject,
            pendingImport = pendingImport,
            onTemplate = { openNewProject(it) },
        )
    } else {
        val visible = remember(projects, query, sort) { filterAndSortProjects(projects, query, sort) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val wide = galleryWindowClass(maxWidth) != GalleryWindowClass.Compact
            Column(
                if (wide) Modifier.fillMaxHeight().widthIn(max = GalleryContentWidths.Browsing) else Modifier.fillMaxSize()
            ) {
                if (wide) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.pdf_projects),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        FilledTonalButton(
                            onClick = onImportProject,
                            enabled = !busy && pendingImport == null,
                        ) {
                            Text(stringResource(R.string.pdf_importproject))
                        }
                        Button(onClick = { openNewProject(null) }, enabled = !busy) {
                            Text(stringResource(R.string.pdf_newproject))
                        }
                    }
                } else {
                    FlowRowActions(busy, pendingImport, onNewProject = { openNewProject(null) }, onImportProject = onImportProject)
                }
                PdfLibrarySearchAndSort(query, { query = it }, sort, { sort = it })
                val cardContent: @Composable (PdfProjectRow) -> Unit = { row ->
                    PdfLibraryCard(
                        row = row,
                        vm = vm,
                        busy = busy,
                        onOpen = { vm.open(row.id) },
                        onRename = { renameTarget = row },
                        onDuplicate = { vm.duplicate(row.id) },
                        onExport = { onExportProject(row) },
                        onDelete = { onDeleteProject(row) },
                    )
                }
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.pdf_library_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else if (wide) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(280.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.id }) { row -> cardContent(row) }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.id }) { row -> cardContent(row) }
                    }
                }
            }
        }
    }

    if (showNewProject)
        PdfNewProjectSheet(
            defaultName = defaultName,
            template = prefillTemplate,
            onDismiss = { showNewProject = false },
            onCreate = { name, paper, landscape, columns, margin, gap, printSize, placementMode ->
                showNewProject = false
                val preset = PdfPaperPresets.presets.first { it.id == paper }
                vm.newProject(
                    name = name,
                    widthMm = preset.widthMm,
                    heightMm = preset.heightMm,
                    landscape = landscape,
                    columns = columns,
                    gap = gap,
                    margin = margin,
                    printSize = printSize,
                    placementMode = placementMode,
                )
            },
        )

    renameTarget?.let { row ->
        PdfRenameProjectDialog(
            initial = row.name,
            onDismiss = { renameTarget = null },
            onConfirm = {
                onRenameProject(row, it)
                renameTarget = null
            },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(
    busy: Boolean,
    pendingImport: Any?,
    onNewProject: () -> Unit,
    onImportProject: () -> Unit,
) {
    FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNewProject, enabled = !busy) {
            Text(stringResource(R.string.pdf_newproject))
        }
        OutlinedButton(onClick = onImportProject, enabled = !busy && pendingImport == null) {
            Text(stringResource(R.string.pdf_importproject))
        }
    }
}

@Composable
private fun PdfLibrarySearchAndSort(
    query: String,
    onQuery: (String) -> Unit,
    sort: PdfLibrarySort,
    onSort: (PdfLibrarySort) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.pdf_library_search_hint)) },
            leadingIcon = { Icon(GalleryIcons.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        var menu by remember { mutableStateOf(false) }
        val sortLabel = stringResource(R.string.pdf_library_sort)
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = sortLabel }) {
                Icon(GalleryIcons.Sort, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_library_sort_recent)) },
                    onClick = { onSort(PdfLibrarySort.Recent); menu = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_library_sort_name)) },
                    onClick = { onSort(PdfLibrarySort.Name); menu = false },
                )
            }
        }
    }
}

/** First-run empty state: an icon-only illustration (no bitmap assets), the value prop, primary
 * New project / secondary Import project, and the three template shortcuts. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PdfLibraryEmptyState(
    busy: Boolean,
    pendingImport: Any?,
    onNewProject: () -> Unit,
    onImportProject: () -> Unit,
    onTemplate: (PdfTemplate) -> Unit,
) {
    // Window width, so multi-window and foldable postures switch layouts like the rest of the app.
    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600) {
        PdfLibraryWideEmptyState(busy, pendingImport, onNewProject, onImportProject, onTemplate)
        return
    }
    Column(
        // G4 review fix: PdfTemplateCard's two-line name+description made the template row much
        // taller than the old icon-only tiles it replaced; at 200% font, "Prints 10 x 15" fell
        // entirely off this non-scrolling, centered Column with no way to reach it (confirmed via
        // uiautomator: the node simply isn't in the tree at font_scale=2.0 on a 1080x2340 phone).
        // A scrollable Column keeps every shortcut reachable regardless of how tall the content
        // grows, the same fix already applied to PdfNewProjectSheet for the same reason.
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.size(96.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(GalleryIcons.PictureAsPdf, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.pdf_library_empty_headline),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.pdf_library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewProject, enabled = !busy) { Text(stringResource(R.string.pdf_newproject)) }
            OutlinedButton(onClick = onImportProject, enabled = !busy && pendingImport == null) {
                Text(stringResource(R.string.pdf_importproject))
            }
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PdfTemplate.LIBRARY_SHORTCUTS.forEach { template ->
                PdfTemplateCard(template = template, selected = false, enabled = !busy) { onTemplate(template) }
            }
        }
    }
}

/** Wide first-run layout: value prop and actions beside a large shape, templates as a full row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PdfLibraryWideEmptyState(
    busy: Boolean,
    pendingImport: Any?,
    onNewProject: () -> Unit,
    onImportProject: () -> Unit,
    onTemplate: (PdfTemplate) -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.widthIn(max = GalleryContentWidths.Browsing).fillMaxWidth(),
        ) {
        Row(
            Modifier.padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pdf_library_empty_headline), style = MaterialTheme.typography.displaySmall)
                Text(
                    stringResource(R.string.pdf_library_empty_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNewProject, enabled = !busy) { Text(stringResource(R.string.pdf_newproject)) }
                    OutlinedButton(onClick = onImportProject, enabled = !busy && pendingImport == null) {
                        Text(stringResource(R.string.pdf_importproject))
                    }
                }
            }
            GalleryShapeIllustration(
                GalleryIcons.PictureAsPdf,
                size = 220.dp,
                backdrop = MaterialTheme.colorScheme.surfaceContainerLow,
                animateEntrance = true,
            )
        }
        }
        FlowRow(
            Modifier.widthIn(max = GalleryContentWidths.Browsing).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PdfTemplate.LIBRARY_SHORTCUTS.forEach { template ->
                PdfTemplateCard(template = template, selected = false, enabled = !busy) { onTemplate(template) }
            }
        }
    }
}

/** A rich library card: cached first-page thumbnail, name, "N pages · size · relative time", the
 * whole card tappable, plus a visible "Open" text action (kept for PdfGallerySelectionUiDeviceTest
 * and verify_pdf_screen_flow.py, which locate it by the pdf_open string) and an overflow menu. */
@Composable
private fun PdfLibraryCard(
    row: PdfProjectRow,
    vm: PdfStudioViewModel,
    busy: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val summary =
        pluralStringResource(
            R.plurals.pdf_library_card_summary,
            row.pageCount.coerceAtLeast(1),
            row.pageCount.coerceAtLeast(1),
            remember(row.sourceBytes) { Formatter.formatShortFileSize(context, row.sourceBytes) },
            remember(row.updated) {
                DateUtils.getRelativeTimeSpanString(
                        row.updated,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    )
                    .toString()
            },
        )
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(56.dp),
            ) {
                PdfLibraryCardThumbnail(row.id, vm)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Two lines + ellipsis: a single clipped line hid word-wrapped suffixes such as
                // "(copy)", leaving a project and its duplicate visually identical.
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onOpen, enabled = !busy) { Text(stringResource(R.string.pdf_open)) }
            Box {
                val moreLabel = stringResource(R.string.pdf_library_more_actions, row.name)
                IconButton(
                    onClick = { menu = true },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = moreLabel },
                ) {
                    Icon(GalleryIcons.More, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_library_rename)) },
                        leadingIcon = { Icon(GalleryIcons.Rename, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_duplicateproject)) },
                        leadingIcon = { Icon(GalleryIcons.ContentCopy, contentDescription = null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_library_export_project)) },
                        leadingIcon = { Icon(GalleryIcons.OpenInNew, contentDescription = null) },
                        onClick = { menu = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.pdf_deleteproject_action),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                GalleryIcons.Trash,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Renders the project's cover page: the first source page's rendered preview when it has one
 * (an imported PDF/image page), otherwise its first placed image, both through the same disk
 * preview cache the canvas uses. A blank/text-only first page falls back to a generic icon. */
@Composable
private fun PdfLibraryCardThumbnail(projectId: String, vm: PdfStudioViewModel) {
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, projectId) {
            value =
                runCatching {
                        val project = vm.repository.load(projectId) ?: return@runCatching null
                        val page = project.pages.firstOrNull() ?: return@runCatching null
                        if (page.source != null) vm.repository.previewBitmap(page, 224)
                        else
                            page.images.firstOrNull()?.let { image ->
                                vm.repository.imageBitmap(vm.repository.file(image.asset), image.rotation, 224)
                            }
                    }
                    .getOrNull()
        }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(GalleryIcons.PictureAsPdf, contentDescription = null)
        }
}

@Composable
private fun PdfRenameProjectDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    // Surfaced as validation, not silently enforced: a blank name or one over 80 characters shows
    // why Done is disabled instead of truncating/no-oping without telling the user (Phase E review
    // finding).
    val blank = text.isBlank()
    val tooLong = text.length > 80
    val errorMessage =
        when {
            blank -> stringResource(R.string.pdf_library_rename_blank)
            tooLong -> stringResource(R.string.pdf_library_rename_too_long)
            else -> null
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_library_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.pdf_name)) },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = errorMessage == null) {
                Text(stringResource(R.string.pdf_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) } },
    )
}

/**
 * "New project" sheet (Phase E item 3, single-source-of-truth templates in Phase G4): a row of
 * [PdfTemplate] visual cards ("Blank" plus the library shortcuts) that prefills paper/orientation/
 * photos-per-page/margin/gap when tapped, an optional name, visual paper cards and orientation
 * (reused from the Phase D Layout panel), a photos-per-page template, and a primary Create action.
 * A [template] pre-fills the sheet's selected card from an empty-state shortcut; `null` starts on
 * [PdfTemplate.Blank].
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PdfNewProjectSheet(
    defaultName: String,
    template: PdfTemplate?,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        paper: String,
        landscape: Boolean,
        columns: Int,
        margin: Double,
        gap: Double,
        printSize: String?,
        placementMode: PdfFit,
    ) -> Unit,
) {
    var selectedProjectTemplate by rememberSaveable(template) { mutableStateOf(template ?: PdfTemplate.Blank) }
    var name by rememberSaveable(template) { mutableStateOf("") }
    var paper by rememberSaveable(template) { mutableStateOf(selectedProjectTemplate.paper) }
    var landscape by rememberSaveable(template) { mutableStateOf(selectedProjectTemplate.landscape) }
    // Phase F item 0: track which grid-template tile is explicitly selected instead of deriving
    // it from a column count, since several tiles share a column count (4 and 6 both use 2
    // columns in portrait) and would otherwise both show as selected. Falls back to the
    // columns-derived match only when it is unambiguous.
    var selectedTemplate by rememberSaveable(template) {
        mutableStateOf(PdfLayoutTemplates.unambiguousMatch(selectedProjectTemplate.columns, selectedProjectTemplate.landscape))
    }
    var margin by rememberSaveable(template) { mutableDoubleStateOf(selectedProjectTemplate.margin) }
    var gap by rememberSaveable(template) { mutableDoubleStateOf(selectedProjectTemplate.gap) }
    // Feedback item B: "Print size" (null = Free grid). A non-null value routes creation through
    // PdfPrintLayout instead of the free-grid columns/photosPerPage tiles below.
    var printSize by rememberSaveable(template) { mutableStateOf(selectedProjectTemplate.printSize) }
    // Feedback item A: the default placement mode for every auto-placed photo.
    var placementMode by rememberSaveable(template) { mutableStateOf(selectedProjectTemplate.fit) }
    val columns = selectedTemplate?.let { PdfLayoutTemplates.columnsFor(it, landscape) } ?: selectedProjectTemplate.columns

    // Applies every field a project/page template (Phase G4) prefills; the user can still tweak
    // paper/orientation/photos-per-page individually afterward via the sections below.
    fun applyProjectTemplate(t: PdfTemplate) {
        selectedProjectTemplate = t
        paper = t.paper
        landscape = t.landscape
        selectedTemplate = PdfLayoutTemplates.unambiguousMatch(t.columns, t.landscape)
        margin = t.margin
        gap = t.gap
        printSize = t.printSize
        placementMode = t.fit
    }

    // Feedback item B: the computed slot grid for the current paper/orientation/margin/gap/print
    // size — null when "Free grid" is selected. Recomputed live, never hardcoded.
    val currentPrintSize = printSize
    val slotFit =
        remember(paper, landscape, margin, gap, currentPrintSize) {
            if (currentPrintSize == null) null
            else {
                val preset = PdfPaperPresets.presets.first { it.id == paper }
                val w = if (landscape) preset.heightMm else preset.widthMm
                val h = if (landscape) preset.widthMm else preset.heightMm
                PdfPrintLayout.fit(w, h, margin, gap, currentPrintSize)
            }
        }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Narrow widths (320-360dp) wrap the paper-card/template FlowRows onto extra lines, and
        // 200% font grows every label; either can push Create's height requirement past the
        // sheet's available space. Unlike PdfExportSheet, Create isn't a separate sticky row here
        // (just the Column's last item), so a plain verticalScroll — reachable by scrolling
        // rather than a sticky footer — is the minimal fix: Create was previously unreachable
        // whenever the sheet's content grew taller than the screen, with no way to scroll to it.
        Column(
            Modifier.padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.pdf_new_project_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.pdf_new_project_template_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                PdfTemplate.NEW_PROJECT_TILES.forEach { t ->
                    PdfTemplateCard(
                        template = t,
                        selected = selectedProjectTemplate == t,
                        enabled = true,
                    ) { applyProjectTemplate(t) }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.pdf_name)) },
                placeholder = { Text(defaultName) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.pdf_paper), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                PdfPaperPresets.presets.forEach { preset ->
                    PdfPaperCard(
                        label = preset.label,
                        widthMm = if (landscape) preset.heightMm else preset.widthMm,
                        heightMm = if (landscape) preset.widthMm else preset.heightMm,
                        selected = preset.id == paper,
                        enabled = true,
                    ) { paper = preset.id }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.pdf_orientation), style = MaterialTheme.typography.labelLarge)
            GalleryExpressiveChoiceGroupCompat(
                labels = listOf(stringResource(R.string.pdf_export_portrait), stringResource(R.string.pdf_export_landscape)),
                selectedIndex = if (landscape) 1 else 0,
                onSelect = { landscape = it == 1 },
                enabled = true,
            )
            Spacer(Modifier.height(12.dp))
            // Feedback item B: "Print size" — a photo print size laid out at exact physical
            // dimensions on the paper above, independent of the paper choice, with the per-page
            // count COMPUTED (never hardcoded). "Free grid" (null) keeps the pre-existing
            // columns/photos-per-page tiles.
            Text(stringResource(R.string.pdf_print_size_label), style = MaterialTheme.typography.labelLarge)
            PdfPrintSizeSelector(selected = printSize, enabled = true) { printSize = it }
            val fit = slotFit
            val slotFitValid = if (fit != null) PdfPrintSizeCountLine(fit) else true
            if (printSize == null) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pdf_grid_template), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    PdfLayoutTemplates.TEMPLATES.forEach { count ->
                        val templateColumns = PdfLayoutTemplates.columnsFor(count, landscape)
                        PdfTemplateTile(count, templateColumns, landscape, selectedTemplate == count, true) {
                            selectedTemplate = count
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.pdf_placement_mode_label), style = MaterialTheme.typography.labelLarge)
            PdfPlacementModeSelector(mode = placementMode, enabled = true) { placementMode = it }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onCreate(
                        name.ifBlank { defaultName },
                        paper,
                        landscape,
                        columns,
                        margin,
                        gap,
                        printSize?.id,
                        placementMode,
                    )
                },
                enabled = slotFitValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pdf_new_project_create))
            }
        }
    }
}
