package com.ugallery.feature.pdfstudio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfStudioScreen(
    onExit: () -> Unit,
    vm: PdfStudioViewModel = viewModel(),
    initialUris: List<android.net.Uri> = emptyList(),
    initialRequestId: String? = null,
    onInitialUrisConsumed: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val exportJobs by vm.exportJobs.collectAsStateWithLifecycle()
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val pendingImport by vm.pendingImport.collectAsStateWithLifecycle()
    val initialName = stringResource(R.string.pdf_untitled)
    val fallbackRequestId = rememberSaveable(initialUris) { newId() }
    val galleryRows by vm.galleryDeliveries.collectAsStateWithLifecycle()
    var intakeAttempt by rememberSaveable(initialRequestId) { mutableIntStateOf(0) }
    var intakeFailed by rememberSaveable(initialRequestId) { mutableStateOf(false) }
    val consumeInitial by rememberUpdatedState(onInitialUrisConsumed)
    LaunchedEffect(initialUris, initialRequestId, intakeAttempt) {
        if (initialUris.isNotEmpty()) {
            val accepted =
                vm.receiveGallery(initialRequestId ?: fallbackRequestId, initialName, initialUris)
            intakeFailed = !accepted
            if (accepted) consumeInitial()
        }
    }
    val project = state.project
    var showActions by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableIntStateOf(-1) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var deletePages by remember { mutableStateOf(false) }
    var deleteProject by remember { mutableStateOf<String?>(null) }
    val imports =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
            { vm.importResult(it) },
        )
    val importProject =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            vm.importResult(listOfNotNull(it), portable = true)
        }
    // True only while a CreateDocument launcher started by THIS composition is outstanding, so a
    // stale saved request can never disable Save forever.
    var saveInFlight by remember { mutableStateOf(false) }
    val exportPdf =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) {
            saveInFlight = false
            vm.publicationResult(it)
        }
    val exportProject =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) {
            saveInFlight = false
            vm.publicationResult(it)
        }
    fun saveExport(job: PdfExportJob) {
        var start = vm.beginPublication(job.id)
        if (start is PublishStart.AlreadyPending) start = vm.restartPublication(job.id)
        when (start) {
            is PublishStart.Launch -> {
                saveInFlight = true
                try {
                    if (job.portable) exportProject.launch("${job.projectName}.ugpdfproject")
                    else exportPdf.launch("${job.projectName}.pdf")
                } catch (e: Exception) {
                    saveInFlight = false
                    vm.publicationLaunchFailed(e)
                }
            }
            is PublishStart.AlreadyPending -> vm.publicationBusy()
        }
    }
    fun launchImport(portable: Boolean) {
        if (!vm.beginImport(portable)) return
        try {
            if (portable)
                importProject.launch(
                    arrayOf("application/zip", "application/octet-stream", "application/json")
                )
            else imports.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf"))
        } catch (e: Exception) {
            vm.importLaunchFailed(e)
        }
    }
    BackHandler(project != null) { if (!state.busy) vm.library() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout =
            PdfStudioLayoutPolicy.forSize(
                maxWidth.value,
                maxHeight.value,
                androidx.compose.ui.platform.LocalDensity.current.fontScale,
            )
        val expanded = layout.expanded
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val backLabel =
                        stringResource(
                            if (project == null) R.string.pdf_close else R.string.pdf_projects
                        )
                    IconButton(
                        onClick = { if (project == null) onExit() else vm.library() },
                        enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Text("×", Modifier.clearAndSetSemantics {})
                    }
                    Text(
                        stringResource(R.string.pdf_studio),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        val actionsLabel = stringResource(R.string.pdf_project_actions)
                        IconButton(
                            onClick = { showActions = true },
                            modifier = Modifier.semantics { contentDescription = actionsLabel },
                        ) {
                            Text("⋮", Modifier.clearAndSetSemantics {})
                        }
                        DropdownMenu(
                            expanded = showActions,
                            onDismissRequest = { showActions = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_queue)) },
                                onClick = {
                                    showActions = false
                                    showQueue = true
                                },
                            )
                            if (project != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_pdfexport)) },
                                    enabled = !state.busy,
                                    onClick = {
                                        showActions = false
                                        exporting = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_project_details)) },
                                    onClick = {
                                        showActions = false
                                        showDetails = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_undo)) },
                                    enabled = state.canUndo && !state.busy,
                                    onClick = {
                                        showActions = false
                                        vm.undo()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_redo)) },
                                    enabled = state.canRedo && !state.busy,
                                    onClick = {
                                        showActions = false
                                        vm.redo()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_save)) },
                                    enabled = !state.busy,
                                    onClick = {
                                        showActions = false
                                        vm.save()
                                    },
                                )
                            }
                        }
                    }
                }
                if (intakeFailed && initialUris.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { intakeAttempt++ }) {
                            Text(stringResource(R.string.pdf_gallery_retry))
                        }
                        TextButton(
                            onClick = {
                                intakeFailed = false
                                consumeInitial()
                            }
                        ) {
                            Text(stringResource(R.string.pdf_gallery_discard))
                        }
                    }
                }
                galleryRows
                    .firstOrNull { it.error != null }
                    ?.let { delivery ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    stringResource(
                                        R.string.pdf_gallery_pending,
                                        delivery.sources().size,
                                    )
                                )
                                val reason =
                                    stringResource(
                                        if (delivery.failure() == "Cancelled")
                                            R.string.pdf_queue_cancelled
                                        else PdfFailure.persisted(delivery.failure()).message
                                    )
                                Text(
                                    delivery.failedSource()?.let { number ->
                                        stringResource(R.string.pdf_failure_source, number, reason)
                                    } ?: reason
                                )
                                GallerySourceStrip(delivery)
                                FlowRow {
                                    TextButton(
                                        onClick = { vm.retryGallery(delivery.id) },
                                        enabled = !state.busy,
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                contentColor = LocalContentColor.current
                                            ),
                                    ) {
                                        Text(stringResource(R.string.pdf_gallery_retry))
                                    }
                                    TextButton(
                                        onClick = { vm.discardGallery(delivery.id) },
                                        enabled = !state.busy,
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                contentColor = LocalContentColor.current
                                            ),
                                    ) {
                                        Text(stringResource(R.string.pdf_gallery_discard))
                                    }
                                }
                            }
                        }
                    }
                state.message?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        val message = stringResource(it)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.sourceError?.let { number ->
                                    stringResource(R.string.pdf_failure_source, number, message)
                                } ?: message,
                                Modifier.weight(1f).padding(12.dp),
                            )
                            exportJobs
                                .firstOrNull { job -> job.id == state.readyExport }
                                ?.takeIf { job -> job.phase == PdfExportPhase.Ready }
                                ?.let { job ->
                                    TextButton(
                                        onClick = { saveExport(job) },
                                        enabled = !state.busy && !saveInFlight,
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                contentColor = LocalContentColor.current
                                            ),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (job.portable) R.string.pdf_portable
                                                else R.string.pdf_save_pdf
                                            )
                                        )
                                    }
                                }
                            TextButton(
                                onClick = vm::dismissMessage,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.pdf_banner_dismiss))
                            }
                        }
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.progress?.let {
                                stringResource(R.string.pdf_progress, it.first, it.second)
                            } ?: stringResource(R.string.pdf_busy),
                            Modifier.weight(1f).padding(12.dp),
                        )
                        TextButton(onClick = vm::cancel) {
                            Text(stringResource(R.string.pdf_cancel))
                        }
                    }
                }
                if (project == null) {
                    val name = stringResource(R.string.pdf_untitled)
                    FlowRow(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { vm.newProject(name) }, enabled = !state.busy) {
                            Text(stringResource(R.string.pdf_newproject))
                        }
                        OutlinedButton(
                            onClick = { launchImport(true) },
                            enabled = !state.busy && pendingImport == null,
                        ) {
                            Text(stringResource(R.string.pdf_importproject))
                        }
                    }
                    Text(
                        stringResource(R.string.pdf_local),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(projects, key = { it.id }) { row ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                                    FlowRow {
                                        TextButton(
                                            onClick = { vm.open(row.id) },
                                            enabled = !state.busy,
                                        ) {
                                            Text(stringResource(R.string.pdf_open))
                                        }
                                        TextButton(
                                            onClick = { vm.duplicate(row.id) },
                                            enabled = !state.busy,
                                        ) {
                                            Text(stringResource(R.string.pdf_duplicatepage))
                                        }
                                        TextButton(
                                            onClick = { deleteProject = row.id },
                                            enabled = !state.busy,
                                        ) {
                                            Text(stringResource(R.string.pdf_remove))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (!layout.compactChrome) ProjectControls(vm, state)
                    BoxWithConstraints(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxSize()) {
                            if (expanded)
                                Column(
                                    Modifier.width(156.dp)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(8.dp)
                                ) {
                                    PageControls(vm, state) { deletePages = true }
                                }
                            PdfCanvas(
                                project.pages[state.page],
                                state.image,
                                vm,
                                Modifier.weight(1f).fillMaxHeight(),
                                state.busy,
                                onAdjustImage = { panel = 3 },
                            )
                            if (expanded)
                                Column(
                                    Modifier.width(280.dp)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp)
                                ) {
                                    InsertControls(
                                        state.copy(busy = state.busy || pendingImport != null),
                                        portable = {
                                            vm.portable()
                                            showQueue = true
                                        },
                                    ) {
                                        launchImport(false)
                                    }
                                    DesignControls(vm, state)
                                    ImageControls(vm, state)
                                }
                        }
                        if (!expanded || panel == 3) {
                            if (panel >= 0)
                                ModalBottomSheet(onDismissRequest = { panel = -1 }) {
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .heightIn(max = 520.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(16.dp)
                                    ) {
                                        when (panel) {
                                            0 -> PageControls(vm, state) { deletePages = true }
                                            1 ->
                                                InsertControls(
                                                    state.copy(
                                                        busy = state.busy || pendingImport != null
                                                    ),
                                                    portable = {
                                                        vm.portable()
                                                        showQueue = true
                                                    },
                                                ) {
                                                    panel = -1
                                                    launchImport(false)
                                                }
                                            2 -> DesignControls(vm, state)
                                            3 -> ImageControls(vm, state)
                                        }
                                    }
                                }
                        }
                    }
                    if (!expanded && layout.compactChrome) {
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
                                listOf(
                                        R.string.pdf_pages,
                                        R.string.pdf_insert,
                                        R.string.pdf_design,
                                        R.string.pdf_adjust,
                                    )
                                    .forEachIndexed { n, label ->
                                        FilterChip(
                                            selected = panel == n,
                                            onClick = { panel = n },
                                            enabled = !state.busy,
                                            label = { Text(stringResource(label), maxLines = 1) },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        )
                                    }
                            }
                        }
                    } else if (!expanded)
                        NavigationBar {
                            listOf(
                                    R.string.pdf_pages,
                                    R.string.pdf_insert,
                                    R.string.pdf_design,
                                    R.string.pdf_adjust,
                                )
                                .forEachIndexed { n, label ->
                                    NavigationBarItem(
                                        selected = panel == n,
                                        onClick = { panel = n },
                                        icon = {
                                            Text(
                                                listOf("▤", "+", "▦", "↔")[n],
                                                Modifier.clearAndSetSemantics {},
                                            )
                                        },
                                        label = { Text(stringResource(label)) },
                                        enabled = !state.busy,
                                    )
                                }
                        }
                }
            }
        }
    }
    if (showDetails && project != null)
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    stringResource(R.string.pdf_project_details),
                    style = MaterialTheme.typography.titleLarge,
                )
                ProjectControls(vm, state)
            }
        }
    if (showQueue)
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            PdfExportQueueContent(
                exportJobs,
                state.busy,
                vm,
                savePending = saveInFlight,
                onSave = ::saveExport,
            )
        }
    if (exporting) {
        var compact by remember { mutableStateOf(false) }
        var selectedOnly by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text(stringResource(R.string.pdf_pdfexport)) },
            text = {
                Column {
                    Toggle(stringResource(R.string.pdf_compactquality), compact) { compact = it }
                    if (state.selectedPages.isNotEmpty())
                        Toggle(stringResource(R.string.pdf_exportselected), selectedOnly) {
                            selectedOnly = it
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exporting = false
                        vm.prepareExport(compact, selectedOnly)
                        showQueue = true
                    }
                ) {
                    Text(stringResource(R.string.pdf_pdfexport))
                }
            },
            dismissButton = {
                TextButton(onClick = { exporting = false }) {
                    Text(stringResource(R.string.pdf_cancel))
                }
            },
        )
    }
    if (deletePages)
        Confirm(R.string.pdf_deletepages, { deletePages = false }) {
            vm.removePages()
            deletePages = false
        }
    deleteProject?.let { id ->
        Confirm(R.string.pdf_deleteproject, { deleteProject = null }) {
            vm.delete(id)
            deleteProject = null
        }
    }
}

