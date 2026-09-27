package com.ugallery.feature.pdfstudio

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
        val project = mutable.value.project ?: return
        val pageId = project.pages.getOrNull(pageIndex)?.id ?: return
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
        if (!PdfMediaPlacement.hasRoomForOneMore(page.images.size)) {
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
            it.copy(
                project = p,
                page = it.page.coerceIn(0, p.pages.lastIndex),
                image =
                    it.image.coerceAtMost(
                        p.pages[it.page.coerceIn(0, p.pages.lastIndex)].images.lastIndex
                    ),
                canUndo = undo.isNotEmpty(),
                canRedo = redo.isNotEmpty(),
                selectedPages = it.selectedPages.intersect(p.pages.map { page -> page.id }.toSet()),
                message = null,
            )
        }
        scheduleSave()
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
        mutable.update { it.copy(image = n) }
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
        val n = mutable.value.image
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page)
        if (n >= 0 && page != null) {
            val source = page.images.getOrNull(n) ?: return
            val offset = 8.0
            val copy =
                PdfGeometry.constrainToPage(
                    source.copy(id = newId(), x = source.x + offset, y = source.y + offset),
                    page,
                )
            pageEdit { p -> p.copy(images = p.images.toMutableList().apply { add(n + 1, copy) }) }
            selectImage(n + 1)
        } else {
            duplicatePage()
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

    /** Moves the selected image one step forward in stacking order (toward the front). */
    fun bringSelectedImageForward() {
        val n = mutable.value.image
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        if (n < 0 || n >= page.images.lastIndex) return
        pageEdit { p -> p.copy(images = p.images.toMutableList().apply { add(n + 1, removeAt(n)) }) }
        selectImage(n + 1)
    }

    /** Moves the selected image one step backward in stacking order (toward the back). */
    fun sendSelectedImageBackward() {
        val n = mutable.value.image
        if (n <= 0) return
        pageEdit { page ->
            page.copy(images = page.images.toMutableList().apply { add(n - 1, removeAt(n)) })
        }
        selectImage(n - 1)
    }

    /** Moves the selected image to the very front (top) of the stacking order. */
    fun bringSelectedImageToFront() {
        val n = mutable.value.image
        if (n < 0) return
        pageEdit { it.copy(images = it.images.toMutableList().apply { add(removeAt(n)) }) }
        selectImage((mutable.value.project?.pages?.getOrNull(mutable.value.page)?.images?.lastIndex) ?: -1)
    }

    /** Moves the selected image to the very back (bottom) of the stacking order. */
    fun sendSelectedImageToBack() {
        val n = mutable.value.image
        if (n < 0) return
        pageEdit { it.copy(images = it.images.toMutableList().apply { add(0, removeAt(n)) }) }
        selectImage(0)
    }

    /** Deletes the selected image (error-role action in the contextual toolbar), one undo step. */
    fun deleteSelectedImage() {
        val n = mutable.value.image
        if (n < 0) return
        pageEdit { it.copy(images = it.images.filterIndexed { m, _ -> m != n }) }
        selectImage(-1)
    }

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

    /** Off-main-thread "≈ size" figure for the export sheet's Original/Compact cards. */
    internal suspend fun estimateExportBytes(pagesChoice: PdfExportPagesChoice, compact: Boolean): Long =
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
    internal fun beginNewExport(pagesChoice: PdfExportPagesChoice, compact: Boolean): PublishStart {
        val project = requireNotNull(mutable.value.project)
        val pageIds =
            when (pagesChoice) {
                PdfExportPagesChoice.All -> emptyList()
                PdfExportPagesChoice.Current ->
                    listOfNotNull(project.pages.getOrNull(mutable.value.page)?.id)
                PdfExportPagesChoice.Selected -> mutable.value.selectedPages.toList()
            }
        val id = draftPickerId()
        val decision = publishPicker.begin(id)
        if (decision is PublishStart.Launch)
            exportDraftStore.put(PdfExportDraft(project.id, compact, pagesChoice, pageIds))
        return decision
    }

    /** Mirrors [restartPublication] for a not-yet-rendered draft: drop a stale request, try again. */
    internal fun restartNewExport(pagesChoice: PdfExportPagesChoice, compact: Boolean): PublishStart {
        if (publishing?.isActive == true)
            return publishPicker.request?.let(PublishStart::AlreadyPending)
                ?: PublishStart.AlreadyPending(draftPickerId() to null)
        publishPicker.discard()
        exportDraftStore.discard()
        mutable.update { it.copy(message = R.string.pdf_publish_restarted) }
        return beginNewExport(pagesChoice, compact)
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
