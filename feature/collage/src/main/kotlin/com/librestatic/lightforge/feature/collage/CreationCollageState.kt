package com.librestatic.lightforge.feature.collage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One source-bound draft. A different creation receives a different ViewModel key from the caller. */
data class CreationCollageUiState(
    val layout: CreationCollageLayout? = null,
    val selectedSlot: Int = 0,
    val preview: Bitmap? = null,
    val busy: Boolean = false,
    val publishing: Boolean = false,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    val result: String? = null,
    val publicationUncertain: Boolean = false,
    val publication: CreationCollagePublicationUi = CreationCollagePublicationUi.Checking,
    val sourcesAvailable: Boolean = false,
)

class CreationCollageState(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val exporter = CreationCollageExporter(application)
    private val mutableState = MutableStateFlow(CreationCollageUiState())
    val state = mutableState.asStateFlow()
    private var identity: List<String> = emptyList()
    private var sources: List<CreationCollageSource> = emptyList()
    private var prepared: CreationCollagePrepared? = null
    private var rendered: CreationCollageRender? = null
    private var work: Job? = null
    @Volatile private var revision = 0L
    private var notifiedResult: String? = null
    private var closed = false
    private var draftId: String? = null
    private var committedResult: String? = null
    private var resultSha256: String? = null
    private var publicationReceipt: CreationCollagePublicationReceipt? = null
    private var publicationUsesJournal = false
    private var invalidSavedSnapshot = false
    @Volatile private var userCancelled = false
    private var restoredRaw: Any? = savedStateHandle.get<Any>(SnapshotKey)

    /** Called on Main. Persist only small review state, never a render job or owned cache path. */
    private fun saveDraft() {
        if (closed) return
        val id = draftId ?: return
        val current = state.value
        val layout = current.layout ?: return
        val snapshot = CreationCollageDraftSnapshot(id, identity, layout, current.selectedSlot,
            committedResult, resultSha256, current.publicationUncertain,
            committedResult != null && notifiedResult == committedResult, publicationUsesJournal)
        val encoded = snapshot.encode()
        if (CreationCollageDraftSnapshot.restore(encoded, id, identity) != null)
            savedStateHandle[SnapshotKey] = encoded
        else savedStateHandle.remove<ArrayList<String>>(SnapshotKey)
    }

    fun bind(sessionId: String, newSources: List<CreationCollageSource>, sourcesAvailable: Boolean = true) {
        if (closed) return
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(newSources.size in 2..4 && newSources.map { it.identity }.distinct().size == newSources.size)
        val next = newSources.map { it.identity }
        val same = draftId == sessionId && identity == next
        mutableState.update { it.copy(sourcesAvailable = sourcesAvailable) }
        if (same && work?.isActive == true) return
        val raw = restoredRaw
        restoredRaw = null
        val restored = CreationCollageDraftSnapshot.restore(raw, sessionId, next)
        draftId = sessionId
        identity = next
        sources = newSources.toList()
        val layout = restored?.layout ?: if (!same) CreationCollageLayout.initial(newSources.size)
            else state.value.layout ?: CreationCollageLayout.initial(newSources.size)
        if (!same) {
            notifiedResult = restored?.resultUri?.takeIf { restored.resultNotified }
            publicationReceipt = null
            committedResult = restored?.resultUri
            resultSha256 = restored?.resultSha256
            publicationUsesJournal = restored?.publicationUsesJournal == true
            invalidSavedSnapshot = raw != null && restored == null
            releasePreparedAfter(work)
            prepared = null
            rendered = null
            mutableState.value = CreationCollageUiState(layout = layout,
                selectedSlot = restored?.selectedSlot ?: 0,
                sourcesAvailable = sourcesAvailable,
                publicationUncertain = restored?.publicationUncertain == true)
        }
        recoverPublication()
    }

    val keepsRecoveryOnBack: Boolean get() = collageKeepsRecovery(state.value.publication)
    private fun canEdit(): Boolean = !closed && !state.value.busy &&
        collageAllowsNewRender(state.value.publication, state.value.sourcesAvailable)

    /** Consult the durable receipt before any original is read, and before enabling output actions. */
    private fun recoverPublication(acknowledgeMissing: Boolean = false) {
        if (closed) return
        val id = draftId ?: return
        val old = work
        old?.cancel()
        val currentRevision = ++revision
        mutableState.update { it.copy(busy = true, publishing = false, result = null,
            publication = CreationCollagePublicationUi.Checking, failed = false) }
        work = viewModelScope.launch {
            old?.join()
            try {
                val recovery = exporter.reconcile(id)
                if (revision != currentRevision) return@launch
                val receipt = recovery.receipt
                if (receipt != null && (receipt.sessionId != id || receipt.sourceIdentities != identity)) {
                    mutableState.update { it.copy(publication = CreationCollagePublicationUi.Conflict, failed = true) }
                    return@launch
                }
                if (receipt != null) {
                    check(receipt.layout.order.size == sources.size)
                    publicationUsesJournal = true
                    invalidSavedSnapshot = false
                    mutableState.update { it.copy(layout = receipt.layout,
                        selectedSlot = it.selectedSlot.coerceIn(receipt.layout.order.indices)) }
                    if (recovery.status != CreationCollagePublicationStatus.Published) {
                        mutableState.update { it.copy(publicationUncertain = committedResult == null) }
                        saveDraft()
                    }
                }
                when (recovery.status) {
                    CreationCollagePublicationStatus.Published -> {
                        checkNotNull(receipt)
                        check(receipt.layout.order.size == sources.size)
                        if (committedResult != null && (committedResult != recovery.resultUri || resultSha256 != receipt.renderSha256)) {
                            mutableState.update { it.copy(publication = CreationCollagePublicationUi.Conflict, failed = true) }
                            return@launch
                        }
                        publicationReceipt = receipt
                        committedResult = checkNotNull(recovery.resultUri)
                        resultSha256 = receipt.renderSha256
                        publicationUsesJournal = true
                        invalidSavedSnapshot = false
                        mutableState.update { it.copy(layout = receipt.layout,
                            selectedSlot = it.selectedSlot.coerceIn(receipt.layout.order.indices),
                            publicationUncertain = false) }
                        saveDraft()
                        work = null
                        restoreResultPreview()
                    }
                    CreationCollagePublicationStatus.Incomplete -> {
                        mutableState.update { it.copy(publication = CreationCollagePublicationUi.Incomplete, failed = true) }
                    }
                    CreationCollagePublicationStatus.Conflict -> {
                        mutableState.update { it.copy(publication = CreationCollagePublicationUi.Conflict, failed = true) }
                    }
                    CreationCollagePublicationStatus.Missing -> when {
                        committedResult != null -> {
                            publicationReceipt = null
                            // Legacy confirmed SavedState remains verifiable without originals or a new receipt.
                            work = null
                            restoreResultPreview()
                        }
                        invalidSavedSnapshot -> mutableState.update {
                            it.copy(publication = CreationCollagePublicationUi.Conflict, failed = true)
                        }
                        state.value.publicationUncertain && (!publicationUsesJournal || !acknowledgeMissing) ->
                            mutableState.update { it.copy(publication = if (publicationUsesJournal)
                                CreationCollagePublicationUi.RetryableMissing else CreationCollagePublicationUi.Incomplete,
                                failed = true) }
                        else -> {
                            mutableState.update { it.copy(publication = CreationCollagePublicationUi.None,
                                publicationUncertain = false, failed = !it.sourcesAvailable) }
                            saveDraft()
                            if (state.value.sourcesAvailable) {
                                work = null
                                render(checkNotNull(state.value.layout), afterRecovery = true)
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (revision == currentRevision) mutableState.update {
                    it.copy(publication = CreationCollagePublicationUi.Unreadable, failed = true, result = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == currentRevision) mutableState.update {
                    it.copy(publication = CreationCollagePublicationUi.Unreadable, failed = true, result = null)
                }
            } finally {
                if (revision == currentRevision) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun select(slot: Int) {
        if (canEdit() && slot in (state.value.layout?.order?.indices ?: IntRange.EMPTY)) {
            mutableState.update { it.copy(selectedSlot = slot) }
            saveDraft()
        }
    }

    fun chooseTemplate(template: CreationCollageTemplate) {
        val current = state.value.layout ?: return
        if (canEdit() && template.sourceCount == current.order.size)
            render(current.copy(template = template))
    }

    fun move(delta: Int) {
        val current = state.value
        val layout = current.layout ?: return
        val destination = current.selectedSlot + delta
        if (canEdit() && destination in layout.order.indices) {
            mutableState.update { it.copy(selectedSlot = destination) }
            render(layout.move(current.selectedSlot, delta))
        }
    }

    fun crop(value: CreationCollageCrop) {
        val current = state.value
        val layout = current.layout ?: return
        if (canEdit()) render(layout.crop(layout.order[current.selectedSlot], value))
    }

    fun retry() {
        if (state.value.busy) return
        recoverPublication()
    }

    fun acknowledgeInterruptedPublication() {
        if (state.value.busy) return
        // Only a NEW journal-backed attempt confirmed absent twice may explicitly return to editing.
        recoverPublication(acknowledgeMissing = state.value.publication == CreationCollagePublicationUi.RetryableMissing)
    }

    private fun render(layout: CreationCollageLayout, afterRecovery: Boolean = false) {
        if (closed || !collageAllowsNewRender(state.value.publication, state.value.sourcesAvailable) ||
            (!afterRecovery && state.value.busy)) return
        val old = work
        old?.cancel()
        val currentRevision = ++revision
        val currentSources = sources
        committedResult = null
        resultSha256 = null
        notifiedResult = null
        mutableState.update { it.copy(layout = layout, preview = null, busy = true,
            publishing = false, failed = false, cancelled = afterRecovery && it.cancelled, result = null) }
        saveDraft()
        work = viewModelScope.launch {
            old?.join()
            try {
                if (prepared == null) prepared = exporter.prepare(currentSources)
                val source = prepared ?: error("Sources unavailable")
                val next = exporter.render(source, layout)
                val bitmap = withContext(Dispatchers.IO) { CreationCollageExporter.decode(next.file, 768) }
                ensureActive()
                if (revision == currentRevision) {
                    rendered?.file?.delete()
                    rendered = next
                    mutableState.update { it.copy(preview = bitmap) }
                } else next.file.delete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == currentRevision) mutableState.update { it.copy(failed = true) }
            } finally {
                if (revision == currentRevision) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun export() {
        if (!canEdit() || state.value.failed || state.value.publicationUncertain || committedResult != null) return
        val image = rendered ?: return
        if (image.layout != state.value.layout || state.value.preview == null) return
        val currentRevision = ++revision
        userCancelled = false
        mutableState.update { it.copy(busy = true, publishing = true, failed = false, cancelled = false,
            publicationUncertain = true) }
        publicationUsesJournal = true
        mutableState.update { it.copy(publication = CreationCollagePublicationUi.Checking) }
        saveDraft() // Persist that this attempt uses the intent-before-insert publication protocol.
        work = viewModelScope.launch {
            try {
                exporter.publish(checkNotNull(draftId), image)
            } catch (cancelled: CancellationException) {
                if (revision == currentRevision) mutableState.update { it.copy(cancelled = true) }
                throw cancelled
            } catch (_: Exception) {
                if (revision == currentRevision) mutableState.update { it.copy(failed = true) }
            } finally {
                if (revision == currentRevision) {
                    work = null
                    // A user Cancel is a decision, not an interruption: drop what the attempt left behind so the
                    // draft can be exported again. Only an unfinished publication is ever removed.
                    if (userCancelled && withContext(NonCancellable) { discardUnfinishedAttempt() }) {
                        mutableState.update { it.copy(publicationUncertain = false) }
                        saveDraft()
                    }
                    recoverPublication() // Read the receipt; never repeat the publication operation.
                }
            }
        }
    }

    /** Every Open/Share handoff rechecks the exact current output, including chooser returns. */
    suspend fun verifyResultForHandoff(): Uri? {
        if (closed || state.value.busy) return null
        val expectedUri = state.value.result ?: return null
        val expectedHash = resultSha256 ?: return null
        recoverPublication()
        while (true) {
            val pending = work ?: break
            pending.join()
            if (work === pending) break // Recovery can replace its own job with bounded PNG verification.
        }
        if (closed || state.value.busy || state.value.publication != CreationCollagePublicationUi.Published ||
            state.value.result != expectedUri || resultSha256 != expectedHash) return null
        return Uri.parse(expectedUri)
    }

    /** Kept with the retained draft, not a possibly already-saved Activity state bundle. */
    internal fun claimResultNotification(uri: String): Boolean {
        if (closed) return false
        if (notifiedResult == uri) return false
        notifiedResult = uri
        saveDraft()
        return true
    }

    private suspend fun discardUnfinishedAttempt(): Boolean {
        val id = draftId ?: return false
        return try { exporter.discardIncompletePublication(id) } catch (_: Exception) { false }
    }

    /** Recovery-card action: drops an unfinished publication (pending file and journal) so a new export can start. */
    fun discardInterruptedPublication() {
        val current = state.value
        if (closed || current.busy || current.publication != CreationCollagePublicationUi.Incomplete) return
        val old = work
        old?.cancel()
        val currentRevision = ++revision
        mutableState.update { it.copy(busy = true, failed = false) }
        work = viewModelScope.launch {
            old?.join()
            val discarded = discardUnfinishedAttempt()
            if (revision != currentRevision) return@launch
            if (discarded) {
                mutableState.update { it.copy(publicationUncertain = false, cancelled = true) }
                saveDraft()
                work = null
                recoverPublication()
            } else mutableState.update { it.copy(busy = false, failed = true) }
        }
    }

    suspend fun cancelAndWait() {
        val exporting = state.value.publishing
        if (exporting) userCancelled = true
        work?.cancelAndJoin()
        if (exporting) while (true) {
            // The cancelled export cleans up and re-reads its receipt (and may re-render) before it settles.
            val pending = work ?: break
            pending.join()
            if (work === pending) break
        }
    }

    /** Explicit close acknowledges only the exact verified receipt, never the published PNG. */
    suspend fun closeResolvedDraft(): Boolean {
        cancelAndWait()
        if (keepsRecoveryOnBack) return false
        val receipt = publicationReceipt
        if (receipt != null) {
            try {
                check(state.value.publication == CreationCollagePublicationUi.Published && state.value.result != null)
                mutableState.update { it.copy(busy = true) }
                check(exporter.retirePublication(receipt)) { "Publication receipt was not retired" }
                publicationReceipt = null
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(publication = CreationCollagePublicationUi.Unreadable,
                    failed = true, result = null, busy = false) }
                return false
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(publication = CreationCollagePublicationUi.Unreadable,
                    failed = true, result = null, busy = false) }
                throw cancelled
            }
            catch (_: Exception) {
                mutableState.update { it.copy(publication = CreationCollagePublicationUi.Unreadable,
                    failed = true, result = null, busy = false) }
                return false
            }
        }
        discardClosedDraft()
        return true
    }

    /** Explicit confirmed Back only, after cancellation drains. Never delete a published MediaStore row. */
    fun discardClosedDraft() {
        if (closed) return
        check(work?.isActive != true) { "Cancel and await work before closing the draft" }
        check(!keepsRecoveryOnBack) { "Unresolved publication must retain its draft identity" }
        closed = true
        detach() // Invalidates any queued callback and closes only owned render snapshots.
        savedStateHandle.remove<ArrayList<String>>(SnapshotKey)
        restoredRaw = null
        draftId = null
        identity = emptyList()
        sources = emptyList()
        committedResult = null
        resultSha256 = null
        publicationReceipt = null
        notifiedResult = null
        mutableState.value = CreationCollageUiState()
    }

    private fun restoreResultPreview() {
        val expectedUri = committedResult ?: return
        val expectedHash = resultSha256 ?: return
        val old = work
        old?.cancel()
        val currentRevision = ++revision
        // Hide actions until this exact published image has been revalidated. Never re-export it.
        mutableState.update { it.copy(busy = true, failed = false, result = null,
            publication = CreationCollagePublicationUi.Checking) }
        work = viewModelScope.launch {
            old?.join()
            try {
                val bitmap = withTimeout(20_000) { withContext(Dispatchers.IO) {
                    val uri = Uri.parse(expectedUri)
                    val resolver = getApplication<Application>().contentResolver
                    fun metadata(): List<String> = resolver.query(uri, arrayOf(
                        android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        android.provider.MediaStore.MediaColumns.IS_PENDING,
                        android.provider.MediaStore.MediaColumns.IS_TRASHED,
                        android.provider.MediaStore.MediaColumns.GENERATION_ADDED,
                        android.provider.MediaStore.MediaColumns.GENERATION_MODIFIED,
                    ), null, null, null)?.use { cursor ->
                        check(cursor.moveToFirst()) { "Published collage unavailable" }
                        List(7) { cursor.getString(it).orEmpty() }.also {
                            check(!cursor.moveToNext() && it[0] == getApplication<Application>().packageName &&
                                it[1] == "Pictures/Lightforge/Collage/" && it[2] == "image/png" && it[3] == "0" && it[4] == "0")
                        }
                    } ?: error("Published collage unavailable")
                    val before = metadata()
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(65536)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size().toLong() + count <= 32L * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Published collage unreadable")
                    check(CreationCollageExporter.digest(bytes.inputStream()) == expectedHash) { "Published collage changed" }
                    check(metadata() == before) { "Published collage changed while reading" }
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(
                        java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        check(info.size.width == CreationCollageExporter.OutputSize && info.size.height == CreationCollageExporter.OutputSize)
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSize(768, 768)
                    }
                } }
                if (revision == currentRevision) {
                    mutableState.update { it.copy(preview = bitmap, result = expectedUri,
                        publication = CreationCollagePublicationUi.Published, publicationUncertain = false) }
                    saveDraft()
                }
            } catch (_: TimeoutCancellationException) {
                if (revision == currentRevision) mutableState.update { it.copy(failed = true, result = null, publication = CreationCollagePublicationUi.Unreadable) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (revision == currentRevision) mutableState.update { it.copy(failed = true, result = null, publication = CreationCollagePublicationUi.Unreadable) } }
            finally { if (revision == currentRevision) mutableState.update { it.copy(busy = false) } }
        }
    }

    /** Drop owned snapshots and bitmap references. Rebind decodes a saved result from its public URI. */
    fun detach() {
        val old = work
        old?.cancel()
        revision++
        releasePreparedAfter(old)
        prepared = null
        rendered = null
        mutableState.update { it.copy(preview = null, busy = false, publishing = false) }
    }

    private fun releasePreparedAfter(job: Job?) {
        val oldPrepared = prepared ?: return
        if (job == null || job.isCompleted) oldPrepared.close()
        else job.invokeOnCompletion { oldPrepared.close() }
    }

    override fun onCleared() { detach(); super.onCleared() }

    private companion object { const val SnapshotKey = "creation_collage_draft_v1" }
}
