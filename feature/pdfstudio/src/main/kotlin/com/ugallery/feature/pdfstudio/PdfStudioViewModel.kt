package com.ugallery.feature.pdfstudio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val saveState: PdfSaveState = PdfSaveState.Idle,
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
                operation {
                    activeGallery = row.id
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

    fun newProject(name: String) = operation {
        val p = PdfProject(name = name)
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
        repository.save(p.copy(id = newId()))
    }

    fun update(transform: (PdfProject) -> PdfProject) {
        val old = mutable.value.project ?: return
        if (mutable.value.editorLocked) return
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
            PdfGeometry.constrain(image.copy(x = x, y = y), page)
        }
    }

    /**
     * Commits a drag-to-move as an absolute page-space position (as opposed to [moveImage]'s
     * relative delta), used once at drag release after the canvas has already resolved the 5 mm
     * grid and any active snap guide. Still a single [imageEdit]/undo step.
     */
    fun moveImageTo(x: Double, y: Double) {
        val page = mutable.value.project?.pages?.getOrNull(mutable.value.page) ?: return
        imageEdit { PdfGeometry.constrain(it.copy(x = x, y = y), page) }
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
    fun alignSelectedImage(align: PdfGeometry.Align) = pageEdit { page ->
        val n = mutable.value.image
        if (n !in page.images.indices) return@pageEdit page
        val aligned = PdfGeometry.align(page.images[n], page, align)
        page.copy(images = page.images.mapIndexed { m, img -> if (m == n) aligned else img })
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