@Composable
private fun Confirm(label: Int, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(label)) },
        confirmButton = {
            TextButton(onClick = confirm) { Text(stringResource(R.string.pdf_remove)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun NumberField(label: String, value: Double, onValue: (Double) -> Unit) {
    // A new model value invalidates an older edit, including while the field has focus.
    var draft by remember(value) { mutableStateOf(PdfNumberFieldDraft.fromValue(value)) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val committed = draft.commit()
        // Consume first: IME Done followed by focus loss must never emit twice.
        draft = committed.draft
        committed.value?.let(onValue)
    }
    OutlinedTextField(
        draft.text,
        { draft = draft.edit(it) },
        label = { Text(label) },
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth().padding(vertical = 4.dp).onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageControls(vm: PdfStudioViewModel, s: PdfStudioState, delete: () -> Unit) {
    val p = s.project ?: return
    Text(stringResource(R.string.pdf_pages), style = MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(p.pages, key = { it.id }) { page ->
            val n = p.pages.indexOf(page)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    page.id in s.selectedPages,
                    { vm.selectExportPage(page.id, it) },
                    enabled = !s.busy,
                )
                OutlinedButton(
                    onClick = { vm.selectPage(n) },
                    enabled = !s.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PageThumbnail(page, vm, Modifier.size(52.dp, 72.dp))
                        Text("${n+1}${if(n==s.page) " •" else ""}")
                    }
                }
            }
        }
    }
    FlowRow {
        TextButton(onClick = vm::addPage, enabled = !s.busy && p.pages.size < 100) {
            Text(stringResource(R.string.pdf_addpage))
        }
        TextButton(onClick = vm::duplicatePage, enabled = !s.busy && p.pages.size < 100) {
            Text(stringResource(R.string.pdf_duplicatepage))
        }
        TextButton(onClick = delete, enabled = !s.busy) {
            Text(stringResource(R.string.pdf_removepage))
        }
        TextButton(onClick = { vm.movePage(-1) }, enabled = !s.busy && s.page > 0) {
            Text(stringResource(R.string.pdf_pagebefore))
        }
        TextButton(onClick = { vm.movePage(1) }, enabled = !s.busy && s.page < p.pages.lastIndex) {
            Text(stringResource(R.string.pdf_pageafter))
        }
    }
}

@Composable
private fun InsertControls(s: PdfStudioState, portable: () -> Unit, import: () -> Unit) {
    Text(stringResource(R.string.pdf_insert), style = MaterialTheme.typography.titleMedium)
    Button(onClick = import, enabled = !s.busy) { Text(stringResource(R.string.pdf_importfiles)) }
    TextButton(onClick = portable, enabled = !s.busy) {
        Text(stringResource(R.string.pdf_portable))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignControls(vm: PdfStudioViewModel, s: PdfStudioState) {
    val p = s.project ?: return
    val page = p.pages[s.page]
    Text(stringResource(R.string.pdf_design), style = MaterialTheme.typography.titleMedium)
    if (page.source != null) {
        Text(stringResource(R.string.pdf_sourcepdf))
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(
                        width = it.height,
                        height = it.width,
                        rotation = (it.rotation + 90) % 360,
                    )
                }
            },
            enabled = !s.busy,
        ) {
            Text(stringResource(R.string.pdf_rotatepage))
        }
        return
    }
    FlowRow {
        listOf(
                "A4" to (210.0 to 297.0),
                "Letter" to (215.9 to 279.4),
                "10 × 15 cm" to (100.0 to 150.0),
            )
            .forEach { (label, size) ->
                TextButton(
                    onClick = {
                        vm.pageEdit {
                            val next =
                                it.copy(
                                    width = size.first,
                                    height = size.second,
                                    margin = min(it.margin, min(size.first, size.second) / 4),
                                )
                            next.copy(
                                images =
                                    it.images.map { image -> PdfGeometry.constrain(image, next) }
                            )
                        }
                    },
                    enabled = !s.busy,
                ) {
                    Text(label)
                }
            }
        TextButton(
            onClick = {
                vm.pageEdit {
                    val next = it.copy(width = it.height, height = it.width)
                    next.copy(
                        images = it.images.map { image -> PdfGeometry.constrain(image, next) }
                    )
                }
            },
            enabled = !s.busy,
        ) {
            Text(stringResource(R.string.pdf_rotatepage))
        }
    }
    FlowRow {
        PdfUnit.entries.forEach { u ->
            FilterChip(
                selected = u == p.unit,
                onClick = { vm.update { it.copy(unit = u) } },
                label = { Text(listOf("mm", "cm", "in", "px")[u.ordinal]) },
                enabled = !s.busy,
            )
        }
    }
    val factor = p.unit.factor(p.dpi)
    NumberField(stringResource(R.string.pdf_resolution), p.dpi.toDouble()) { n ->
        if (n in 72.0..600.0) vm.update { it.copy(dpi = n.toInt()) }
    }
    NumberField(stringResource(R.string.pdf_width), page.width / factor) { n ->
        if (n * factor >= 20)
            vm.pageEdit {
                val next = it.copy(width = n * factor)
                next.copy(images = it.images.map { i -> PdfGeometry.constrain(i, next) })
            }
    }
    NumberField(stringResource(R.string.pdf_height), page.height / factor) { n ->
        if (n * factor >= 20)
            vm.pageEdit {
                val next = it.copy(height = n * factor)
                next.copy(images = it.images.map { i -> PdfGeometry.constrain(i, next) })
            }
    }
    NumberField(stringResource(R.string.pdf_margin), page.margin / factor) { n ->
        if (n * factor < min(page.width, page.height) / 4)
            vm.pageEdit {
                val next = it.copy(margin = n * factor)
                next.copy(images = it.images.map { i -> PdfGeometry.constrain(i, next) })
            }
    }
    NumberField(stringResource(R.string.pdf_columns), p.columns.toDouble()) { n ->
        if (n in 1.0..6.0) vm.update { it.copy(columns = n.toInt()) }
    }
    NumberField(stringResource(R.string.pdf_gap), p.gap / factor) { n ->
        if (n * factor in 0.0..30.0) vm.update { it.copy(gap = n * factor) }
    }
    Toggle(stringResource(R.string.pdf_snap), p.snap) { enabled ->
        vm.update { it.copy(snap = enabled) }
    }
    TextButton(
        onClick = { vm.pageEdit { PdfGeometry.grid(it, p.columns, p.gap) } },
        enabled = !s.busy,
    ) {
        Text(stringResource(R.string.pdf_arrangegrid))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageControls(vm: PdfStudioViewModel, s: PdfStudioState) {
    val project = s.project ?: return
    val page = project.pages[s.page]
    Text(stringResource(R.string.pdf_adjust), style = MaterialTheme.typography.titleMedium)
    FlowRow {
        page.images.forEachIndexed { n, _ ->
            FilterChip(
                selected = n == s.image,
                onClick = { vm.selectImage(n) },
                label = { Text(stringResource(R.string.pdf_image_label, n + 1)) },
                enabled = !s.busy,
            )
        }
    }
    val image = page.images.getOrNull(s.image) ?: return
    val f = project.unit.factor(project.dpi)
    NumberField("X (${listOf("mm","cm","in","px")[project.unit.ordinal]})", image.x / f) { n ->
        vm.imageEdit { PdfGeometry.constrain(it.copy(x = n * f), page) }
    }
    NumberField("Y (${listOf("mm","cm","in","px")[project.unit.ordinal]})", image.y / f) { n ->
        vm.imageEdit { PdfGeometry.constrain(it.copy(y = n * f), page) }
    }
    NumberField(stringResource(R.string.pdf_width), image.width / f) { n ->
        if (n > 0) vm.imageEdit { PdfGeometry.resize(it, page, n * f, it.height, true) }
    }
    NumberField(stringResource(R.string.pdf_height), image.height / f) { n ->
        if (n > 0) vm.imageEdit { PdfGeometry.resize(it, page, it.width, n * f, false) }
    }
    Toggle(stringResource(R.string.pdf_lockratio), image.locked) { v ->
        vm.imageEdit { it.copy(locked = v) }
    }
    Toggle(stringResource(R.string.pdf_fillimage), image.fit == PdfFit.Cover) { v ->
        vm.imageEdit { it.copy(fit = if (v) PdfFit.Cover else PdfFit.Contain) }
    }
    if (image.fit == PdfFit.Cover) {
        Text(stringResource(R.string.pdf_cropx))
        Slider(image.focusX.toFloat(), { v -> vm.imageEdit { it.copy(focusX = v.toDouble()) } })
        Text(stringResource(R.string.pdf_cropy))
        Slider(image.focusY.toFloat(), { v -> vm.imageEdit { it.copy(focusY = v.toDouble()) } })
    }
    FlowRow {
        TextButton(
            onClick = {
                vm.imageEdit {
                    PdfGeometry.constrain(
                        it.copy(
                            width = it.height,
                            height = it.width,
                            rotation = (it.rotation + 90) % 360,
                        ),
                        page,
                    )
                }
            }
        ) {
            Text(stringResource(R.string.pdf_rotate))
        }
        TextButton(
            onClick = {
                vm.imageEdit {
                    it.copy(x = (page.width - it.width) / 2, y = (page.height - it.height) / 2)
                }
            }
        ) {
            Text(stringResource(R.string.pdf_center))
        }
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(images = it.images.toMutableList().apply { add(removeAt(s.image)) })
                }
                vm.selectImage(page.images.lastIndex)
            }
        ) {
            Text(stringResource(R.string.pdf_front))
        }
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(images = it.images.toMutableList().apply { add(0, removeAt(s.image)) })
                }
                vm.selectImage(0)
            }
        ) {
            Text(stringResource(R.string.pdf_backlayer))
        }
        TextButton(
            onClick = {
                vm.pageEdit { it.copy(images = it.images.filterIndexed { n, _ -> n != s.image }) }
                vm.selectImage(-1)
            }
        ) {
            Text(stringResource(R.string.pdf_remove))
        }
    }
}

