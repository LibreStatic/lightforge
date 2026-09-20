package com.ugallery.feature.collage

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CreationGifPublicationUi { Checking, None, RetryableMissing, Incomplete, Conflict, Unreadable, Unverified, Published, Retired }
internal fun gifAllowsNewExport(status: CreationGifPublicationUi, sourcesAvailable: Boolean) =
    status == CreationGifPublicationUi.None && sourcesAvailable
internal fun gifKeepsRecovery(status: CreationGifPublicationUi) =
    status != CreationGifPublicationUi.None && status != CreationGifPublicationUi.Published

internal fun gifMissingPublicationState(confirmed: Boolean, retirementRequested: Boolean,
    previousResult: Boolean, attempted: Boolean): CreationGifPublicationUi = when {
    confirmed && retirementRequested -> CreationGifPublicationUi.Retired
    confirmed || previousResult || retirementRequested -> CreationGifPublicationUi.Conflict
    attempted -> CreationGifPublicationUi.RetryableMissing
    else -> CreationGifPublicationUi.None
}

/** Small Bundle marker: identity order and both generations are already part of each source identity. */
internal fun gifPublicationBinding(sessionId: String, identities: List<String>): ArrayList<String> {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    identities.forEach { identity ->
        val bytes = identity.toByteArray(Charsets.UTF_8)
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }
    return arrayListOf(sessionId, hash.digest().joinToString("") { "%02x".format(it) })
}

data class CreationGifExportState(val running: Boolean = false, val progress: Int = 0,
    val uri: String? = null, val failed: Boolean = false, val cancelled: Boolean = false,
    val publication: CreationGifPublicationUi = CreationGifPublicationUi.Checking,
    val receipt: CreationGifPublicationReceipt? = null)

// A cancelled encoder still owns its non-cancellable publication reconciliation until completion.
internal fun gifHasOwnedWork(job: Job?): Boolean = job?.isCompleted == false

/**
 * A verification timeout means the check did not finish, not that the published file is broken.
 * When a published output is known, keep it visible as present-but-unverified instead of failed.
 */
/** Terminal guarantee for every recovery exit, including the early returns that emit no state of their own. */
internal fun gifRecoveryResolved(state: CreationGifExportState): CreationGifExportState =
    if (state.running) state.copy(running = false) else state

internal fun gifRecoveryUnverified(state: CreationGifExportState, publishedUri: String?): CreationGifExportState {
    val uri = state.uri ?: publishedUri
    return if (uri == null) state.copy(running = false, publication = CreationGifPublicationUi.Unreadable, failed = true)
    else state.copy(running = false, uri = uri, failed = false, progress = 100,
        publication = CreationGifPublicationUi.Unverified)
}

internal fun gifRecoveryCancelled(state: CreationGifExportState): CreationGifExportState = state.copy(
    running = false,
    publication = if (state.publication == CreationGifPublicationUi.Checking) CreationGifPublicationUi.Unreadable else state.publication,
)

