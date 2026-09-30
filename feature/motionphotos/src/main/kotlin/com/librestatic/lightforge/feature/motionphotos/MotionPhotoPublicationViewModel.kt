package com.librestatic.lightforge.feature.motionphotos

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class MotionPhotoPublicationUi { Checking, None, RetryableMissing, Incomplete, Conflict, Unreadable, Published, Retired }
internal fun motionAllowsNewPublication(status: MotionPhotoPublicationUi, sourceAvailable: Boolean) =
    status == MotionPhotoPublicationUi.None && sourceAvailable
internal fun motionMayOpenSource(status: MotionPhotoPublicationUi, exporting: Boolean, sourceAvailable: Boolean) =
    sourceAvailable && (status == MotionPhotoPublicationUi.None || (status == MotionPhotoPublicationUi.Checking && exporting))
internal fun motionKeepsPublication(status: MotionPhotoPublicationUi) =
    status != MotionPhotoPublicationUi.None && status != MotionPhotoPublicationUi.Published
internal fun motionHasPublicationWork(job: Job?) = job?.isCompleted == false
internal fun motionMissingPublication(confirmed: Boolean, retiring: Boolean, previousResult: Boolean,
    attempted: Boolean): MotionPhotoPublicationUi = when {
    confirmed && retiring -> MotionPhotoPublicationUi.Retired
    confirmed || retiring || previousResult -> MotionPhotoPublicationUi.Conflict
    attempted -> MotionPhotoPublicationUi.RetryableMissing
    else -> MotionPhotoPublicationUi.None
}

internal data class MotionPhotoPublicationMarker(val attempted: Boolean = false, val confirmed: Boolean = false,
    val retiring: Boolean = false) {
    fun encode(id: String, identity: String): ArrayList<String> = arrayListOf("1", id, identityHash(identity),
        attempted.toString(), confirmed.toString(), retiring.toString())
    companion object {
        private fun identityHash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun decode(value: List<String>?, id: String, identity: String): MotionPhotoPublicationMarker {
            require(UUID.fromString(id).toString() == id)
            if (value == null) return MotionPhotoPublicationMarker()
            require(value.size == 6 && value[0] == "1" && value[1] == id && value[2] == identityHash(identity))
            require(value.drop(3).all { it == "true" || it == "false" })
            return MotionPhotoPublicationMarker(value[3].toBoolean(), value[4].toBoolean(), value[5].toBoolean()).also {
                require(!it.retiring || it.confirmed)
            }
        }
    }
}

internal data class MotionPhotoPublicationState(val status: MotionPhotoPublicationUi = MotionPhotoPublicationUi.Checking,
    val busy: Boolean = false, val exporting: Boolean = false, val receipt: MotionPhotoPublicationReceipt? = null,
    val uri: String? = null)
internal fun motionPublicationCancelled(state: MotionPhotoPublicationState) = state.copy(busy = false, exporting = false,
    status = if (state.status == MotionPhotoPublicationUi.Checking) MotionPhotoPublicationUi.Unreadable else state.status)