@Composable
private fun PdfBitmap(
    file: File?,
    rotation: Int,
    fit: PdfFit,
    focusX: Double,
    focusY: Double,
    modifier: Modifier = Modifier,
    maxSide: Int = 1024,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retry by remember(file?.path, rotation, maxSide) { mutableIntStateOf(0) }
    var failure by remember(file?.path, rotation, maxSide) { mutableStateOf<Int?>(null) }
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, file?.path, rotation, maxSide, retry) {
            value = null
            failure = null
            try {
                value =
                    file?.let {
                        PdfBitmapStore.get(context).load(it, rotation, maxSide, immutable = true)
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = previewError(e)
            }
        }
    failure?.let { PdfPreviewProblem(it, maxSide > 192) { retry++ } }
    // Compose owns the displayed bitmap until this image leaves composition; GC releases its
    // allocation.
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = if (fit == PdfFit.Cover) ContentScale.Crop else ContentScale.Fit,
            alignment =
                androidx.compose.ui.BiasAlignment(
                    (focusX * 2 - 1).toFloat(),
                    (focusY * 2 - 1).toFloat(),
                ),
            modifier = modifier,
        )
    }
}

private fun previewError(error: Exception): Int =
    PdfFailure.from(error).let {
        if (it == PdfFailure.Unknown) R.string.pdf_preview_error else it.message
    }

