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
    private var task: Job? = null
    private var autosave: Job? = null
    internal val publishPicker = PdfPublishPicker(saved)
    val pendingPublication = publishPicker.pending
    private var publishing: Job? = null
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
        resumePublication()
        resumeImport()
        viewModelScope.launch { galleryIntake.deliveries.collect { resumeGallery() } }
        viewModelScope.launch { exportQueue.jobs.collect(::followExport) }
        viewModelScope.launch {
            runCatching { exportQueue.reconcile() }
                .onFailure {
                    mutable.update { it.copy(message = R.string.pdf_queue_recovery_error) }
                }
        }
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
        mutable.update {
            it.copy(
                page = page,
                image = p.pages[page].images.indexOfFirst { image -> image.id == session.imageId },
                selectedPages = session.selectedPages,
                canUndo = undo.isNotEmpty(),
                canRedo = redo.isNotEmpty(),
                zoom = session.zoom,
                panX = session.panX,
                panY = session.panY,
            )
        }
    }

    private fun session(): PdfEditorSession {
        val s = mutable.value
        val p = s.project
        return PdfEditorSession(
            pageId = p?.pages?.getOrNull(s.page)?.id,
            imageId = p?.pages?.getOrNull(s.page)?.images?.getOrNull(s.image)?.id,
            selectedPages = s.selectedPages,
            undo = undo.toList(),
            redo = redo.toList(),
            zoom = s.zoom,
            panX = s.panX,
            panY = s.panY,
        )
    }

    private suspend fun persistCurrent() {
        mutable.value.project?.let { repository.save(it, session()) }
    }

    private fun scheduleSave() {
        val project = mutable.value.project ?: return
        val editor = session()
        autosave?.cancel()
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
        mutable.update { it.copy(page = n, image = -1, zoom = 1f, panX = 0f, panY = 0f) }
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
        mutable.update {
            it.copy(
                zoom = zoom.coerceIn(.5f, 4f),
                panX = panX.coerceIn(-10_000f, 10_000f),
                panY = panY.coerceIn(-10_000f, 10_000f),
            )
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
        val queuedNotice = setOf(R.string.pdf_queue_added, R.string.pdf_queue_ready)
        when (phase) {
            PdfExportPhase.Ready ->
                mutable.update { it.copy(message = R.string.pdf_queue_ready, sourceError = null, readyExport = id) }
            PdfExportPhase.Published -> {
                mutable.update { it.copy(message = R.string.pdf_queue_published, sourceError = null, readyExport = null) }
                watchedExport = null
            }
            // Still watched: a retry from the queue can bring it back to Ready.
            PdfExportPhase.Failed ->
                mutable.update { it.copy(message = R.string.pdf_queue_failed, sourceError = null, readyExport = null) }
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
    }

    fun removeExport(id: String) = operation(lock = false) { exportQueue.remove(id) }

    internal fun beginPublication(id: String): PublishStart =
        publishPicker.begin(id).also { if (it is PublishStart.Launch) watchedExport = id }

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
                try {
                    val existing = requireNotNull(exportQueue.get(request.first))
                    if (existing.destination != uri)
                        exportQueue.publish(request.first, Uri.parse(uri))
                    // A matching durable row already owns the result, even after worker failure.
                    publishPicker.acknowledge(request)
                } catch (e: CancellationException) {
                    throw e // leave saved delivery available to a recreated ViewModel
                } catch (e: Exception) {
                    reportFailure(e)
                    publishPicker.acknowledge(request)
                }
            }
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
