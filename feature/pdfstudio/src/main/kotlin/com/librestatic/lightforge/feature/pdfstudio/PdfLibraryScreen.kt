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
import com.librestatic.lightforge.core.designsystem.GalleryIcons

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

/** The three quick-start templates offered from the empty state and prefilled into the New
 * project sheet (Phase E item 2/3): Photo grid (A4 portrait, 2 columns), Receipts (A4 portrait,
 * 1 column, narrow margins) and Prints 10x15 (10x15 cm, 1 photo per page). */
internal data class PdfNewProjectTemplate(
    val nameRes: Int,
    val paper: String,
    val landscape: Boolean = false,
    val columns: Int,
    val margin: Double = 10.0,
) {
    companion object {
        val PhotoGrid =
            PdfNewProjectTemplate(R.string.pdf_library_template_photo_grid, PdfPaperPresets.A4, columns = 2)
        val Receipts =
            PdfNewProjectTemplate(
                R.string.pdf_library_template_receipts,
                PdfPaperPresets.A4,
                columns = 1,
                margin = 5.0,
            )
        val Prints10x15 =
            PdfNewProjectTemplate(R.string.pdf_library_template_prints, PdfPaperPresets.PRINT_10X15, columns = 1)
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
    var prefillTemplate by remember { mutableStateOf<PdfNewProjectTemplate?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(PdfLibrarySort.Recent) }
    var renameTarget by remember { mutableStateOf<PdfProjectRow?>(null) }

    fun openNewProject(template: PdfNewProjectTemplate?) {
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
        FlowRowActions(busy, pendingImport, onNewProject = { openNewProject(null) }, onImportProject = onImportProject)
        PdfLibrarySearchAndSort(query, { query = it }, sort, { sort = it })
        val visible = remember(projects, query, sort) { filterAndSortProjects(projects, query, sort) }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(visible, key = { it.id }) { row ->
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
        }
    }

    if (showNewProject)
        PdfNewProjectSheet(
            defaultName = defaultName,
            template = prefillTemplate,
            onDismiss = { showNewProject = false },
            onCreate = { name, paper, landscape, columns, margin ->
                showNewProject = false
                val preset = PdfPaperPresets.presets.first { it.id == paper }
                vm.newProject(
                    name = name,
                    widthMm = preset.widthMm,
                    heightMm = preset.heightMm,
                    landscape = landscape,
                    columns = columns,
                    margin = margin,
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
    onTemplate: (PdfNewProjectTemplate) -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
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
            listOf(
                    PdfNewProjectTemplate.PhotoGrid to GalleryIcons.GridView,
                    PdfNewProjectTemplate.Receipts to GalleryIcons.Receipt,
                    PdfNewProjectTemplate.Prints10x15 to GalleryIcons.Photo,
                )
                .forEach { (template, icon) ->
                    val label = stringResource(template.nameRes)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier =
                            Modifier.clickable(enabled = !busy) { onTemplate(template) }
                                .semantics { contentDescription = label },
                    ) {
                        Column(
                            Modifier.padding(12.dp).widthIn(min = 88.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(icon, contentDescription = null)
                            Spacer(Modifier.height(4.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
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
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
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
 * "New project" sheet (Phase E item 3): optional name, visual paper cards and orientation (reused
 * from the Phase D Layout panel), a photos-per-page template, and a primary Create action. A
 * [template] pre-fills the sheet from an empty-state shortcut.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PdfNewProjectSheet(
    defaultName: String,
    template: PdfNewProjectTemplate?,
    onDismiss: () -> Unit,
    onCreate: (name: String, paper: String, landscape: Boolean, columns: Int, margin: Double) -> Unit,
) {
    var name by rememberSaveable(template) { mutableStateOf("") }
    var paper by rememberSaveable(template) { mutableStateOf(template?.paper ?: PdfPaperPresets.A4) }
    var landscape by rememberSaveable(template) { mutableStateOf(template?.landscape ?: false) }
    // Phase F item 0: track which grid-template tile is explicitly selected instead of deriving
    // it from a column count, since several tiles share a column count (4 and 6 both use 2
    // columns in portrait) and would otherwise both show as selected. Falls back to the
    // columns-derived match only when it is unambiguous.
    var selectedTemplate by rememberSaveable(template) {
        mutableStateOf(template?.let { PdfLayoutTemplates.unambiguousMatch(it.columns, it.landscape) })
    }
    val columns = selectedTemplate?.let { PdfLayoutTemplates.columnsFor(it, landscape) } ?: (template?.columns ?: 2)
    val margin = template?.margin ?: 10.0
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
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onCreate(name.ifBlank { defaultName }, paper, landscape, columns, margin)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pdf_new_project_create))
            }
        }
    }
}