@Composable
private fun PdfPreviewProblem(error: Int, retryable: Boolean, retry: () -> Unit) {
    Column {
        Text(stringResource(error), color = androidx.compose.ui.graphics.Color.Black)
        if (retryable)
            TextButton(
                onClick = retry,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = androidx.compose.ui.graphics.Color.Black
                    ),
            ) {
                Text(stringResource(R.string.pdf_retry))
            }
    }
}

@Composable
private fun PdfPageBitmap(page: PdfPage, vm: PdfStudioViewModel, side: Int, modifier: Modifier) {
    var retry by
        remember(page.source, page.sourcePage, page.rotation, side) { mutableIntStateOf(0) }
    var failure by
        remember(page.source, page.sourcePage, page.rotation, side) { mutableStateOf<Int?>(null) }
    val bitmap by
        produceState<android.graphics.Bitmap?>(
            null,
            page.source,
            page.sourcePage,
            page.rotation,
            side,
            retry,
        ) {
            value = null
            failure = null
            try {
                value = vm.repository.previewBitmap(page, side)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = previewError(e)
            }
        }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
    failure?.let { PdfPreviewProblem(it, side > 192) { retry++ } }
}

/** The delivery's photos, with the one that was rejected badged so the user can find it. */
@Composable
private fun GallerySourceStrip(delivery: PdfGalleryDelivery) {
    val failed = delivery.failedSource() ?: return
    val sources = remember(delivery.uris) { delivery.sources() }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(sources.size) { index ->
            val number = index + 1
            val rejected = number == failed
            val description =
                if (rejected) stringResource(R.string.pdf_gallery_source_rejected, number)
                else stringResource(R.string.pdf_gallery_source, number)
            val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
            val uri = sources[index]
            val bitmap by
                produceState<android.graphics.Bitmap?>(null, uri) {
                    value =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                    resolver.loadThumbnail(uri, android.util.Size(144, 144), null)
                                }
                                .getOrNull()
                        }
                }
            Box(
                Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics { contentDescription = description }
            ) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (rejected) Badge(Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
    }
}