/** One session, one durable publication. Recovery never invokes the encoder. */
class CreationGifViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(CreationGifExportState())
    val state = mutableState.asStateFlow()
    private val exporter = CreationGifExporter(application)
    private var session: String? = null
    private var identities = emptyList<String>()
    private var job: Job? = null
    private var closed = false
    private var invalidBinding = false
    /** Last URI the exporter reported as committed; recovery, not this field, still owns publication truth. */
    private var publishedUri: String? = null
    private val markerKey = "gif-publication-attempt-v1"
    private val confirmedKey = "gif-publication-confirmed-v1"
    private val retiringKey = "gif-publication-retiring-v1"
    private fun mark(key: String) { savedState[key] = gifPublicationBinding(checkNotNull(session), identities) }
    private fun markerMatches(): Boolean = try {
        listOf(markerKey, confirmedKey, retiringKey).all { key ->
            savedState.get<ArrayList<String>>(key)?.let { it == gifPublicationBinding(checkNotNull(session), identities) } ?: true
        }
    } catch (_: Exception) { false }
    private fun hasAttempt() = savedState.contains(markerKey)

    fun bind(sessionId: String, sources: List<CreationGifSource>) {
        val incoming = sources.map { it.identity }
        if (session != null) {
            if (session != sessionId || identities != incoming) {
                invalidBinding = true
                publishedUri = null
                job?.cancel()
                mutableState.value = CreationGifExportState(publication = CreationGifPublicationUi.Conflict)
            } else if (!closed && !gifHasOwnedWork(job) && mutableState.value.publication != CreationGifPublicationUi.None) {
                checkPublication()
            }
            return
        }
        session = sessionId; identities = incoming
        if (!markerMatches()) {
            invalidBinding = true
            mutableState.value = CreationGifExportState(publication = CreationGifPublicationUi.Conflict)
        } else checkPublication()
    }

    /** Every exit path clears `running`; disabled controls with a live progress bar are never a terminal state. */
    private suspend fun recover() {
        try {
            recoverUnlocked()
        } finally {
            mutableState.value = gifRecoveryResolved(mutableState.value)
        }
    }

    private suspend fun recoverUnlocked() {
        if (invalidBinding) return
        val previous = mutableState.value
        try {
            val found = exporter.reconcile(checkNotNull(session))
            if (invalidBinding) return
            val receipt = found.receipt
            if (receipt != null && (receipt.sessionId != session || receipt.sourceIdentities != identities)) {
                mutableState.value = CreationGifExportState(publication = CreationGifPublicationUi.Conflict)
                return
            }
            val status = when (found.status) {
                CreationGifPublicationStatus.Missing -> gifMissingPublicationState(
                    savedState.contains(confirmedKey), savedState.contains(retiringKey),
                    previous.uri != null || previous.receipt != null, hasAttempt())
                CreationGifPublicationStatus.Incomplete -> CreationGifPublicationUi.Incomplete
                CreationGifPublicationStatus.Conflict -> CreationGifPublicationUi.Conflict
                CreationGifPublicationStatus.Published -> CreationGifPublicationUi.Published
            }
            if (status == CreationGifPublicationUi.Published) {
                checkNotNull(receipt)
                check(found.resultUri != null && receipt.destination?.uri == found.resultUri)
                // An unverified carry-over has a URI but no receipt yet; only a differing one is a changed output.
                check(previous.uri == null || (previous.uri == found.resultUri &&
                    (previous.receipt?.renderSha256 ?: receipt.renderSha256) == receipt.renderSha256)) { "Published output changed" }
            }
            if (status == CreationGifPublicationUi.Published) mark(confirmedKey)
            mutableState.value = CreationGifExportState(publication = status, receipt = receipt,
                uri = found.resultUri.takeIf { status == CreationGifPublicationUi.Published },
                progress = if (status == CreationGifPublicationUi.Published) 100 else 0)
        } catch (timeout: TimeoutCancellationException) {
            if (invalidBinding) return
            mutableState.value = gifRecoveryUnverified(previous, publishedUri)
        } catch (cancel: CancellationException) {
            if (!invalidBinding) mutableState.value = gifRecoveryCancelled(previous)
            throw cancel
        } catch (_: Exception) {
            if (invalidBinding) return
            mutableState.value = previous.copy(running = false, publication = CreationGifPublicationUi.Unreadable, failed = true)
        }
    }

    fun checkPublication() {
        if (closed || invalidBinding || gifHasOwnedWork(job) || session == null || !markerMatches()) return
        mutableState.value = mutableState.value.copy(running = true)
        job = viewModelScope.launch { recover() }
    }

    fun start(request: CreationGifRequest, order: List<Int>) {
        if (closed || gifHasOwnedWork(job) || mutableState.value.publication != CreationGifPublicationUi.None) return
        check(request.sources.map { it.identity } == identities)
        publishedUri = null
        mark(markerKey) // Saved UI distinguishes a failed pre-intent attempt; the journal owns publication truth.
        mutableState.value = CreationGifExportState(running = true, publication = CreationGifPublicationUi.Checking)
        job = viewModelScope.launch {
            try {
                exporter.export(checkNotNull(session), request, order,
                    onProgress = { percent -> mutableState.value = mutableState.value.copy(progress = percent) },
                    onPublished = { uri -> publishedUri = uri.toString() })
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) {
                // The durable receipt, not an exception category, decides whether retry is possible.
            } finally {
                // Resolve even when Back cancels encoding. Never publish again from recovery.
                withContext(NonCancellable) { recover() }
            }
        }
    }

    suspend fun acknowledgeMissing() {
        if (closed || mutableState.value.running || gifHasOwnedWork(job) || mutableState.value.publication != CreationGifPublicationUi.RetryableMissing) return
        mutableState.value = mutableState.value.copy(running = true)
        recover()
        if (mutableState.value.publication == CreationGifPublicationUi.RetryableMissing) {
            savedState.remove<ArrayList<String>>(markerKey)
            mutableState.value = CreationGifExportState(publication = CreationGifPublicationUi.None)
        }
    }

    suspend fun loadVerifiedResultPreview(expected: CreationGifPublicationReceipt): android.graphics.Bitmap? {
        fun stillOwnsResult() = !closed && !invalidBinding && session == expected.sessionId &&
            identities == expected.sourceIdentities && mutableState.value.publication == CreationGifPublicationUi.Published &&
            mutableState.value.receipt == expected && mutableState.value.uri == expected.destination?.uri
        if (!stillOwnsResult()) return null
        try {
            val bitmap = exporter.loadVerifiedResultPreview(expected) ?: error("Published GIF changed")
            if (stillOwnsResult()) return bitmap
            bitmap.recycle()
        } catch (timeout: TimeoutCancellationException) {
            if (stillOwnsResult()) mutableState.value = mutableState.value.copy(
                publication = CreationGifPublicationUi.Unreadable, failed = true)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            if (stillOwnsResult()) mutableState.value = mutableState.value.copy(
                publication = CreationGifPublicationUi.Unreadable, failed = true)
        }
        return null
    }

    suspend fun verifyResultForHandoff(): Uri? {
        if (closed || mutableState.value.running || gifHasOwnedWork(job) || mutableState.value.publication != CreationGifPublicationUi.Published) return null
        val expected = mutableState.value
        mutableState.value = expected.copy(running = true)
        recover()
        val current = mutableState.value
        return current.uri?.takeIf { current.publication == CreationGifPublicationUi.Published &&
            it == expected.uri && current.receipt == expected.receipt }?.let(Uri::parse)
    }

    suspend fun cancelAndWait() { job?.cancelAndJoin() }
    suspend fun closeResolvedDraft(): Boolean {
        cancelAndWait()
        if (closed) return true
        val current = mutableState.value
        if (current.running) return false
        if (current.publication == CreationGifPublicationUi.Retired) {
            mutableState.value = current.copy(running = true)
            recover() // Reconfirm durable absence; never treat a failed fsync as successful closure.
            if (mutableState.value.publication != CreationGifPublicationUi.Retired) return false
        } else if (gifKeepsRecovery(current.publication)) return false
        if (current.publication == CreationGifPublicationUi.Published) {
            mark(retiringKey)
            mutableState.value = current.copy(running = true)
            try {
                check(exporter.retirePublication(checkNotNull(current.receipt)))
            } catch (timeout: TimeoutCancellationException) {
                mutableState.value = current.copy(publication = CreationGifPublicationUi.Unreadable, failed = true)
                return false
            } catch (cancel: CancellationException) {
                mutableState.value = current.copy(running = false, publication = CreationGifPublicationUi.Unreadable, failed = true)
                throw cancel
            } catch (_: Exception) {
                mutableState.value = current.copy(publication = CreationGifPublicationUi.Unreadable, failed = true)
                return false
            }
        }
        closed = true
        listOf(markerKey, confirmedKey, retiringKey).forEach { savedState.remove<ArrayList<String>>(it) }
        return true
    }
}
