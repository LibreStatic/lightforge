package com.ugallery.feature.pdfstudio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * `CreateDocument` with an optional `EXTRA_INITIAL_URI` hint, so the destination-first export
 * picker (Phase B) opens near the last place the user saved a PDF instead of always starting at
 * the provider's default root.
 */
private class PdfCreateDocumentWithHint(private val hint: () -> android.net.Uri?) :
    ActivityResultContracts.CreateDocument("application/pdf") {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent {
        val intent = super.createIntent(context, input)
        hint()?.let { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
        return intent
    }
}

private fun ensurePdfSuffix(name: String): String =
    if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfStudioScreen(
    onExit: () -> Unit,
    vm: PdfStudioViewModel = viewModel(),
    initialUris: List<android.net.Uri> = emptyList(),
    initialRequestId: String? = null,
    onInitialUrisConsumed: () -> Unit = {},
    // Phase F item 1: the app passes its GalleryFoldInfo the same way it does for the video
    // editor (ProductionGalleryApp.kt's VideoEditorContent call), so foldable/tabletop layouts
    // work without pdfstudio depending on androidx.window. The default keeps every existing
    // caller (tests, PdfUiProbeActivity) on the hinge-less path.
    foldInfo: com.ugallery.core.designsystem.GalleryFoldInfo? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val exportJobs by vm.exportJobs.collectAsStateWithLifecycle()
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val pendingImport by vm.pendingImport.collectAsStateWithLifecycle()
    val initialName = stringResource(R.string.pdf_untitled)
    val fallbackRequestId = rememberSaveable(initialUris) { newId() }
    val galleryRows by vm.galleryDeliveries.collectAsStateWithLifecycle()
    val lastDestinationLabel by vm.lastDestinationLabel.collectAsStateWithLifecycle()
    val watchedExportId by vm.watchedExportId.collectAsStateWithLifecycle()
    val watchedJob = exportJobs.firstOrNull { it.id == watchedExportId }
    // "Keep editing" hides the progress card; the top-bar chip (PdfEditorTopBar) reopens it. A new
    // watched job (a fresh export, or a retry) always starts with the card visible again.
    var progressHidden by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(watchedExportId) { progressHidden = false }
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableIntStateOf(-1) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var deletePages by remember { mutableStateOf(false) }
    var deleteProject by remember { mutableStateOf<PdfProjectRow?>(null) }
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
    val newExportPdf =
        rememberLauncherForActivityResult(
            remember { PdfCreateDocumentWithHint { vm.lastDestinationUriOrNull() } }
        ) {
            saveInFlight = false
            vm.publicationResult(it)
        }
    fun startNewExport(filename: String, pagesChoice: PdfExportPagesChoice, compact: Boolean) {
        var start = vm.beginNewExport(pagesChoice, compact)
        if (start is PublishStart.AlreadyPending) start = vm.restartNewExport(pagesChoice, compact)
        when (start) {
            is PublishStart.Launch -> {
                saveInFlight = true
                try {
                    newExportPdf.launch(ensurePdfSuffix(filename))
                } catch (e: Exception) {
                    saveInFlight = false
                    vm.publicationLaunchFailed(e)
                }
            }
            is PublishStart.AlreadyPending -> vm.publicationBusy()
        }
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
    val replaceImage =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::replaceSelectedImageAsset)
        }
    // "Replace photo" on a failed gallery intake (Phase E item 5): remembers which delivery/source
    // to swap, then lets the batch retry once a replacement is picked.
    var replaceGalleryTarget by rememberSaveable { mutableStateOf<Pair<String, Int>?>(null) }
    val replaceGallerySource =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = replaceGalleryTarget
            replaceGalleryTarget = null
            if (uri != null && target != null) vm.replaceGallerySource(target.first, target.second, uri)
        }
    // library() always works now (it joins whatever operation is in flight itself), so Back must
    // never be conditionally gated here or it can look tappable while doing nothing.
    BackHandler(project != null) { vm.library() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout =
            PdfStudioLayoutPolicy.forSize(
                maxWidth.value,
                maxHeight.value,
                androidx.compose.ui.platform.LocalDensity.current.fontScale,
                foldInfo,
            )
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (project == null) {
                    PdfLibraryTopBar(onBack = onExit, onQueue = { showQueue = true }, exportJobs = exportJobs)
                    PdfLibraryScreen(
                        vm = vm,
                        projects = projects,
                        busy = state.editorLocked,
                        pendingImport = pendingImport,
                        defaultName = initialName,
                        onImportProject = { launchImport(true) },
                        onDeleteProject = { deleteProject = it },
                        onRenameProject = { row, name -> vm.renameProject(row.id, name) },
                        onExportProject = { vm.portableFor(it.id); showQueue = true },
                    )
                } else {
                    PdfEditorTopBar(
                        project = project,
                        state = state,
                        onBack = vm::library,
                        onRename = { showDetails = true },
                        onUndo = vm::undo,
                        onRedo = vm::redo,
                        onExport = { exporting = true },
                        onQueue = { showQueue = true },
                        onDetails = { showDetails = true },
                        onPortable = {
                            vm.portable()
                            showQueue = true
                        },
                        watchedJob = watchedJob,
                        onReopenProgress = { progressHidden = false },
                    )
                    PdfEditorBody(
                        vm = vm,
                        state = state,
                        project = project,
                        layout = layout,
                        panel = panel,
                        onPanelChange = { panel = it },
                        onDeletePages = { deletePages = true },
                        onExportSelectedPages = {
                            // The export sheet already defaults its page choice to Selected
                            // whenever state.selectedPages is non-empty (R10 review fix).
                            exporting = true
                        },
                        onPortable = {
                            vm.portable()
                            showQueue = true
                        },
                        onShowQueue = { showQueue = true },
                        onLaunchImport = { launchImport(false) },
                        onAdjustImage = { panel = 3 },
                        onReplaceImage = {
                            try {
                                replaceImage.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                            } catch (e: Exception) {
                                vm.importLaunchFailed(e)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        PdfFeedbackOverlay(
                            state = state,
                            galleryRows = galleryRows,
                            exportJobs = exportJobs,
                            intakeFailed = intakeFailed,
                            hasInitialUris = initialUris.isNotEmpty(),
                            saveInFlight = saveInFlight,
                            onRetryIntake = { intakeAttempt++ },
                            onDiscardIntake = {
                                intakeFailed = false
                                consumeInitial()
                            },
                            onRetryGallery = vm::retryGallery,
                            onDiscardGallery = vm::discardGallery,
                            onReplaceGallerySource = { delivery ->
                                delivery.failedSource()?.let { number ->
                                    replaceGalleryTarget = delivery.id to number
                                    try {
                                        replaceGallerySource.launch(arrayOf("image/*"))
                                    } catch (e: Exception) {
                                        replaceGalleryTarget = null
                                    }
                                }
                            },
                            onRemoveGallerySource = { delivery ->
                                delivery.failedSource()?.let { number ->
                                    vm.removeGallerySource(delivery.id, number)
                                }
                            },
                            onSaveExport = ::saveExport,
                            onDismissMessage = vm::dismissMessage,
                            onCancelBusy = vm::cancel,
                            watchedExportId = watchedExportId,
                            progressHidden = progressHidden,
                            onCancelExport = vm::cancelExport,
                            onHideProgress = { progressHidden = true },
                            onRetryExport = vm::retryExport,
                            onDismissResult = vm::dismissResult,
                            onOpenRecovery = { job ->
                                job.destination?.let { openPdf(context, android.net.Uri.parse(it), vm::reportOpenFailed) }
                                vm.dismissRecovery()
                            },
                            onDismissRecovery = vm::dismissRecovery,
                        )
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
                ProjectDetails(vm, state)
            }
        }
    if (showQueue)
        PdfExportHistoryDialog(
            jobs = exportJobs,
            busy = state.editorLocked,
            vm = vm,
            savePending = saveInFlight,
            onSave = ::saveExport,
            onDismiss = { showQueue = false },
        )
    state.resultJobId
        ?.let { id -> exportJobs.firstOrNull { it.id == id && it.phase == PdfExportPhase.Published } }
        ?.let { job ->
            val uri = job.destination?.let(android.net.Uri::parse)
            PdfExportResultSheet(
                job = job,
                locationLabel = lastDestinationLabel,
                onOpen = { uri?.let { openPdf(context, it, vm::reportOpenFailed) } },
                onShare = { uri?.let { sharePdf(context, it, vm::reportOpenFailed) } },
                onDone = vm::dismissResult,
            )
        }
    if (exporting && project != null)
        PdfExportSheet(
            project = project,
            currentPageId = project.pages.getOrNull(state.page)?.id,
            selectedPageIds = state.selectedPages,
            defaultFilename = project.name,
            lastDestinationLabel = lastDestinationLabel,
            onEstimate = { choice, compact -> vm.estimateExportBytes(choice, compact) },
            onDismiss = { exporting = false },
            onExport = { filename, pagesChoice, compact ->
                exporting = false
                startNewExport(filename, pagesChoice, compact)
            },
        )
    if (deletePages) {
        val count = state.selectedPages.size.coerceAtLeast(1)
        val label =
            androidx.compose.ui.res.pluralStringResource(R.plurals.pdf_delete_pages_action, count, count)
        Confirm(R.string.pdf_deletepages, label, { deletePages = false }) {
            vm.removePages()
            deletePages = false
        }
    }
    deleteProject?.let { row ->
        Confirm(
            stringResource(R.string.pdf_library_delete_confirm, row.name),
            stringResource(R.string.pdf_deleteproject_action),
            { deleteProject = null },
        ) {
            vm.delete(row.id)
            deleteProject = null
        }
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun NumberField(label: String, value: Double, onValue: (Double) -> Unit) {
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

/**
 * Project details sheet: the rename field, reached either from the overflow or by tapping the top
 * bar title. Undo/Redo/Save used to live here too; they are now top-bar actions (Undo/Redo) or
 * implicit (autosave), so this sheet is rename-only.
 */
@Composable
internal fun ProjectDetails(vm: PdfStudioViewModel, state: PdfStudioState) {
    val project = state.project ?: return
    var title by remember(project.id) { mutableStateOf(project.name) }
    LaunchedEffect(project.id, project.name) { title = project.name }
    OutlinedTextField(
        title,
        {
            title = it.take(80)
            if (it.isNotBlank()) vm.update { p -> p.copy(name = it.take(80)) }
        },
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pdf_name)) },
        singleLine = true,
        enabled = !state.editorLocked,
    )
}