@Composable
private fun PageThumbnail(page: PdfPage, vm: PdfStudioViewModel, modifier: Modifier) {
    BoxWithConstraints(
        modifier.background(androidx.compose.ui.graphics.Color.White).clipToBounds(),
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val width = maxWidth
        val height = maxHeight
        if (page.source != null) {
            PdfPageBitmap(page, vm, 192, Modifier.fillMaxSize())
        } else
            page.images.forEach { i ->
                PdfBitmap(
                    vm.repository.file(i.asset),
                    i.rotation,
                    i.fit,
                    i.focusX,
                    i.focusY,
                    Modifier.absoluteOffset(
                            width * (i.x / page.width).toFloat(),
                            height * (i.y / page.height).toFloat(),
                        )
                        .size(
                            width * (i.width / page.width).toFloat(),
                            height * (i.height / page.height).toFloat(),
                        ),
                    PdfPreviewPolicy.side(page.images.size, thumbnail = true),
                )
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectControls(vm: PdfStudioViewModel, state: PdfStudioState) {
    val project = state.project ?: return
    var title by remember(project.id) { mutableStateOf(project.name) }
    LaunchedEffect(project.id, project.name) { title = project.name }
    OutlinedTextField(
        title,
        {
            title = it.take(80)
            if (it.isNotBlank()) vm.update { p -> p.copy(name = it.take(80)) }
        },
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        label = { Text(stringResource(R.string.pdf_name)) },
        singleLine = true,
        enabled = !state.busy,
    )
    FlowRow(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = vm::undo, enabled = state.canUndo && !state.busy) {
            Text(stringResource(R.string.pdf_undo))
        }
        TextButton(onClick = vm::redo, enabled = state.canRedo && !state.busy) {
            Text(stringResource(R.string.pdf_redo))
        }
        TextButton(onClick = vm::save, enabled = !state.busy) {
            Text(stringResource(R.string.pdf_save))
        }
    }
}

@Composable
private fun PdfCanvas(
    page: PdfPage,
    selected: Int,
    vm: PdfStudioViewModel,
    modifier: Modifier,
    busy: Boolean,
    onAdjustImage: () -> Unit,
) {
    val current by vm.state.collectAsStateWithLifecycle()
    val viewport by rememberUpdatedState(current)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val canvasLabel = stringResource(R.string.pdf_canvas_label)
    Surface(
        modifier.semantics { contentDescription = canvasLabel },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .clipToBounds()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (busy || event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown)
                        false
                    else if (
                        event.isCtrlPressed && event.key == androidx.compose.ui.input.key.Key.Z
                    ) {
                        if (event.isShiftPressed) vm.redo() else vm.undo()
                        true
                    } else {
                        val step =
                            if (event.isShiftPressed) 10.0
                            else if (current.project?.snap == true) 5.0 else 1.0
                        when (event.key) {
                            androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                vm.moveImage(-step, 0.0)
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionRight -> {
                                vm.moveImage(step, 0.0)
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionUp -> {
                                vm.moveImage(0.0, -step)
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionDown -> {
                                vm.moveImage(0.0, step)
                                true
                            }
                            else -> false
                        }
                    }
                }
                .focusable()
                .pointerInput(page.id, busy, density) {
                    if (!busy)
                        detectTransformGestures { _, offset, scale, _ ->
                            vm.viewport(
                                viewport.zoom * scale,
                                viewport.panX + offset.x / density.density,
                                viewport.panY + offset.y / density.density,
                            )
                        }
                },
            contentAlignment = Alignment.Center,
        ) {
            val width =
                minOf(maxWidth - 24.dp, (maxHeight - 24.dp) * (page.width / page.height).toFloat())
                    .coerceAtLeast(80.dp)
            val height = width * (page.height / page.width).toFloat()
            // Physical print preview uses explicit white paper / black ink, a 21:1 contrast pair.
            Box(
                Modifier.size(width, height)
                    .graphicsLayer {
                        scaleX = current.zoom
                        scaleY = current.zoom
                        translationX = current.panX * density.density
                        translationY = current.panY * density.density
                    }
                    .background(androidx.compose.ui.graphics.Color.White)
                    .clipToBounds(),
                // PDF coordinates are physical, not reading-direction relative.
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                if (page.source != null) {
                    PdfPageBitmap(page, vm, 1024, Modifier.fillMaxSize())
                } else {
                    if (page.images.isEmpty())
                        Text(
                            stringResource(R.string.pdf_empty),
                            Modifier.align(Alignment.Center).padding(16.dp),
                            color = androidx.compose.ui.graphics.Color.Black,
                        )
                    page.images.forEachIndexed { n, i ->
                        val imageLabel = stringResource(R.string.pdf_image_label, n + 1)
                        val resizeLabel = stringResource(R.string.pdf_resize_label, n + 1)
                        val adjustLabel = stringResource(R.string.pdf_adjust)
                        val imageSelected = selected == n
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val pxPerMm = with(density) { width.toPx() } / page.width
                        var delta by
                            remember(i.id, i.x, i.y) {
                                mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
                            }
                        Box(
                            Modifier.absoluteOffset(
                                    x = width * (i.x / page.width).toFloat(),
                                    y = height * (i.y / page.height).toFloat(),
                                )
                                .size(
                                    width * (i.width / page.width).toFloat(),
                                    height * (i.height / page.height).toFloat(),
                                )
                                .graphicsLayer {
                                    translationX = delta.x
                                    translationY = delta.y
                                }
                                .then(
                                    if (selected == n)
                                        Modifier.drawWithContent {
                                            drawContent()
                                            // Physical print canvas overlay: white/black provide
                                            // 21:1 contrast.
                                            // The outline is editor-only and is never included in
                                            // exported pages.
                                            drawRect(Color.White, style = Stroke(6.dp.toPx()))
                                            drawRect(Color.Black, style = Stroke(2.dp.toPx()))
                                        }
                                    else Modifier
                                )
                                .semantics {
                                    contentDescription = imageLabel
                                    this.selected = imageSelected
                                    customActions =
                                        if (busy) emptyList()
                                        else
                                            listOf(
                                                CustomAccessibilityAction(adjustLabel) {
                                                    vm.selectImage(n)
                                                    onAdjustImage()
                                                    true
                                                }
                                            )
                                }
                                .clickable(enabled = !busy) {
                                    focus.requestFocus()
                                    vm.selectImage(n)
                                }
                                .pointerInput(i, busy) {
                                    if (!busy)
                                        detectDragGestures(
                                            onDragStart = {
                                                focus.requestFocus()
                                                vm.selectImage(n)
                                            },
                                            onDragEnd = {
                                                val move = delta
                                                delta = androidx.compose.ui.geometry.Offset.Zero
                                                vm.moveImage(move.x / pxPerMm, move.y / pxPerMm)
                                            },
                                            onDragCancel = {
                                                delta = androidx.compose.ui.geometry.Offset.Zero
                                            },
                                        ) { change, drag ->
                                            change.consume()
                                            delta += drag
                                        }
                                }
                        ) {
                            PdfBitmap(
                                vm.repository.file(i.asset),
                                i.rotation,
                                i.fit,
                                i.focusX,
                                i.focusY,
                                Modifier.fillMaxSize(),
                                PdfPreviewPolicy.side(page.images.size, thumbnail = false),
                            )
                            if (selected == n && !busy)
                                Box(
                                    Modifier.align(AbsoluteAlignment.BottomRight)
                                        .size(48.dp)
                                        .semantics { contentDescription = resizeLabel }
                                        .background(MaterialTheme.colorScheme.primary)
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = resizeLabel,
                                            onClick = onAdjustImage,
                                        )
                                        .pointerInput(i) {
                                            var resize = androidx.compose.ui.geometry.Offset.Zero
                                            detectDragGestures(
                                                onDragStart = {
                                                    resize =
                                                        androidx.compose.ui.geometry.Offset.Zero
                                                },
                                                onDragEnd = {
                                                    val w = max(.1, i.width + resize.x / pxPerMm)
                                                    val h = max(.1, i.height + resize.y / pxPerMm)
                                                    vm.imageEdit {
                                                        PdfGeometry.resize(it, page, w, h, true)
                                                    }
                                                },
                                            ) { change, drag ->
                                                change.consume()
                                                resize += drag
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "↘",
                                        Modifier.clearAndSetSemantics {},
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                        }
                    }
                }
            }
            val fitLabel = stringResource(R.string.pdf_fit_view)
            FilledTonalIconButton(
                onClick = { vm.viewport(1f, 0f, 0f) },
                enabled = !busy,
                modifier =
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp).semantics {
                        contentDescription = fitLabel
                    },
            ) {
                Text("⤢", Modifier.clearAndSetSemantics {})
            }
        }
    }
}
