package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.min

enum class PdfSaveState {
    Idle,
    Saving,
    Saved,
    Error,
}

data class PdfStudioState(
    val project: PdfProject? = null,
    val page: Int = 0,
    val image: Int = -1,
    /**
     * The selected text's id (Phase G1b), or null when no text is selected. Mutually exclusive
     * with [image] being >= 0 — [PdfStudioViewModel.selectText]/[PdfStudioViewModel.selectImage]
     * each clear the other. Kept as a separate field (rather than replacing [image] outright) so
     * every existing image call site — including the accessibility tests, which look up nodes by
     * "Image n"/"Resize image n" — keeps working unchanged; [selected] below is the generalized
     * view new code (the canvas's text rendering, the Layer/Align/Delete commands, the inspector)
     * reads instead of branching on both fields itself.
     */
    val selectedTextId: String? = null,
    /**
     * The multi-selection (Phase G2): element ids (images and texts, sharing one id space via
     * [PdfLayers.elementId]) currently selected as a group. Kept in sync with [image]/
     * [selectedTextId] — when it holds exactly one id, those two fields point at that same
     * element, exactly as before multi-select existed; every existing single-selection call site
     * therefore keeps working unchanged. [multiSelectMode] is the separate "explicitly in a
     * multi-select session" flag (entered by long-press or Ctrl+A, exited by tapping empty page
     * area/Escape/Back): [selectedIds] can hold a single id while this is true (right after a
     * long-press, before a second tap adds anything), so the two are not simply redundant.
     */
    val selectedIds: Set<String> = emptySet(),
    val multiSelectMode: Boolean = false,
    /** Edge-triggered "just added by Insert → Text" signal (Phase G1b): set once by [addText],
     * consumed once by the canvas (which opens inline editing then calls
     * [PdfStudioViewModel.newTextOpened]) so a later recomposition or re-selecting the same text
     * never re-opens the editor on its own. */
    val newTextId: String? = null,
    /** An operation is running: internal serialization guard, also drives the busy/progress row. */
    val busy: Boolean = false,
    /**
     * A project-mutating operation is running (open/delete/duplicate/new project/import commit).
     * Only this subset of `busy` disables Back, navigation, viewing/zoom and editing; background
     * work such as export queueing or gallery intake keeps the editor usable.
     */
    val editorLocked: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val message: Int? = null,
    val sourceError: Int? = null,
    /** Export whose Ready notice offers Save; cleared once saving starts or the notice goes. */
    val readyExport: String? = null,
    /**
     * The watched export's Published or Failed outcome, edge-triggered once: set the instant that
     * job reaches a terminal state, cleared on dismissal (and never reset by recomposition/rotation
     * since it lives in this state, nor re-shown for the same job after a process restart, since
     * [PdfStudioViewModel] guards it against the persisted `dismissedResult` id).
     */
    val resultJobId: String? = null,
    /** A job that reached Published or Failed while nothing was watching it (Phase B item 8). */
    val recoveryJobId: String? = null,
    /** Destination label for [recoveryJobId], resolved asynchronously; null while resolving. */
    val recoveryLabel: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedPages: Set<String> = emptySet(),
    /** Explicit multi-select mode in the Pages panel (Phase D): "Select" → "n selected" header,
     * check badges and the contextual Duplicate/Rotate/Delete bar. Independent of [selectedPages]
     * so leaving it clears the selection without affecting the export sheet's page choice. */
    val pagesSelectionMode: Boolean = false,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val saveState: PdfSaveState = PdfSaveState.Idle,
    /**
     * Phase F item 3 review fix (MAJOR): a single-image Media panel insert into the currently
     * open project must NOT set [editorLocked] (back/navigation/zoom/other pages stay usable, and
     * only the background progress chip shows - [backgroundBusy]), but the project's CONTENT
     * still must not be mutated by anything else while the insert's slow copy is in flight, since
     * [PdfProjectRepository.importMediaIntoPage] computes its append against whatever is
     * authoritative at commit time and [PdfStudioViewModel.restoreEditor] then replaces the
     * in-memory project wholesale with that committed result - a concurrent edit that never made
     * it to that snapshot would simply vanish. [update]/[pageEdit] and [scheduleSave] check this
     * flag (distinct from [editorLocked]) to serialize project mutation without blocking
     * navigation.
     */
    val mutationLocked: Boolean = false,
) {
    /** Busy purely from background work (export queueing, gallery retry/discard, autosave). */
    val backgroundBusy: Boolean
        get() = busy && !editorLocked

    /** Generalized view of [image]/[selectedTextId] (Phase G1b) — null when nothing is selected. */
    val selected: PdfElementRef?
        get() =
            selectedTextId?.let(PdfElementRef::Text)
                ?: image.takeIf { it >= 0 }?.let(PdfElementRef::Image)

    /** True once the contextual multi-select toolbar/group inspector should show (Phase G2): a
     * multi-select session with 2+ members. A lone long-pressed element ([selectedIds].size == 1)
     * still shows the ordinary single-element inspector/toolbar. */
    val groupSelected: Boolean
        get() = multiSelectMode && selectedIds.size >= 2
}

class PdfStudioViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    val repository = PdfProjectRepository(application)
    val exportQueue = PdfExportQueue(application)
    val exportJobs =
        exportQueue.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val projects =
        repository.projects.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    private val mutable = MutableStateFlow(PdfStudioState())
    val state = mutable.asStateFlow()
    private val undo = ArrayDeque<PdfProject>()
    private val redo = ArrayDeque<PdfProject>()
    /**
     * Per-page zoom/pan (Phase C item 6): keyed by page id, seeded from the restored session and
     * updated by [viewport]. [selectPage] reads from here instead of resetting to 1x/centered.
     */
    private var pageViewports: Map<String, PdfViewport> = emptyMap()
    private var task: Job? = null
    private var autosave: Job? = null
    internal val publishPicker = PdfPublishPicker(saved)
    val pendingPublication = publishPicker.pending
    private var publishing: Job? = null
    private val exportDraftStore = PdfExportDraftStore(saved)
    private val lastDestinationStore = PdfLastDestinationStore(application)
    /**
     * The export sheet's Custom pages field text, kept per project id for the life of this
     * ViewModel only (Phase G3 item 2: "remember for the session", not persisted to Room or
     * SavedStateHandle — losing it on process death is an acceptable trade-off for a draft this
     * short-lived, unlike the destination-first [exportDraftStore]).
     */
    private val customRangeDrafts = mutableMapOf<String, String>()
    private val ackStore = PdfExportAcknowledgementStore(application)
    private val mutableLastDestinationLabel = MutableStateFlow<String?>(null)
    /** "Save to: <label>" text for the export sheet's destination row, refreshed on demand. */
    val lastDestinationLabel = mutableLastDestinationLabel.asStateFlow()
    /** The export currently followed for progress/result, reactive so the UI can show it live. */
    val watchedExportId = saved.getStateFlow<String?>(WATCHED_EXPORT, null)
    /** True once this job has been dismissed, so a later process restart never re-shows its sheet. */
    private var dismissedResult: String?
        get() = saved["pdfDismissedResult"]
        set(value) {
            saved["pdfDismissedResult"] = value
        }

    /** Seeds `DocumentsContract.EXTRA_INITIAL_URI` so the next picker opens near the last one. */
    internal fun lastDestinationUriOrNull(): Uri? = lastDestinationStore.lastDestination
    internal val importPicker = PdfImportPicker(saved)
    val pendingImport = importPicker.pending
    private var activeImport: String? = null
    private val importInbox = PdfImportInbox(application)
    private var receivingImport: Job? = null
    private var recoveringImports: Job? = null
    private val galleryIntake = PdfGalleryIntake(application)
    val galleryDeliveries =
        galleryIntake.deliveries.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    private var galleryRecovery: Job? = null
    private var activeGallery: String? = null
    /** The export this studio queued or saved, so its outcome replaces the "queued" notice. */
    private var watchedExport: String?
        get() = saved[WATCHED_EXPORT]
        set(value) {
            saved[WATCHED_EXPORT] = value
            watchedPhase = null
        }
    private var watchedPhase: PdfExportPhase? = null

    init {
        saved.get<String>("projectId")?.let { open(it) }
        refreshLastDestinationLabel()
        resumeImport()
        // v9 backfill (Phase E): rows saved before the pageCount/sourceBytes/coverPageId columns
        // existed carry pageCount = 0; compute and persist them once, off the main thread.
        viewModelScope.launch { runCatching { repository.backfillSummaries() } }
        viewModelScope.launch { galleryIntake.deliveries.collect { resumeGallery() } }
        viewModelScope.launch { exportQueue.jobs.collect(::followExport) }
        viewModelScope.launch {
            runCatching { exportQueue.reconcile() }
                .onFailure {
                    mutable.update { it.copy(message = R.string.pdf_queue_recovery_error) }
                }
        }
        // Deterministic order in one coroutine: resume any outstanding publish/export-draft
        // delivery (which can itself enqueue a job) and let it fully finish before deciding which
        // job, if any, finished unwatched - otherwise scanForUnwatchedOutcome could race a
        // publication that is still in flight and either miss it or double-report it.
        viewModelScope.launch {
            resumePublication()
            publishing?.join()
            scanForUnwatchedOutcome()
        }
    }

    /**
     * Phase B item 8: a job can reach Published or Failed while this studio was not running to
     * observe it (process death, or a background worker finishing after the app was swiped away).
     * On (re)open, look once for the most recent such job that nothing has acknowledged yet and
     * surface exactly one inline recovery notice for it; [dismissRecovery] (dismiss, Open or Retry)
     * marks it acknowledged so it is never shown again. A job this instance is actively
     * [watchedExport]ing is excluded: [followExport] already reports its outcome live.
     */
    private suspend fun scanForUnwatchedOutcome() {
        val jobs = exportQueue.jobs.first()
        ackStore.prune(jobs.map { it.id }.toSet())
        val watched = watchedExport
        val candidate =
            jobs
                .asSequence()
                .filter { it.phase in setOf(PdfExportPhase.Published, PdfExportPhase.Failed) }
                .filter { it.id != watched }
                .filter { !ackStore.isAcknowledged(it.id) }
                .maxByOrNull { it.updated } ?: return
        val label =
            if (candidate.phase == PdfExportPhase.Published)
                candidate.destination?.let {
                    resolveDestinationLabel(
                        getApplication(),
                        Uri.parse(it),
                        getApplication<Application>().getString(R.string.pdf_export_destination_default),
                    )
                }
            else null
        mutable.update { it.copy(recoveryJobId = candidate.id, recoveryLabel = label) }
    }

    /** Dismiss the recovery notice (explicit dismiss, or after Open/Retry act on it). */
    fun dismissRecovery() {
        mutable.value.recoveryJobId?.let(ackStore::acknowledge)
        mutable.update { it.copy(recoveryJobId = null, recoveryLabel = null) }
    }

    /**
     * @param lock whether this operation is project-mutating (open/delete/duplicate/new
     *   project/import commit) and must disable Back, navigation, viewing/zoom and editing while
     *   it runs. Background work (export queueing, gallery retry/discard, cancel) passes false so
     *   the editor stays usable; only `busy`/the progress row reflect it.
     */
    private fun operation(lock: Boolean = true, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        task =
            viewModelScope.launch {
                mutable.update {
                    it.copy(
                        busy = true,
                        editorLocked = lock,
                        message = null,
                        sourceError = null,
                        readyExport = null,
                    )
                }
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(e)
                } finally {
                    mutable.update { it.copy(busy = false, editorLocked = false, progress = null) }
                    if (viewModelScope.isActive) {
                        resumeImport()
                        resumeGallery()
                    }
                }
            }
    }

    suspend fun receiveGallery(id: String, name: String, uris: List<Uri>): Boolean =
        try {
            galleryIntake.stage(id, name, uris)
            resumeGallery()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(e)
            false
        }

    private fun resumeGallery() {
        if (mutable.value.busy || galleryRecovery?.isActive == true) return
        galleryRecovery =
            viewModelScope.launch {
                val row =
                    try {
                        galleryIntake.pending()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportFailure(e)
                        null
                    } ?: return@launch
                if (mutable.value.busy) return@launch
                // Phase F item 3 review fix (MAJOR): a targeted single-image delivery (Media
                // panel tap/drop into the currently open project) must not set editorLocked - the
                // editor, navigation and zoom stay usable, only the background progress chip
                // shows (state.backgroundBusy). A batch gallery selection (no target: it always
                // builds a brand-new project nothing else could be editing yet) keeps the
                // original locking behavior unchanged.
                val targeted = row.targetProjectId != null
                operation(lock = !targeted) {
                    activeGallery = row.id
                    // mutationLocked (distinct from editorLocked) blocks project-content edits
                    // and autosave writes for the duration (see PdfStudioState.mutationLocked's
                    // doc) so the append this computes can never be clobbered by, or itself
                    // clobber, a concurrent edit - without disabling navigation/zoom.
                    if (targeted) mutable.update { it.copy(mutationLocked = true) }
                    try {
                        autosave?.cancelAndJoin()
                        persistCurrent()
                        val project =
                            galleryIntake.process(row, repository) { n, total ->
                                mutable.update { it.copy(progress = n to total) }
                            }
                        val (_, editor) = requireNotNull(repository.loadEditor(project.id))
                        restoreEditor(project, editor)
                    } catch (e: CancellationException) {
                        if (viewModelScope.isActive)
                            withContext(NonCancellable) {
                                val receipt =
                                    PdfProjectDatabase.get(getApplication()).imports().get(row.id)
                                if (receipt == null) galleryIntake.failed(row.id, "Cancelled")
                                else {
                                    repository.loadEditor(receipt.projectId)?.let {
                                        (project, editor) ->
                                        restoreEditor(project, editor)
                                    }
                                    galleryIntake.discard(row.id)
                                }
                            }
                        throw e
                    } catch (e: Exception) {
                        galleryIntake.failed(
                            row.id,
                            PdfFailure.from(e).name,
                            (e as? PdfSourceFailure)?.number,
                        )
                        throw e
                    } finally {
                        if (targeted) mutable.update { it.copy(mutationLocked = false) }
                        activeGallery = null
                    }
                }
            }
    }

    fun retryGallery(id: String) {
        viewModelScope.launch {
            try {
                galleryIntake.retry(id)
                resumeGallery()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    fun discardGallery(id: String) {
        if (activeGallery == id) return
        viewModelScope.launch {
            try {
                galleryIntake.discard(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    /** "Replace photo" on a failed gallery delivery: swap the rejected source, then retry the
     * whole batch (all-or-nothing, per the Phase E product decision). */
    fun replaceGallerySource(id: String, index: Int, replacement: Uri) {
        if (activeGallery == id) return
        viewModelScope.launch {
            try {
                galleryIntake.replaceSource(id, index, replacement)
                resumeGallery()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    /** "Remove & retry" on a failed gallery delivery: drop the rejected source, then retry the
     * remaining sources as a batch. */
    fun removeGallerySource(id: String, index: Int) {
        if (activeGallery == id) return
        viewModelScope.launch {
            try {
                galleryIntake.removeSource(id, index)
                resumeGallery()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    /**
     * Creates a new project from the "New project" sheet (Phase E item 3). [widthMm]/[heightMm]
     * are in portrait orientation; [landscape] swaps them. [columns] seeds the photos-per-page
     * grid template (see [PdfLayoutTemplates]) via [PdfGeometry.grid] on the initial blank page.
     */
    fun newProject(
        name: String,
        widthMm: Double = 210.0,
        heightMm: Double = 297.0,
        landscape: Boolean = false,
        columns: Int = 2,
        gap: Double = 4.0,
        margin: Double = 10.0,
    ) = operation {
        val w = if (landscape) heightMm else widthMm
        val h = if (landscape) widthMm else heightMm
        val page = PdfPage(width = w, height = h, margin = margin.coerceAtMost(min(w, h) / 4))
        val p = PdfProject(name = name, pages = listOf(page), columns = columns, gap = gap)
        repository.save(p)
        setProject(p)
    }

    fun open(id: String) = operation {
        autosave?.cancelAndJoin()
        persistCurrent()
        val (p, session) = requireNotNull(repository.loadEditor(id))
        restoreEditor(p, session)
    }

    private fun restoreEditor(p: PdfProject, session: PdfEditorSession) {
        setProject(p)
        undo.addAll(session.undo)
        redo.addAll(session.redo)
        val page = p.pages.indexOfFirst { it.id == session.pageId }.coerceAtLeast(0)
        val viewport = session.viewportFor(p.pages[page].id)
        // Seed the current page's entry even for a legacy session whose `viewports` map doesn't
        // have it (viewportFor() above already fell back to the legacy top-level zoom/pan for
        // it): without this, switching away and back to this page before ever calling viewport()
        // on it would look it up as absent and silently reset to 1x/centered.
        pageViewports = session.viewports + (p.pages[page].id to viewport)
        mutable.update {
            it.copy(
                page = page,
                image = p.pages[page].images.indexOfFirst { image -> image.id == session.imageId },
                selectedTextId = session.textId?.takeIf { id -> p.pages[page].texts.any { it.id == id } },
                selectedPages = session.selectedPages,
                canUndo = undo.isNotEmpty(),
                canRedo = redo.isNotEmpty(),
                zoom = viewport.zoom,
                panX = viewport.panX,
                panY = viewport.panY,
            )
        }
    }

    private fun session(): PdfEditorSession {
        val s = mutable.value
        val p = s.project
        val currentPageId = p?.pages?.getOrNull(s.page)?.id
        val viewports =
            if (currentPageId != null)
                pageViewports + (currentPageId to PdfViewport(s.zoom, s.panX, s.panY))
            else pageViewports
        return PdfEditorSession(
            pageId = currentPageId,
            imageId = p?.pages?.getOrNull(s.page)?.images?.getOrNull(s.image)?.id,
            textId = s.selectedTextId,
            selectedPages = s.selectedPages,
            undo = undo.toList(),
            redo = redo.toList(),
            zoom = s.zoom,
            panX = s.panX,
            panY = s.panY,
            viewports = viewports,
        )
    }

    private suspend fun persistCurrent() {
        mutable.value.project?.let { repository.save(it, session()) }
    }

    private fun scheduleSave() {
        val project = mutable.value.project ?: return
        // Phase F item 3 review fix: scheduleSave captures `project`/`editor` NOW but only
        // writes them ~400ms later. A view-only change (viewport()/selectPage(), neither of
        // which check mutationLocked since they don't touch project content) could still queue a
        // stale write that lands after a targeted Media insert commits its own, newer project to
        // disk - silently reverting the insert. Simplest safe fix: never schedule a write while a
        // background insert has the project content locked; nothing is lost since mutationLocked
        // also blocks every actual content edit, so there is nothing new to persist here besides
        // (at most) view state, which restoreEditor's own session already covers once the insert
        // finishes.
        if (mutable.value.mutationLocked) return
        val editor = session()
        autosave?.cancel()
        // A pinch/pan gesture calls scheduleSave() on every frame; flipping saveState to Saving
        // every time (when it already is) is pure per-frame state churn for a value that isn't
        // changing, so only write it on the actual Idle/Saved/Error -> Saving transition.
        if (mutable.value.saveState != PdfSaveState.Saving)
            mutable.update { it.copy(saveState = PdfSaveState.Saving) }
        autosave =
            viewModelScope.launch {
                delay(400)
                try {
                    repository.save(project, editor)
                    mutable.update { it.copy(saveState = PdfSaveState.Saved) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(message = R.string.pdf_saveerror, saveState = PdfSaveState.Error)
                    }
                }
            }
    }

    private fun setProject(p: PdfProject?) {
        undo.clear()
        redo.clear()
        pageViewports = emptyMap()
        saved["projectId"] = p?.id
        val previous = mutable.value
        mutable.value =
            PdfStudioState(
                project = p,
                busy = previous.busy,
                editorLocked = previous.editorLocked,
                message = previous.message,
                sourceError = previous.sourceError,
            )
    }

    /**
     * Leaving the editor must never be silently swallowed by [operation]'s busy guard: a
     * background, non-locking op (export enqueue/cancel/retry, gallery retry/discard...) can be
     * `busy` without `editorLocked`, and Back stays enabled/tappable through exactly that window.
     * So this joins whatever is currently running first — safe even if that is itself a
     * project-mutating op, since setProject/persistCurrent only run after it has finished — and
     * only then persists and clears the open project, instead of going through [operation] and
     * returning early when `busy` is already true.
     */
    fun library() {
        val previous = task
        task =
            viewModelScope.launch {
                previous?.let { runCatching { it.join() } }
                mutable.update {
                    it.copy(
                        busy = true,
                        editorLocked = true,
                        message = null,
                        sourceError = null,
                        readyExport = null,
                    )
                }
                try {
                    autosave?.cancelAndJoin()
                    persistCurrent()
                    setProject(null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(e)
                } finally {
                    mutable.update { it.copy(busy = false, editorLocked = false, progress = null) }
                    if (viewModelScope.isActive) {
                        resumeImport()
                        resumeGallery()
                    }
                }
            }
    }

    fun delete(id: String) = operation {
        repository.delete(
            id,
            undo.flatMap { it.usedAssets() }.toSet() + redo.flatMap { it.usedAssets() },
        )
    }

    fun duplicate(id: String) = operation {
        val p = requireNotNull(repository.load(id))
        val name =
            getApplication<Application>()
                .getString(R.string.pdf_library_duplicate_suffix, p.name)
                .take(80)
        repository.save(p.copy(id = newId(), name = name))
    }

    /** Rename from the library list (Phase E); the project is never open while the library is
     * shown, so this never has to reconcile with in-memory editor state. */
    fun renameProject(id: String, name: String) = operation {
        val trimmed = name.trim().take(80)
        if (trimmed.isEmpty()) return@operation
        val p = requireNotNull(repository.load(id))
        repository.save(p.copy(name = trimmed))
    }

    /** "Export project file" from a library card's overflow menu, for a project that is not the
     * one currently open (see [portable] for that case). */
    fun portableFor(id: String) = operation(lock = false) {
        val p = requireNotNull(repository.load(id))
        watchedExport = exportQueue.enqueue(p, compact = false, portable = true).id
        mutable.update { it.copy(message = R.string.pdf_queue_added) }
    }

    /**
     * Media panel tap-insert or drag & drop (Phase F item 3): stages a gallery delivery targeted
     * at the CURRENT project/page (v9's `gallery_deliveries.targetProjectId`/`targetPageId`/
     * `placementX`/`placementY`), then lets the existing [resumeGallery] pipeline durably append
     * it — same receipts/all-or-nothing/idempotency/one-undo-step guarantees as every other
     * import path, and `restoreEditor` simply refreshes the already-open project in place since
     * the target IS the open project. [placementXMm]/[placementYMm], when given, are the drop
     * point's page-space CENTER (screen->mm conversion is the canvas/rail's job); null means
     * "auto-place" (the repository's existing next-slot heuristic).
     */
    fun insertMedia(uri: Uri, placementXMm: Double? = null, placementYMm: Double? = null) {
        insertMediaIntoPage(uri, mutable.value.page, placementXMm, placementYMm)
    }

    /** Same as [insertMedia] but targets an explicit page index (dropping onto a page thumbnail
     * in the rail rather than onto the canvas), always auto-placed. */
    fun insertMediaIntoPage(
        uri: Uri,
        pageIndex: Int,
        placementXMm: Double? = null,
        placementYMm: Double? = null,
    ) {
        // Review fix: these two guards used to `return` bare - a silent no-op indistinguishable
        // from "nothing happened", exactly the reported symptom (tap does nothing, no issue
        // card). Both cases should be effectively unreachable from the Media panel (it only
        // renders while a project/page is open), so surfacing the generic failure message is a
        // deliberate "this should never happen, but if it does, say so" guard, not a real
        // day-to-day error path.
        val project = mutable.value.project
        val pageId = project?.pages?.getOrNull(pageIndex)?.id
        if (project == null || pageId == null) {
            mutable.update { it.copy(message = R.string.pdf_error) }
            return
        }
        viewModelScope.launch {
            try {
                galleryIntake.stage(
                    id = "media-${newId()}",
                    name = project.name,
                    sources = listOf(uri),
                    targetProjectId = project.id,
                    targetPageId = pageId,
                    placementX = placementXMm,
                    placementY = placementYMm,
                )
                resumeGallery()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    /** Media panel "In this project" chip (Phase F item 3): re-inserts an asset the project
     * already owns by content hash onto the current page, with no copy and no gallery-intake
     * round trip — it is a plain edit, so it goes through [pageEdit]/[update] like every other
     * canvas edit (one undo step, autosave, the same [PdfProject.validate] limit checks). */
    fun insertOwnAsset(hash: String) {
        val project = mutable.value.project ?: return
        val asset = project.assets.firstOrNull { it.hash == hash } ?: return
        if (asset.width <= 0 || asset.height <= 0) return
        val page = project.pages.getOrNull(mutable.value.page) ?: return
        if (!PdfMediaPlacement.hasRoomForOneMore(page.images.size + page.texts.size)) {
            mutable.update { it.copy(message = R.string.pdf_failure_limit) }
            return
        }
        pageEdit { editedPage ->
            val page = editedPage
            val (w, h) = PdfMediaPlacement.fitSize(page.width, page.margin, asset.width, asset.height)
            val (x, y) = PdfMediaPlacement.autoSlotTopLeft(page.images.size, page.margin)
            val image =
                PdfGeometry.constrainToPage(PdfImage(asset = hash, x = x, y = y, width = w, height = h), page)
            page.copy(images = page.images + image)
        }
    }

    fun update(transform: (PdfProject) -> PdfProject) {
        val old = mutable.value.project ?: return
        if (mutable.value.editorLocked || mutable.value.mutationLocked) return
        runCatching { transform(old).validate() }
            .onSuccess { next ->
                if (next == old) return@onSuccess
                undo.addLast(old)
                if (undo.size > 40) undo.removeFirst()
                redo.clear()
                apply(next)
            }
            .onFailure { mutable.update { it.copy(message = R.string.pdf_error) } }
    }

    private fun apply(p: PdfProject) {
        mutable.update {
            val page = p.pages[it.page.coerceIn(0, p.pages.lastIndex)]
            // Multi-selection pruning (Phase G2): drop any id that no longer exists on this page
            // (deleted directly, or a page change/undo/redo swapped the page out from under it —
            // like [selectedTextId] already did for the single-selection case below).
            val prunedIds = it.selectedIds.intersect(elementIds(page))
            val (groupImage, groupText) = selectionFields(page, prunedIds)
            it.copy(
                project = p,
                page = it.page.coerceIn(0, p.pages.lastIndex),
                // While a real multi-selection (or a lone long-pressed element) is tracked, it is
                // now the source of truth for image/selectedTextId; otherwise fall back to the
                // pre-G2 clamp/prune so every call site that never touches selectedIds (most of
                // this file) is unaffected.
                image = if (it.selectedIds.isEmpty()) it.image.coerceAtMost(page.images.lastIndex) else groupImage,
                // A selected text can vanish out from under a concurrent undo/redo (or simply be
                // deleted); unlike the image index (which just clamps), an id has nothing sane to
                // clamp to, so it's cleared instead of possibly pointing at an unrelated text.
                selectedTextId =
                    if (it.selectedIds.isEmpty())
                        it.selectedTextId?.takeIf { id -> page.texts.any { t -> t.id == id } }
                    else groupText,
                selectedIds = prunedIds,
                multiSelectMode = it.multiSelectMode && prunedIds.isNotEmpty(),
                canUndo = undo.isNotEmpty(),
                canRedo = redo.isNotEmpty(),
                selectedPages = it.selectedPages.intersect(p.pages.map { page -> page.id }.toSet()),
                message = null,
            )
        }
        scheduleSave()
    }

    /** Every element id (image or text) on [page] — the two kinds share one id space via
     * [PdfLayers.elementId]. */
    private fun elementIds(page: PdfPage): Set<String> =
        page.images.mapTo(mutableSetOf<String>()) { it.id }.apply { addAll(page.texts.map { it.id }) }

    /** The single-selection (image, text) fields [ids] maps to: both null/`-1` when [ids] doesn't
     * hold exactly one id, otherwise whichever kind that one id belongs to on [page]. Shared by
     * every selection-mutating function below and by [apply]'s pruning so the two single-selection
     * fields always agree with [PdfStudioState.selectedIds] whenever it holds exactly one id. */
    private fun selectionFields(page: PdfPage, ids: Set<String>): Pair<Int, String?> {
        val single = ids.singleOrNull() ?: return -1 to null
        val imageIndex = page.images.indexOfFirst { it.id == single }
        return if (imageIndex >= 0) imageIndex to null
        else -1 to single.takeIf { id -> page.texts.any { it.id == id } }
    }

    private fun setSelection(page: PdfPage, ids: Set<String>, multiMode: Boolean) {
        val (imageIndex, textId) = selectionFields(page, ids)
        mutable.update {
            it.copy(selectedIds = ids, multiSelectMode = multiMode, image = imageIndex, selectedTextId = textId)
        }
        scheduleSave()
    }

    /** Long-press an element (Phase G2): starts a multi-select session containing just that one
     * element — a further tap on another element (see [toggleMultiSelect]) adds it. */
    fun enterMultiSelect(id: String) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (mutable.value.editorLocked || id !in elementIds(page)) return
        setSelection(page, setOf(id), multiMode = true)
    }

    /** Tap on an element while already in a multi-select session (Phase G2): toggles its
     * membership. Exits the session automatically once the last id is removed. A no-op outside a
     * multi-select session — a plain tap then goes through [selectImage]/[selectText] instead. */
    fun toggleMultiSelect(id: String) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (mutable.value.editorLocked || !mutable.value.multiSelectMode || id !in elementIds(page)) return
        val current = mutable.value.selectedIds
        val next = if (id in current) current - id else current + id
        setSelection(page, next, multiMode = next.isNotEmpty())
    }

    /**
     * Fix-round item 3: a mouse/keyboard Shift+click or Ctrl+click on an element toggles its
     * membership — while already in a multi-select session this is exactly [toggleMultiSelect];
     * otherwise it starts one from whatever was singly selected (if anything, via
     * [PdfStudioState.selected]) plus [id], matching the conventional desktop "add to selection"
     * gesture (Figma/Keynote-style) rather than discarding the prior single selection the way
     * [enterMultiSelect] (long-press) intentionally does.
     */
    fun toggleSelectionWithModifier(id: String) {
        if (mutable.value.editorLocked) return
        if (mutable.value.multiSelectMode) {
            toggleMultiSelect(id)
            return
        }
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val currentId =
            when (val ref = mutable.value.selected) {
                is PdfElementRef.Image -> page.images.getOrNull(ref.index)?.id
                is PdfElementRef.Text -> ref.id
                null -> null
            }
        setMultiSelection(if (currentId == id) setOf(id) else setOfNotNull(currentId, id))
    }

    /** Ctrl+A / "Select all" (Phase G2): selects every element on the current page as a group. */
    fun selectAllOnPage() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (mutable.value.editorLocked) return
        val all = elementIds(page)
        if (all.isEmpty()) return
        setSelection(page, all, multiMode = true)
    }

    /** A pointer marquee drag (Phase G2) replaces the whole selection with [ids] (may be empty —
     * an empty marquee just clears it without entering multi-select mode), and Shift/Ctrl+click
     * toggling routes through here too via the caller computing the next set. Always enters
     * multi-select mode when more than one id ends up selected. */
    fun setMultiSelection(ids: Set<String>) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (mutable.value.editorLocked) return
        val next = ids.intersect(elementIds(page))
        setSelection(page, next, multiMode = next.size > 1 || (next.isNotEmpty() && mutable.value.multiSelectMode))
    }

    /** Tap on empty page area / Escape / Back while in a multi-select session (Phase G2): clears
     * the whole selection and exits the session. */
    fun exitMultiSelect() {
        if (mutable.value.selectedIds.isEmpty() && !mutable.value.multiSelectMode &&
            mutable.value.image < 0 && mutable.value.selectedTextId == null
        )
            return
        mutable.update {
            it.copy(selectedIds = emptySet(), multiSelectMode = false, image = -1, selectedTextId = null)
        }
        scheduleSave()
    }

    /** The current group's members as (id, bounds) pairs — images then texts, matching
     * [PdfLayers.order]'s tie-break. Used by every group operation below. Empty when fewer than 2
     * ids are selected (those go through the ordinary single-element path instead). */
    private fun groupMembers(page: PdfPage): List<Pair<String, PdfArrange.Bounds>> {
        val ids = mutable.value.selectedIds
        if (ids.size < 2) return emptyList()
        val images = page.images.filter { it.id in ids }.map { it.id to PdfArrange.boundsOf(it) }
        val texts = page.texts.filter { it.id in ids }.map { it.id to PdfArrange.boundsOf(it) }
        return images + texts
    }

    /**
     * Moves the whole group by ([dx], [dy]) mm — one undo step — clamped ([PdfArrange.clampGroupMove])
     * so every member stays on the page. Falls back to [moveSelected] (single element) when fewer
     * than 2 are selected, so the canvas's keyboard-nudge and drag-end handlers can call this
     * unconditionally without checking selection size themselves. Used both for the arrow-key
     * group nudge and for committing a group drag's total delta at drag end (mirroring the
     * single-image drag pattern: a live preview is drawn locally by the canvas, and only the final
     * delta is committed here, in ONE call).
     */
    fun moveSelectionBy(dx: Double, dy: Double) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val members = groupMembers(page)
        if (members.isEmpty()) {
            moveSelected(dx, dy)
            return
        }
        val (cdx, cdy) = PdfArrange.clampGroupMove(dx, dy, members.map { it.second }, page.width, page.height)
        if (cdx == 0.0 && cdy == 0.0) return
        val ids = members.map { it.first }.toSet()
        pageEdit { p ->
            p.copy(
                images = p.images.map { if (it.id in ids) it.copy(x = it.x + cdx, y = it.y + cdy) else it },
                texts = p.texts.map { if (it.id in ids) it.copy(x = it.x + cdx, y = it.y + cdy) else it },
            )
        }
    }

    /** Deletes every selected element as ONE undo step (Phase G2); falls back to [deleteSelected]
     * (single element) when fewer than 2 are selected. Exits multi-select afterward. */
    fun deleteGroupSelection() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val ids = mutable.value.selectedIds
        if (ids.size < 2) {
            deleteSelected()
            return
        }
        pageEdit { p ->
            p.copy(
                images = p.images.filterNot { it.id in ids },
                texts = p.texts.filterNot { it.id in ids },
            )
        }
        exitMultiSelect()
    }

    /**
     * Duplicates every selected element as ONE undo step (Phase G2), offset like the single-element
     * [duplicateSelected], and selects the copies as the new group. Falls back to
     * [duplicateSelected] when fewer than 2 are selected.
     */
    fun duplicateGroupSelection() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val ids = mutable.value.selectedIds
        if (ids.size < 2) {
            duplicateSelected()
            return
        }
        // Fix-round item 8: check the 24-elements-per-page cap for ALL N copies up front, instead
        // of letting pageEdit's own validate() reject the whole batch and fall through to the
        // generic pdf_error message — this surfaces the specific "page is full" reason, matching
        // every other place this cap is enforced (addText, the Media panel insert).
        if (!PdfMediaPlacement.hasRoomForOneMore(page.images.size + page.texts.size + ids.size - 1)) {
            mutable.update { it.copy(message = PdfFailure.PageFull.message) }
            return
        }
        val offset = 8.0
        val imageCopies = page.images.filter { it.id in ids }.map { it.copy(id = newId()) }
        var nextZ = PdfLayers.nextZ(page)
        val textCopies =
            page.texts.filter { it.id in ids }.map {
                val copy = it.copy(id = newId(), z = nextZ)
                nextZ += 1
                copy
            }
        pageEdit { p ->
            val withImages =
                p.copy(
                    images =
                        p.images +
                            imageCopies.map { PdfGeometry.constrainToPage(it.copy(x = it.x + offset, y = it.y + offset), p) }
                )
            withImages.copy(
                texts =
                    withImages.texts +
                        textCopies.map {
                            PdfGeometry.constrainTextToPage(it.copy(x = it.x + offset, y = it.y + offset), withImages)
                        }
            )
        }
        val copyIds = imageCopies.map { it.id }.toSet() + textCopies.map { it.id }.toSet()
        val updatedPage = mutable.value.project?.pages?.getOrNull(mutable.value.page)
        if (updatedPage != null) setSelection(updatedPage, copyIds.intersect(elementIds(updatedPage)), multiMode = true)
    }

    /**
     * Aligns every selected element to [align] relative to the GROUP's own bounding box (Phase
     * G2) — as opposed to [alignSelectedImage]/[alignSelectedText]'s page/margins reference frame
     * for a single element. One undo step. A no-op with fewer than 2 selected (the single-element
     * Align menu already covers that case with its own reference frame).
     */
    fun alignGroupSelection(align: PdfGeometry.Align) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val members = groupMembers(page)
        if (members.isEmpty()) return
        val group = PdfArrange.union(members.map { it.second })
        val deltas = members.associate { (id, bounds) -> id to PdfArrange.alignDelta(bounds, group, align) }
        pageEdit { p ->
            p.copy(
                images =
                    p.images.map { img ->
                        deltas[img.id]?.let { (dx, dy) ->
                            PdfGeometry.constrainToPage(img.copy(x = img.x + dx, y = img.y + dy), p)
                        } ?: img
                    },
                texts =
                    p.texts.map { txt ->
                        deltas[txt.id]?.let { (dx, dy) ->
                            PdfGeometry.constrainTextToPage(txt.copy(x = txt.x + dx, y = txt.y + dy), p)
                        } ?: txt
                    },
            )
        }
    }

    /**
     * Distributes every selected element with equal gaps along [orientation] (Phase G2) — one
     * undo step, a no-op with fewer than 3 selected (enforced by [PdfArrange.distributeHorizontal]/
     * [distributeVertical] returning an empty map, so the UI's own "needs 3+" disabled state is
     * just a courtesy, not the only guard).
     */
    fun distributeGroupSelection(orientation: PdfSnapGuides.Orientation) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val members = groupMembers(page)
        if (members.size < 3) return
        val newLead =
            if (orientation == PdfSnapGuides.Orientation.Horizontal) PdfArrange.distributeHorizontal(members)
            else PdfArrange.distributeVertical(members)
        if (newLead.isEmpty()) return
        pageEdit { p ->
            p.copy(
                images =
                    p.images.map { img ->
                        newLead[img.id]?.let { lead ->
                            val moved =
                                if (orientation == PdfSnapGuides.Orientation.Horizontal) img.copy(x = lead)
                                else img.copy(y = lead)
                            PdfGeometry.constrainToPage(moved, p)
                        } ?: img
                    },
                texts =
                    p.texts.map { txt ->
                        newLead[txt.id]?.let { lead ->
                            val moved =
                                if (orientation == PdfSnapGuides.Orientation.Horizontal) txt.copy(x = lead)
                                else txt.copy(y = lead)
                            PdfGeometry.constrainTextToPage(moved, p)
                        } ?: txt
                    },
            )
        }
    }

    fun undo() {
        if (undo.isEmpty() || mutable.value.editorLocked) return
        redo.addLast(requireNotNull(mutable.value.project))
        apply(undo.removeLast())
    }

    fun redo() {
        if (redo.isEmpty() || mutable.value.editorLocked) return
        undo.addLast(requireNotNull(mutable.value.project))
        apply(redo.removeLast())
    }

    fun save() = operation(lock = false) {
        autosave?.cancelAndJoin()
        persistCurrent()
        mutable.update { it.copy(message = R.string.pdf_savedlocal) }
    }

    fun selectPage(n: Int) {
        val p = mutable.value.project ?: return
        if (mutable.value.editorLocked || n !in p.pages.indices) return
        // Phase C item 6: the viewport is per page now, so switching pages restores whatever
        // zoom/pan that page last had (1x/centered the first time it is visited) instead of
        // always resetting to 1x/centered.
        val viewport = pageViewports[p.pages[n].id] ?: PdfViewport()
        mutable.update {
            it.copy(
                page = n,
                image = -1,
                selectedTextId = null,
                // A page change prunes the multi-selection entirely (Phase G2): it's a different
                // page's elements now.
                selectedIds = emptySet(),
                multiSelectMode = false,
                zoom = viewport.zoom,
                panX = viewport.panX,
                panY = viewport.panY,
            )
        }
        scheduleSave()
    }

    fun selectImage(n: Int) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (mutable.value.editorLocked || n !in -1..page.images.lastIndex) return
        val id = page.images.getOrNull(n)?.id
        mutable.update {
            it.copy(
                image = n,
                selectedTextId = null,
                // A plain tap (as opposed to long-press/toggle) always replaces the selection with
                // just this element and leaves any multi-select session (Phase G2).
                selectedIds = if (id != null) setOf(id) else emptySet(),
                multiSelectMode = false,
            )
        }
        scheduleSave()
    }

    /** Selects the text with [id] on the current page (Phase G1b), clearing any selected image —
     * mutual exclusion until multi-select (Phase G2). A no-op if [id] doesn't exist on the current
     * page. Pass `null` to clear the text selection. */
    fun selectText(id: String?) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (mutable.value.editorLocked || (id != null && page.texts.none { it.id == id })) return
        mutable.update {
            it.copy(
                image = -1,
                selectedTextId = id,
                selectedIds = if (id != null) setOf(id) else emptySet(),
                multiSelectMode = false,
            )
        }
        scheduleSave()
    }

    fun selectExportPage(id: String, selected: Boolean) {
        if (mutable.value.editorLocked || mutable.value.project?.pages?.none { it.id == id } != false)
            return
        mutable.update {
            it.copy(selectedPages = if (selected) it.selectedPages + id else it.selectedPages - id)
        }
        scheduleSave()
    }

    fun viewport(zoom: Float, panX: Float, panY: Float) {
        // Gated like its siblings: editorLocked only covers genuine project-mutating ops (open/
        // delete/duplicate/new project/library/import or gallery commit), during which the
        // project can be swapped or torn down under it, so pan/zoom stays enabled through
        // background work (export queueing, gallery retry/discard) but not through those.
        if (mutable.value.editorLocked || !listOf(zoom, panX, panY).all { it.isFinite() }) return
        val z = zoom.coerceIn(.5f, 4f)
        val x = panX.coerceIn(-10_000f, 10_000f)
        val y = panY.coerceIn(-10_000f, 10_000f)
        mutable.update { it.copy(zoom = z, panX = x, panY = y) }
        mutable.value.project?.pages?.getOrNull(mutable.value.page)?.id?.let { pageId ->
            pageViewports = pageViewports + (pageId to PdfViewport(z, x, y))
        }
        scheduleSave()
    }

    /**
     * Ctrl+D (Phase F2 item C): duplicates the selected image right after itself, offset a few mm
     * so the copy is visibly distinct and immediately selected, or duplicates the current page
     * when no image is selected. Either way, a single undo step.
     */
    fun duplicateSelected() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return duplicatePage()
        val textId = mutable.value.selectedTextId
        val n = mutable.value.image
        val offset = 8.0
        when {
            textId != null -> {
                val source = page.texts.firstOrNull { it.id == textId } ?: return
                val copyId = newId()
                val copy =
                    PdfGeometry.constrainTextToPage(
                        source.copy(
                            id = copyId,
                            x = source.x + offset,
                            y = source.y + offset,
                            z = PdfLayers.nextZ(page),
                        ),
                        page,
                    )
                pageEdit { p -> p.copy(texts = p.texts + copy) }
                selectText(copyId)
            }
            n >= 0 -> {
                val source = page.images.getOrNull(n) ?: return
                val copy =
                    PdfGeometry.constrainToPage(
                        source.copy(id = newId(), x = source.x + offset, y = source.y + offset),
                        page,
                    )
                pageEdit { p -> p.copy(images = p.images.toMutableList().apply { add(n + 1, copy) }) }
                selectImage(n + 1)
            }
            else -> duplicatePage()
        }
    }

    /** Ctrl+=/Ctrl+- (Phase F2 item C): multiplies the current zoom by [factor], same clamping and
     * per-page viewport memory as [viewport]. */
    fun zoomBy(factor: Float) {
        val s = mutable.value
        viewport(s.zoom * factor, s.panX, s.panY)
    }

    fun moveImage(dx: Double, dy: Double) {
        val p = mutable.value.project ?: return
        val page = p.pages[mutable.value.page]
        imageEdit { image ->
            var x = image.x + dx
            var y = image.y + dy
            if (p.snap) {
                x = kotlin.math.round(x / 5) * 5
                y = kotlin.math.round(y / 5) * 5
            }
            // Manual move: margins are a guide (snap can still pull to them), not a wall.
            PdfGeometry.constrainToPage(image.copy(x = x, y = y), page)
        }
    }

    /**
     * Commits a drag-to-move as an absolute page-space position (as opposed to [moveImage]'s
     * relative delta), used once at drag release after the canvas has already resolved the 5 mm
     * grid and any active snap guide. Still a single [imageEdit]/undo step.
     */
    fun moveImageTo(x: Double, y: Double) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        imageEdit { PdfGeometry.constrainToPage(it.copy(x = x, y = y), page) }
    }

    /** As [moveImage], but moves the selected TEXT box (Phase G1b) — a no-op if no text is
     * selected. Shared by the canvas's arrow-key nudge (via [moveSelected]) and the text drag
     * gesture's per-frame update. */
    fun moveTextBy(dx: Double, dy: Double) {
        val id = mutable.value.selectedTextId ?: return
        val project = mutable.value.project ?: return
        val page = project.pages.getOrNull(mutable.value.page) ?: return
        textEdit(id) { t ->
            var x = t.x + dx
            var y = t.y + dy
            if (project.snap) {
                x = kotlin.math.round(x / 5) * 5
                y = kotlin.math.round(y / 5) * 5
            }
            PdfGeometry.constrainTextToPage(t.copy(x = x, y = y), page)
        }
    }

    /** As [moveImageTo], but commits a drag-to-move's final absolute position for the selected
     * text (Phase G1b) — a no-op if no text is selected. */
    fun moveTextTo(x: Double, y: Double) {
        val id = mutable.value.selectedTextId ?: return
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        textEdit(id) { t -> PdfGeometry.constrainTextToPage(t.copy(x = x, y = y), page) }
    }

    /** Resizes the selected text's box from [corner] (Phase G1b's corner-drag handles) — box only,
     * never the font size. A no-op if no text is selected. */
    fun resizeSelectedTextFromCorner(corner: PdfGeometry.Corner, dxMm: Double, dyMm: Double) {
        val id = mutable.value.selectedTextId ?: return
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        textEdit(id) { PdfGeometry.resizeTextFromCorner(it, page, corner, dxMm, dyMm) }
    }

    /** Dispatches an arrow-key nudge (Phase G1b) to whichever kind is currently selected — the
     * canvas's keyboard handler no longer needs to branch on selection kind itself. */
    fun moveSelected(dx: Double, dy: Double) {
        if (mutable.value.selectedTextId != null) moveTextBy(dx, dy) else moveImage(dx, dy)
    }

    fun pageEdit(transform: (PdfPage) -> PdfPage) = update { p ->
        p.copy(
            pages =
                p.pages.mapIndexed { n, page ->
                    if (n == mutable.value.page) transform(page) else page
                }
        )
    }

    fun imageEdit(transform: (PdfImage) -> PdfImage) = pageEdit { p ->
        p.copy(
            images =
                p.images.mapIndexed { n, i -> if (n == mutable.value.image) transform(i) else i }
        )
    }

    /**
     * Adds a default text box to the current page — centered, 12pt Sans Regular Black, in front
     * of everything already on the page — selects it and marks it as [PdfStudioState.newTextId]
     * (Phase G1b) so the canvas opens it straight into inline editing, matching the Insert → Text
     * entry's "selected in edit mode immediately" requirement. A no-op with a failure message on
     * an imported-PDF page (texts aren't allowed there), when the page already has 24 elements
     * (images + texts), or when [text] has an unsupported character, so it never silently produces
     * a project that fails [PdfProject.validate]. A single undo step, like every other [pageEdit].
     */
    fun addText(text: String) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (page.source != null) {
            mutable.update { it.copy(message = R.string.pdf_error) }
            return
        }
        if (!PdfMediaPlacement.hasRoomForOneMore(page.images.size + page.texts.size)) {
            mutable.update { it.copy(message = PdfFailure.PageFull.message) }
            return
        }
        if (PdfTextSupport.check(text).isFailure) {
            mutable.update { it.copy(message = PdfFailure.UnsupportedGlyph.message) }
            return
        }
        val id = newId()
        pageEdit { p ->
            val width = 100.0
            val height = 40.0
            p.copy(
                texts =
                    p.texts +
                        PdfText(
                            id = id,
                            text = text,
                            x = ((p.width - width) / 2).coerceAtLeast(0.0),
                            y = ((p.height - height) / 2).coerceAtLeast(0.0),
                            width = width.coerceAtMost(p.width),
                            height = height.coerceAtMost(p.height),
                            z = PdfLayers.nextZ(p),
                        )
            )
        }
        // Fix (round 2, item B): pageEdit/update silently no-ops on a rejected change (editorLocked,
        // mutationLocked, or a validate() failure) — only select the new text if it's actually
        // there, otherwise selectedTextId would point at a text that doesn't exist.
        val added =
            mutable.value.project?.pages?.getOrNull(mutable.value.page)?.texts?.any { it.id == id } == true
        if (added) mutable.update { it.copy(image = -1, selectedTextId = id, newTextId = id) }
    }

    /** Applies [transform] to the text with [id] on the current page, one undo step. A no-op if
     * [id] no longer exists (e.g. it was removed by a concurrent undo). */
    fun textEdit(id: String, transform: (PdfText) -> PdfText) = pageEdit { p ->
        p.copy(texts = p.texts.map { if (it.id == id) transform(it) else it })
    }

    /** Removes the text with [id] from the current page, one undo step. */
    fun removeText(id: String) = pageEdit { p -> p.copy(texts = p.texts.filterNot { it.id == id }) }

    /** One-shot acknowledgement of [PdfStudioState.newTextId] (Phase G1b): the canvas calls this
     * right after opening the freshly-added text into inline editing, so the same "just added"
     * signal never re-fires on an unrelated recomposition or a later selection of the same text. */
    fun newTextOpened() {
        mutable.update { it.copy(newTextId = null) }
    }

    fun addPage() {
        val next = mutable.value.page + 1
        update { p -> p.copy(pages = p.pages.toMutableList().apply { add(next, PdfPage()) }) }
        if (next < (mutable.value.project?.pages?.size ?: 0)) selectPage(next)
    }

    fun duplicatePage() {
        val next = mutable.value.page + 1
        update { p ->
            p.copy(
                pages =
                    p.pages.toMutableList().apply {
                        add(next, p.pages[mutable.value.page].copy(id = newId()))
                    }
            )
        }
        if (next < (mutable.value.project?.pages?.size ?: 0)) selectPage(next)
    }

    fun removePages() = update { p ->
        val ids = mutable.value.selectedPages.ifEmpty { setOf(p.pages[mutable.value.page].id) }
        p.copy(pages = p.pages.filter { it.id !in ids }.ifEmpty { listOf(PdfPage()) })
    }

    /** Enters/leaves the Pages panel's explicit selection mode, clearing any prior selection. */
    fun setPagesSelectionMode(enabled: Boolean) {
        mutable.update {
            it.copy(pagesSelectionMode = enabled, selectedPages = if (enabled) it.selectedPages else emptySet())
        }
    }

    fun selectAllPages() {
        val ids = mutable.value.project?.pages?.map { it.id }?.toSet() ?: return
        mutable.update { it.copy(selectedPages = ids) }
    }

    /** Duplicates every selected page (or just the current one), each copy inserted right after
     * its original, as a single undo step. */
    fun duplicateSelectedPages() = update { p ->
        val ids = mutable.value.selectedPages.ifEmpty { setOf(p.pages[mutable.value.page].id) }
        p.copy(
            pages =
                p.pages.flatMap { page ->
                    if (page.id in ids) listOf(page, page.copy(id = newId())) else listOf(page)
                }
        )
    }

    /** Rotates every selected page (or just the current one) 90°, swapping width/height and
     * re-fitting its images, as a single undo step — the same transform the old per-page "Rotate
     * page" action applied to one page at a time. */
    fun rotateSelectedPages() = update { p ->
        val ids = mutable.value.selectedPages.ifEmpty { setOf(p.pages[mutable.value.page].id) }
        p.copy(
            pages =
                p.pages.map { page ->
                    if (page.id !in ids) return@map page
                    val next =
                        page.copy(
                            width = page.height,
                            height = page.width,
                            rotation = (page.rotation + 90) % 360,
                        )
                    next.copy(images = page.images.map { i -> PdfGeometry.constrain(i, next) })
                }
        )
    }

    /**
     * Applies a page-level layout change (paper/orientation/margin/gap/grid) to either just the
     * current page or every page (the Layout panel's sticky "Apply to: This page / All pages"
     * row), as a single undo step either way.
     */
    fun applyLayout(allPages: Boolean, transform: (PdfPage) -> PdfPage) {
        if (!allPages) {
            pageEdit(transform)
            return
        }
        update { p -> p.copy(pages = p.pages.map { page -> if (page.source != null) page else transform(page) }) }
    }

    fun movePage(delta: Int) {
        val s = mutable.value
        val p = s.project ?: return
        val next = s.page + delta
        if (next !in p.pages.indices) return
        update { it.copy(pages = it.pages.toMutableList().apply { add(next, removeAt(s.page)) }) }
        selectPage(next)
    }

    /** Reorders the page at [from] to [to] (page strip drag-and-drop), one undo step. */
    fun reorderPage(from: Int, to: Int) {
        val s = mutable.value
        val p = s.project ?: return
        if (from !in p.pages.indices || to !in p.pages.indices || from == to) return
        val movingCurrent = s.page == from
        update { it.copy(pages = it.pages.toMutableList().apply { add(to, removeAt(from)) }) }
        if (movingCurrent) selectPage(to)
        else {
            // Keep the current page selected by identity, its index may have shifted.
            val id = p.pages[s.page].id
            mutable.value.project?.pages?.indexOfFirst { it.id == id }?.takeIf { it >= 0 }?.let {
                mutable.update { state -> state.copy(page = it) }
            }
        }
    }

    /** Rotates the selected image 90°, keeping it centered on its previous frame. */
    fun rotateSelectedImage() = imageEdit {
        val page = mutable.value.project!!.pages[mutable.value.page]
        PdfGeometry.constrain(
            it.copy(width = it.height, height = it.width, rotation = (it.rotation + 90) % 360),
            page,
        )
    }

    /** Toggles the selected image between Contain (Fit) and Cover (Crop/Fill). */
    fun toggleSelectedImageFit() = imageEdit {
        it.copy(fit = if (it.fit == PdfFit.Cover) PdfFit.Contain else PdfFit.Cover)
    }

    /**
     * Replace (contextual toolbar): re-picks the selected image's asset from [uri] while keeping
     * its frame (x/y/width/height/rotation/fit — [PdfGeometry.replaceAsset] decides whether the
     * crop focus resets). Goes through the same durable, deduping import path as any other image
     * import ([PdfProjectRepository.import]), which appends a fresh [PdfImage] to the current
     * page; this then folds that image's new asset into the *originally selected* image and drops
     * the extra one, and commits the whole thing as a single [undo] step against the
     * project as it was *before* this call (not the intermediate, already-persisted "with an
     * extra appended image" state) — so Undo cleanly restores the original photo.
     *
     * Note: [PdfProjectRepository.import] persists its intermediate "appended" result directly
     * (independent of this class's own undo/redo-driven autosave). That write is superseded
     * within one autosave cycle by the swap this function then applies through [apply]; only a
     * process death landing in the small window between those two would leave the intermediate,
     * unswapped state on disk instead of silently losing the edit.
     */
    fun replaceSelectedImageAsset(uri: Uri) = operation(lock = false) {
        val before = requireNotNull(mutable.value.project)
        val pageIndex = mutable.value.page
        val imageIndex = mutable.value.image
        val page = before.pages.getOrNull(pageIndex) ?: return@operation
        if (imageIndex !in page.images.indices) return@operation
        val oldImage = page.images[imageIndex]
        val oldAsset = before.assets.first { it.hash == oldImage.asset }
        autosave?.cancelAndJoin()
        persistCurrent()
        val imported = repository.import(before, listOf(uri), pageIndex) { _, _ -> }
        val importedPage = imported.pages[pageIndex]
        val newImage = importedPage.images.last()
        val newAsset = imported.assets.first { it.hash == newImage.asset }
        val swapped = PdfGeometry.replaceAsset(oldImage, oldAsset, newAsset)
        val nextPage =
            importedPage.copy(
                images =
                    importedPage.images.dropLast(1).mapIndexed { m, im ->
                        if (m == imageIndex) swapped else im
                    }
            )
        val next =
            imported
                .copy(pages = imported.pages.mapIndexed { n, p -> if (n == pageIndex) nextPage else p })
                .validate()
        undo.addLast(before)
        if (undo.size > 40) undo.removeFirst()
        redo.clear()
        apply(next)
    }

    /**
     * Aligns the selected image relative to the page (its margins define the left/top/right/
     * bottom). The actual geometry (and the [PdfGeometry.constrain] call that keeps an
     * image wider/taller than the margin box from landing at a negative x/y) lives in
     * [PdfGeometry.align], which has JVM coverage; this just applies it as one undo step.
     */
    fun alignSelectedImage(align: PdfGeometry.Align, relativeToMargins: Boolean = true) = pageEdit { page ->
        val n = mutable.value.image
        if (n !in page.images.indices) return@pageEdit page
        val aligned = PdfGeometry.align(page.images[n], page, align, relativeToMargins)
        page.copy(images = page.images.mapIndexed { m, img -> if (m == n) aligned else img })
    }

    /** As [alignSelectedImage], for the selected TEXT box (Phase G1b's text inspector Align
     * menu) — a no-op if no text is selected. */
    fun alignSelectedText(align: PdfGeometry.Align, relativeToMargins: Boolean = true) {
        val id = mutable.value.selectedTextId ?: return
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        textEdit(id) { PdfGeometry.alignText(it, page, align, relativeToMargins) }
    }

    /** Resets the selected image's crop focus, rotation and fit to their defaults, keeping its
     * frame (x/y/width/height) untouched. */
    fun resetSelectedImage() = imageEdit {
        it.copy(fit = PdfFit.Contain, focusX = .5, focusY = .5, rotation = 0)
    }

    /** Nudges the selected image's crop focus by [dx]/[dy] (Phase D's 2D crop-focus viewport). */
    fun moveSelectedImageFocus(dx: Double, dy: Double) = imageEdit {
        it.copy(focusX = PdfCropFocus.move(it.focusX, dx), focusY = PdfCropFocus.move(it.focusY, dy))
    }

    /** Commits the crop-focus viewport's drag as a single undo step, once at drag end/cancel —
     * the viewport itself tracks the live position locally while dragging. */
    fun setSelectedImageFocus(x: Double, y: Double) = imageEdit {
        it.copy(focusX = x.coerceIn(0.0, 1.0), focusY = y.coerceIn(0.0, 1.0))
    }

    /** The current page's selected element's id, regardless of kind (Phase G1b) — [PdfLayers]
     * gives images and texts one shared id space specifically so layer ops can look either up by
     * id alone, without branching on [PdfStudioState.selected]'s kind. */
    private fun selectedElementId(): String? {
        val s = mutable.value
        s.selectedTextId?.let { return it }
        val page = s.project?.pages?.getOrNull(s.page) ?: return null
        return page.images.getOrNull(s.image)?.id
    }

    /** Re-selects whichever element [id] now belongs to (image or text), after a layer-op
     * [pageEdit] has already run — the element itself never moves lists, only its `z` changes, so
     * this only has to look up its current kind/index, not restore geometry. */
    private fun reselect(id: String) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val imageIndex = page.images.indexOfFirst { it.id == id }
        if (imageIndex >= 0) selectImage(imageIndex) else selectText(id)
    }

    /**
     * Moves the selected element (image or text) one step forward in stacking order, toward the
     * front (Phase G1b: the Layer menu now works across both kinds — [normalizeZ] renumbers every
     * element by its new position, whichever kind each one is).
     */
    fun bringSelectedForward() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val id = selectedElementId() ?: return
        val order = PdfLayers.order(page).map(PdfLayers::elementId)
        val index = order.indexOf(id)
        if (index < 0 || index == order.lastIndex) return
        val newOrder = order.toMutableList().apply { add(index + 1, removeAt(index)) }
        pageEdit { p -> PdfLayers.normalizeZ(p, newOrder) }
        reselect(id)
    }

    /** As [bringSelectedForward], one step backward (toward the back). */
    fun sendSelectedBackward() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val id = selectedElementId() ?: return
        val order = PdfLayers.order(page).map(PdfLayers::elementId)
        val index = order.indexOf(id)
        if (index <= 0) return
        val newOrder = order.toMutableList().apply { add(index - 1, removeAt(index)) }
        pageEdit { p -> PdfLayers.normalizeZ(p, newOrder) }
        reselect(id)
    }

    /** Moves the selected element to the very front (top) of the stacking order. */
    fun bringSelectedToFront() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val id = selectedElementId() ?: return
        val order = PdfLayers.order(page).map(PdfLayers::elementId)
        val index = order.indexOf(id)
        if (index < 0 || index == order.lastIndex) return
        val newOrder = order.toMutableList().apply { add(removeAt(index)) }
        pageEdit { p -> PdfLayers.normalizeZ(p, newOrder) }
        reselect(id)
    }

    /** Moves the selected element to the very back (bottom) of the stacking order. */
    fun sendSelectedToBack() {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        val id = selectedElementId() ?: return
        val order = PdfLayers.order(page).map(PdfLayers::elementId)
        val index = order.indexOf(id)
        if (index <= 0) return
        val newOrder = order.toMutableList().apply { add(0, removeAt(index)) }
        pageEdit { p -> PdfLayers.normalizeZ(p, newOrder) }
        reselect(id)
    }

    /** Deletes the selected element — image or text (Phase G1b) — as the error-role action in the
     * contextual toolbar/Delete key/inspector, one undo step. A no-op with nothing selected. */
    fun deleteSelected() {
        val textId = mutable.value.selectedTextId
        if (textId != null) {
            removeText(textId)
            selectText(null)
            return
        }
        val n = mutable.value.image
        if (n < 0) return
        pageEdit { it.copy(images = it.images.filterIndexed { m, _ -> m != n }) }
        selectImage(-1)
    }

    /** @suppress kept as a thin alias — every existing call site (the image contextual toolbar,
     * the Adjust panel's Remove action, [PdfEditorCommand.DeleteSelection]) already only runs
     * while an image is selected, so behavior is unchanged; new code should call [deleteSelected]
     * directly, which also handles a selected text. */
    fun deleteSelectedImage() = deleteSelected()

    fun beginImport(portable: Boolean): Boolean {
        val s = mutable.value
        if (s.busy) return false
        return importPicker.begin(portable, s.project?.id, s.project?.pages?.getOrNull(s.page)?.id)
    }

    fun importResult(uris: List<Uri>, portable: Boolean = false) {
        try {
            if (importPicker.result(portable, uris.map(Uri::toString))) resumeImport()
        } catch (e: Exception) {
            importLaunchFailed(e)
        }
    }

    fun importLaunchFailed(error: Exception) {
        importPicker.request?.let(importPicker::acknowledge)
        reportFailure(error)
    }

    private fun recoverImports() {
        if (recoveringImports?.isActive == true) return
        recoveringImports =
            viewModelScope.launch {
                try {
                    val current = importPicker.request
                    val request =
                        importInbox.pending().firstOrNull { current == null || it.id == current.id }
                            ?: return@launch
                    if (importPicker.restore(request)) {
                        if (
                            !mutable.value.busy &&
                                mutable.value.project == null &&
                                !request.portable
                        )
                            request.projectId?.let { open(it) }
                        resumeImport()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(e)
                }
            }
    }

    private fun resumeImport() {
        val request = importPicker.request
        if (request == null || request.uris.isEmpty()) {
            recoverImports()
            return
        }
        if (mutable.value.busy) {
            if (receivingImport?.isActive != true)
                receivingImport =
                    viewModelScope.launch {
                        try {
                            importInbox.stage(request)
                            if (importPicker.request != request) importInbox.finish(request.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            importInbox.finish(request.id)
                            importPicker.acknowledge(request)
                            reportFailure(e)
                        }
                    }
            return
        }
        operation {
            activeImport = request.id
            try {
                importInbox.stage(request)
                autosave?.cancelAndJoin()
                persistCurrent()
                val imported =
                    repository.importPicked(request) { n, total ->
                        mutable.update { it.copy(progress = n to total) }
                    }
                // A different open project must never receive, or be overwritten by, this result.
                if (request.portable || mutable.value.project?.id == imported.id) {
                    val (project, editor) = requireNotNull(repository.loadEditor(imported.id))
                    restoreEditor(project, editor)
                }
                importInbox.finish(request.id)
                importPicker.acknowledge(request)
            } catch (e: CancellationException) {
                // The database commit is atomic and non-cancellable. If cancellation raced its
                // acknowledgement, keep the visible editor in sync with that committed result.
                if (viewModelScope.isActive)
                    withContext(NonCancellable) {
                        val receipt =
                            PdfProjectDatabase.get(getApplication()).imports().get(request.id)
                        if (
                            receipt != null &&
                                (request.portable || mutable.value.project?.id == receipt.projectId)
                        ) {
                            repository.loadEditor(receipt.projectId)?.let { (project, editor) ->
                                restoreEditor(project, editor)
                            }
                        }
                    }
                if (viewModelScope.isActive) importInbox.finish(request.id)
                throw e // recreation retains the durable inbox; explicit cancellation removes it
            } catch (e: Exception) {
                importInbox.finish(request.id)
                importPicker.acknowledge(request)
                throw e
            } finally {
                activeImport = null
            }
        }
    }

    fun prepareExport(compact: Boolean, onlySelected: Boolean) = operation(lock = false) {
        autosave?.cancelAndJoin()
        persistCurrent()
        // Read project/selectedPages AFTER the suspension above: this runs unlocked, so an edit
        // made while cancelAndJoin/persistCurrent were suspending must be reflected in the
        // snapshot that gets enqueued, not silently dropped from it.
        val s = mutable.value
        var p = requireNotNull(s.project)
        if (onlySelected) p = p.copy(pages = p.pages.filter { it.id in s.selectedPages })
        watchedExport = exportQueue.enqueue(p, compact).id
        mutable.update { it.copy(message = R.string.pdf_queue_added) }
    }

    /** Edge-triggered: report only the phase the watched export has just reached. */
    private fun followExport(jobs: List<PdfExportJob>) {
        val id = watchedExport ?: return
        // A list emitted before the new row was committed is not a removal.
        val phase = jobs.firstOrNull { it.id == id }?.phase ?: return
        if (phase == watchedPhase) return
        watchedPhase = phase
        // A previously shown result card is stale the moment the job leaves Published/Failed
        // (a retry re-queues it, for instance): never let it linger for a phase it no longer
        // describes.
        if (phase != PdfExportPhase.Published && phase != PdfExportPhase.Failed)
            mutable.update { if (it.resultJobId == id) it.copy(resultJobId = null) else it }
        val queuedNotice = setOf(R.string.pdf_queue_added, R.string.pdf_queue_ready)
        when (phase) {
            PdfExportPhase.Ready ->
                mutable.update { it.copy(message = R.string.pdf_queue_ready, sourceError = null, readyExport = id) }
            PdfExportPhase.Published -> {
                // The dedicated "PDF saved" sheet covers this outcome; the generic message banner
                // only needs to fall back when that sheet was already dismissed for this job id
                // (which cannot happen on a fresh id, but keeps this robust either way).
                val showResult = id != dismissedResult
                mutable.update {
                    it.copy(
                        message = if (showResult) null else R.string.pdf_queue_published,
                        sourceError = null,
                        readyExport = null,
                        resultJobId = id.takeIf { showResult },
                    )
                }
                ackStore.acknowledge(id)
                watchedExport = null
            }
            // Still watched: a retry from the queue can bring it back to Ready.
            PdfExportPhase.Failed -> {
                val showResult = id != dismissedResult
                mutable.update {
                    it.copy(
                        message = if (showResult) null else R.string.pdf_queue_failed,
                        sourceError = null,
                        readyExport = null,
                        resultJobId = id.takeIf { showResult },
                    )
                }
            }
            PdfExportPhase.Cancelled -> {
                mutable.update {
                    it.copy(message = it.message.takeUnless { m -> m in queuedNotice }, readyExport = null)
                }
                watchedExport = null
            }
            PdfExportPhase.Publishing -> mutable.update { it.copy(readyExport = null) }
            PdfExportPhase.Queued, PdfExportPhase.Running, PdfExportPhase.Cancelling -> Unit
        }
    }

    fun cancelExport(id: String) = operation(lock = false) { exportQueue.cancel(id) }

    fun keepExportDestination(id: String) = operation(lock = false) { exportQueue.keepDestination(id) }

    fun retryExport(id: String) = operation(lock = false) {
        exportQueue.retry(id)
        watchedExport = id
        clearResultIfShowing(id)
    }

    fun removeExport(id: String) = operation(lock = false) { exportQueue.remove(id) }

    internal fun beginPublication(id: String): PublishStart =
        publishPicker.begin(id).also {
            if (it is PublishStart.Launch) {
                watchedExport = id
                clearResultIfShowing(id)
            }
        }

    /** A result card/sheet for [id] is stale the moment the user acts on it again. */
    private fun clearResultIfShowing(id: String) {
        mutable.update { if (it.resultJobId == id) it.copy(resultJobId = null) else it }
    }

    /** Dismiss the watched export's Published/Failed result card or sheet ("Done"). */
    fun dismissResult() {
        val id = mutable.value.resultJobId ?: return
        dismissedResult = id
        ackStore.acknowledge(id)
        mutable.update { it.copy(resultJobId = null) }
    }

    /**
     * A recorded request with no live launcher behind it (SAF cancelled without delivering, or the
     * process died during the picker). Drop it so Save can be launched again, and say so.
     */
    internal fun restartPublication(id: String): PublishStart {
        if (publishing?.isActive == true) return PublishStart.AlreadyPending(id to null)
        publishPicker.discard()
        mutable.update { it.copy(message = R.string.pdf_publish_restarted) }
        return publishPicker.begin(id)
    }

    /** Publication really is in flight; tell the user instead of silently doing nothing. */
    fun publicationBusy() {
        mutable.update { it.copy(message = R.string.pdf_publish_busy) }
    }

    /** No app on the device can handle Open/Share for a published PDF; say so instead of crashing. */
    fun reportOpenFailed() = mutable.update { it.copy(message = R.string.pdf_export_open_failed) }

    /** Dismiss the current banner. Explicit user action, never a timeout. */
    fun dismissMessage() {
        mutable.update { it.copy(message = null, sourceError = null, readyExport = null) }
    }

    fun publicationResult(uri: Uri?) {
        // A duplicate null delivery must not discard a result that is already being published.
        if (uri == null && publishing?.isActive == true) return
        if (publishPicker.result(uri?.toString())) resumePublication()
    }

    fun publicationLaunchFailed(error: Exception) {
        publishPicker.result(null)
        reportFailure(error)
    }

    private fun reportFailure(error: Exception) {
        mutable.update {
            it.copy(
                message = PdfFailure.from(error).message,
                sourceError = (error as? PdfSourceFailure)?.number,
            )
        }
    }

    private fun resumePublication() {
        val request = publishPicker.request ?: return
        val uri = request.second ?: return
        if (publishing?.isActive == true) return
        // Independent of editor busy state: ActivityResult delivery must never be dropped.
        publishing =
            viewModelScope.launch {
                if (isDraftPickerId(request.first)) resumeDraftExport(request, uri)
                else resumeLegacyPublish(request, uri)
            }
    }

    /**
     * A destination was picked for a not-yet-rendered draft. This must be safe to re-run from
     * scratch after process death at *any* point: [exportDraftStore.peek] (not `take`) so the draft
     * is only cleared once [PdfExportQueue.enqueue] has durably committed, never before — clearing
     * it first and dying before the commit would silently lose an export the user already picked a
     * destination for, with nothing left to retry from. On enqueue failure, neither the draft nor
     * the picker request is cleared, so a recreated ViewModel (or [restartNewExport]) retries the
     * exact same enqueue rather than the app quietly forgetting it.
     */
    private suspend fun resumeDraftExport(request: Pair<String, String?>, uri: String) {
        val draft = exportDraftStore.peek()
        if (draft == null) {
            // Nothing left to recover from (already cleared by a completed attempt, or never
            // written) and there is no way to reconstruct it: free the picker rather than wedging
            // it forever, but this path should be unreachable now that success always clears the
            // draft itself.
            publishPicker.acknowledge(request)
            return
        }
        val snapshot = buildDraftSnapshot(draft)
        if (snapshot == null) {
            reportFailure(IllegalStateException("Project removed"))
            exportDraftStore.discard()
            publishPicker.acknowledge(request)
            return
        }
        try {
            val destination = Uri.parse(uri)
            val job = enqueueDraftIdempotently(snapshot, draft, destination)
            watchedExport = job.id
            mutable.update { it.copy(message = R.string.pdf_queue_added) }
            rememberDestination(destination)
            // Only now: the row is durably committed, so there is nothing left to lose.
            exportDraftStore.discard()
            publishPicker.acknowledge(request)
        } catch (e: CancellationException) {
            throw e // leave the draft and the saved delivery available to a recreated ViewModel
        } catch (e: Exception) {
            reportFailure(e) // surfaced as an issue card; draft and request stay recoverable
        }
    }

    /**
     * [PdfExportQueue.enqueue] with [destination] can fail with "DestinationInUse" (or, if the
     * first attempt's worker already started writing, "DestinationNotEmpty") purely because an
     * earlier call for this same draft already committed before this retry ran. In that case the
     * live job already bound to [destination] *is* this export, not a conflicting one: recognize it
     * by decoding its (real, non-blanked) manifest and comparing project ids, and treat it as
     * success instead of reporting a spurious failure. Any other cause rethrows.
     */
    private suspend fun enqueueDraftIdempotently(
        snapshot: PdfProject,
        draft: PdfExportDraft,
        destination: Uri,
    ): PdfExportJob =
        try {
            exportQueue.enqueue(snapshot, draft.compact, destination = destination)
        } catch (e: IllegalArgumentException) {
            if (e.message !in setOf("DestinationInUse", "DestinationNotEmpty")) throw e
            // Prefer an already-Published row for this destination (the earlier attempt fully
            // finished); otherwise any row of ours still live and holding it.
            val candidates = exportQueue.jobsForDestination(destination)
            val existing =
                candidates.firstOrNull { it.phase == PdfExportPhase.Published }
                    ?: candidates.firstOrNull { it.keepsSources }
            val ownProject =
                existing != null &&
                    runCatching { PdfCodec.decode(existing.manifest).id == draft.projectId }
                        .getOrDefault(false)
            existing?.takeIf { ownProject } ?: throw e
        }

    private suspend fun resumeLegacyPublish(request: Pair<String, String?>, uri: String) {
        try {
            val existing = requireNotNull(exportQueue.get(request.first))
            if (existing.destination != uri) exportQueue.publish(request.first, Uri.parse(uri))
            rememberDestination(Uri.parse(uri))
            // A matching durable row already owns the result, even after worker failure.
            publishPicker.acknowledge(request)
        } catch (e: CancellationException) {
            throw e // leave saved delivery available to a recreated ViewModel
        } catch (e: Exception) {
            reportFailure(e)
            publishPicker.acknowledge(request)
        }
    }

    /** The project a not-yet-rendered [draft] should export, honoring any edits made since. */
    private suspend fun buildDraftSnapshot(draft: PdfExportDraft): PdfProject? {
        val current = mutable.value.project
        val base = if (current?.id == draft.projectId) current else repository.load(draft.projectId)
        val project = base ?: return null
        if (draft.pagesChoice == PdfExportPagesChoice.All) return project
        val filtered = project.copy(pages = project.pages.filter { it.id in draft.pageIds })
        return if (filtered.pages.isEmpty()) project else filtered
    }

    private fun rememberDestination(uri: Uri) {
        lastDestinationStore.lastDestination = uri
        refreshLastDestinationLabel()
    }

    private fun refreshLastDestinationLabel() {
        val uri = lastDestinationStore.lastDestination ?: return
        viewModelScope.launch {
            val fallback = getApplication<Application>().getString(R.string.pdf_export_destination_default)
            mutableLastDestinationLabel.value = resolveDestinationLabel(getApplication(), uri, fallback)
        }
    }

    /** The last Custom pages text typed for [projectId] this session, or null if there is none
     * (a fresh sheet then prefills from the current page selection instead — see
     * `PdfStudioScreen`). */
    internal fun customRangeDraft(projectId: String): String? = customRangeDrafts[projectId]

    /** Called on every keystroke in the Custom pages field so navigating away and back (or
     * reopening the sheet) does not lose what was typed. */
    internal fun rememberCustomRangeDraft(projectId: String, text: String) {
        customRangeDrafts[projectId] = text
    }

    /** Off-main-thread "≈ size" figure for the export sheet's Original/Compact cards.
     * [customPageIds] is only consulted when [pagesChoice] is [PdfExportPagesChoice.Custom]. */
    internal suspend fun estimateExportBytes(
        pagesChoice: PdfExportPagesChoice,
        compact: Boolean,
        customPageIds: List<String> = emptyList(),
    ): Long =
        withContext(Dispatchers.IO) {
            val project = mutable.value.project ?: return@withContext 0L
            val snapshot =
                when (pagesChoice) {
                    PdfExportPagesChoice.All -> project
                    PdfExportPagesChoice.Current ->
                        project.copy(
                            pages = listOfNotNull(project.pages.getOrNull(mutable.value.page))
                        )
                    PdfExportPagesChoice.Selected ->
                        project.copy(pages = project.pages.filter { it.id in mutable.value.selectedPages })
                    PdfExportPagesChoice.Custom ->
                        project.copy(pages = project.pages.filter { it.id in customPageIds })
                }
            if (snapshot.pages.isEmpty()) return@withContext 0L
            val sourceBytes = snapshot.usedAssets().associateWith { repository.file(it).length() }
            PdfExportEstimator.estimate(snapshot, sourceBytes, compact = compact)
        }

    /**
     * Starts the destination-first export flow: records what to export as a draft (surviving
     * process death the same way [PdfPublishPicker] does) and asks the picker to launch
     * `CreateDocument`. The actual snapshot and [PdfExportQueue.enqueue] call happen once the
     * picker delivers a URI, in [resumePublication].
     */
    internal fun beginNewExport(
        pagesChoice: PdfExportPagesChoice,
        compact: Boolean,
        customPageIds: List<String> = emptyList(),
    ): PublishStart {
        val project = requireNotNull(mutable.value.project)
        val pageIds =
            when (pagesChoice) {
                PdfExportPagesChoice.All -> emptyList()
                PdfExportPagesChoice.Current ->
                    listOfNotNull(project.pages.getOrNull(mutable.value.page)?.id)
                PdfExportPagesChoice.Selected -> mutable.value.selectedPages.toList()
                // Defend against a stale id list (the project could have changed between the
                // sheet's last parse and this tap) by intersecting with the pages that still
                // exist; buildDraftSnapshot already falls back to the whole project if that
                // leaves nothing.
                PdfExportPagesChoice.Custom -> {
                    val known = project.pages.map { it.id }.toSet()
                    customPageIds.filter { it in known }
                }
            }
        val id = draftPickerId()
        val decision = publishPicker.begin(id)
        if (decision is PublishStart.Launch)
            exportDraftStore.put(PdfExportDraft(project.id, compact, pagesChoice, pageIds))
        return decision
    }

    /** Mirrors [restartPublication] for a not-yet-rendered draft: drop a stale request, try again. */
    internal fun restartNewExport(
        pagesChoice: PdfExportPagesChoice,
        compact: Boolean,
        customPageIds: List<String> = emptyList(),
    ): PublishStart {
        if (publishing?.isActive == true)
            return publishPicker.request?.let(PublishStart::AlreadyPending)
                ?: PublishStart.AlreadyPending(draftPickerId() to null)
        publishPicker.discard()
        exportDraftStore.discard()
        mutable.update { it.copy(message = R.string.pdf_publish_restarted) }
        return beginNewExport(pagesChoice, compact, customPageIds)
    }

    fun portable() = operation(lock = false) {
        autosave?.cancelAndJoin()
        persistCurrent()
        // Same ordering fix as prepareExport: read the project after the suspension so a
        // concurrent edit is not excluded from the enqueued snapshot.
        val p = requireNotNull(mutable.value.project)
        watchedExport = exportQueue.enqueue(p, compact = false, portable = true).id
        mutable.update { it.copy(message = R.string.pdf_queue_added) }
    }

    private companion object {
        const val WATCHED_EXPORT = "watchedExport"
    }

    fun cancel() {
        importPicker.request?.takeIf { it.id == activeImport }?.let(importPicker::acknowledge)
        task?.cancel()
    }
}