/** Composition-owned worker. Disposing cancels work; durable receipts survive it, never replay it. */
internal class MotionPhotoPublicationController(context: Context, private val scope: CoroutineScope,
    val publicationId: String, private val input: MotionPhotoInput, savedIdentity: String,
    markerSnapshot: List<String>?, private val onMarkerChanged: (ArrayList<String>?) -> Unit) {
    private val publication = MotionPhotoPublication(context)
    private val mutableState = MutableStateFlow(MotionPhotoPublicationState())
    val state = mutableState.asStateFlow()
    private var marker = MotionPhotoPublicationMarker()
    private var invalidBinding = false
    private var closed = false
    private var job: Job? = null
    init {
        try {
            require(savedIdentity == input.identity)
            marker = MotionPhotoPublicationMarker.decode(markerSnapshot, publicationId, input.identity)
        } catch (_: Exception) {
            invalidBinding = true
            mutableState.value = MotionPhotoPublicationState(status = MotionPhotoPublicationUi.Conflict)
        }
    }
    private fun mark(value: MotionPhotoPublicationMarker) {
        marker = value
        onMarkerChanged(value.encode(publicationId, input.identity))
    }
    private fun receiptMatches(receipt: MotionPhotoPublicationReceipt) = receipt.publicationId == publicationId &&
        receipt.sourceIdentity == input.identity && receipt.sourceUri == input.uri.toString() &&
        receipt.generationModified == input.expectedGeneration && receipt.generationAdded == input.expectedGenerationAdded

    fun checkPublication() {
        if (closed || invalidBinding || motionHasPublicationWork(job) || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        job = scope.launch { recover() }
    }
    private suspend fun recover() {
        if (closed || invalidBinding) return
        val previous = mutableState.value
        try {
            val found = publication.reconcile(publicationId)
            if (closed || invalidBinding) return
            val receipt = found.receipt
            if (receipt != null && !receiptMatches(receipt)) {
                mutableState.value = MotionPhotoPublicationState(status = MotionPhotoPublicationUi.Conflict)
                return
            }
            val status = when (found.status) {
                MotionPhotoPublicationStatus.Missing -> motionMissingPublication(marker.confirmed, marker.retiring,
                    previous.receipt != null || previous.uri != null, marker.attempted)
                MotionPhotoPublicationStatus.Incomplete -> MotionPhotoPublicationUi.Incomplete
                MotionPhotoPublicationStatus.Conflict -> MotionPhotoPublicationUi.Conflict
                MotionPhotoPublicationStatus.Published -> MotionPhotoPublicationUi.Published
            }
            if (status == MotionPhotoPublicationUi.Published) {
                checkNotNull(receipt)
                check(found.resultUri != null && receipt.destination?.uri == found.resultUri)
                check(previous.uri == null || (previous.uri == found.resultUri && previous.receipt == receipt))
                mark(marker.copy(confirmed = true))
            }
            mutableState.value = MotionPhotoPublicationState(status = status, receipt = receipt,
                uri = found.resultUri.takeIf { status == MotionPhotoPublicationUi.Published })
        } catch (_: TimeoutCancellationException) {
            if (!closed && !invalidBinding) mutableState.value = previous.copy(busy = false, exporting = false,
                status = MotionPhotoPublicationUi.Unreadable)
        } catch (cancel: CancellationException) {
            if (!closed && !invalidBinding) mutableState.value = motionPublicationCancelled(previous)
            throw cancel
        } catch (_: Exception) {
            if (!closed && !invalidBinding) mutableState.value = previous.copy(busy = false, exporting = false,
                status = MotionPhotoPublicationUi.Unreadable)
        }
    }
    fun exportFrame(session: MotionPhotoSession, timeUs: Long) = start { session.exportFrame(publicationId, timeUs) }
    fun exportClip(session: MotionPhotoSession) = start { session.exportClip(publicationId) }
    private fun start(action: suspend () -> Unit) {
        if (closed || invalidBinding || motionHasPublicationWork(job) || mutableState.value.busy ||
            mutableState.value.status != MotionPhotoPublicationUi.None) return
        mark(marker.copy(attempted = true))
        mutableState.value = mutableState.value.copy(status = MotionPhotoPublicationUi.Checking, busy = true, exporting = true)
        job = scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Journal state decides whether a retry is possible. */ }
            finally { withContext(NonCancellable) { recover() } }
        }
    }
    suspend fun acknowledgeMissing() {
        if (closed || motionHasPublicationWork(job) || mutableState.value.busy ||
            mutableState.value.status != MotionPhotoPublicationUi.RetryableMissing) return
        mutableState.value = mutableState.value.copy(busy = true)
        recover()
        if (mutableState.value.status == MotionPhotoPublicationUi.RetryableMissing) {
            mark(MotionPhotoPublicationMarker())
            mutableState.value = MotionPhotoPublicationState(status = MotionPhotoPublicationUi.None)
        }
    }
    private fun owns(expected: MotionPhotoPublicationReceipt) = !closed && !invalidBinding && receiptMatches(expected) &&
        mutableState.value.status == MotionPhotoPublicationUi.Published && mutableState.value.receipt == expected &&
        mutableState.value.uri == expected.destination?.uri
    suspend fun loadPreview(expected: MotionPhotoPublicationReceipt): Bitmap? {
        if (!owns(expected)) return null
        try {
            val bitmap = publication.loadVerifiedPreview(expected) ?: error("Result changed")
            if (owns(expected)) return bitmap
            bitmap.recycle()
        } catch (_: TimeoutCancellationException) {
            if (owns(expected)) mutableState.value = mutableState.value.copy(status = MotionPhotoPublicationUi.Unreadable)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            if (owns(expected)) mutableState.value = mutableState.value.copy(status = MotionPhotoPublicationUi.Unreadable)
        }
        return null
    }
    suspend fun verifyForHandoff(): MotionPhotoPublicationReceipt? {
        val expected = mutableState.value.receipt ?: return null
        if (!owns(expected) || mutableState.value.busy || motionHasPublicationWork(job)) return null
        mutableState.value = mutableState.value.copy(busy = true)
        recover()
        return expected.takeIf { owns(it) }
    }
    suspend fun cancelAndWait() { job?.cancelAndJoin() }
    suspend fun closeResolved(): Boolean {
        cancelAndWait()
        if (closed) return true
        val current = mutableState.value
        if (current.busy) return false
        if (current.status == MotionPhotoPublicationUi.Retired) {
            mutableState.value = current.copy(busy = true)
            recover()
            if (mutableState.value.status != MotionPhotoPublicationUi.Retired) return false
        } else if (motionKeepsPublication(current.status)) return false
        if (current.status == MotionPhotoPublicationUi.Published) {
            mark(marker.copy(retiring = true))
            mutableState.value = current.copy(busy = true)
            try { check(publication.retire(checkNotNull(current.receipt))) }
            catch (_: TimeoutCancellationException) {
                mutableState.value = current.copy(status = MotionPhotoPublicationUi.Unreadable)
                return false
            } catch (cancel: CancellationException) {
                mutableState.value = current.copy(status = MotionPhotoPublicationUi.Unreadable)
                throw cancel
            } catch (_: Exception) {
                mutableState.value = current.copy(status = MotionPhotoPublicationUi.Unreadable)
                return false
            }
        }
        closed = true
        onMarkerChanged(null)
        return true
    }
}
