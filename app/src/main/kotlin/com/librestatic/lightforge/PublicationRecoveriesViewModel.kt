package com.librestatic.lightforge

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.librestatic.lightforge.feature.collage.*
import com.librestatic.lightforge.feature.motionphotos.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PublicationRecoveryKind { Collage, Gif, MotionFrame, MotionClip, MotionUnknown }
data class PublicationRecoveryTarget(val id: String, val kind: PublicationRecoveryKind, val uri: Uri, val mimeType: String)
internal enum class PublicationRecoveryAction { Complete, RemovePending, Forget }
internal enum class PublicationRecoveryMessage { LoadError, Changed, Failed, Completed }
internal data class PublicationRecoveryPermissions(val unreadable: Boolean, val busy: Boolean, val backingFileMissing: Boolean,
    val canComplete: Boolean, val canRemovePending: Boolean, val canForget: Boolean, val published: Boolean)
internal fun publicationRecoveryAllows(action: PublicationRecoveryAction, p: PublicationRecoveryPermissions) =
    !p.unreadable && !p.busy && when (action) {
        PublicationRecoveryAction.Complete -> p.canComplete && !p.backingFileMissing
        PublicationRecoveryAction.RemovePending -> p.canRemovePending
        PublicationRecoveryAction.Forget -> p.canForget
    }
/** Navigation cancellation cannot lose a durable acknowledgement; engine work still has its own deadline. */
internal suspend fun publicationRecoveryApplyWithinDeadline(timeoutMillis: Long = 60_000, action: suspend () -> Boolean): Boolean =
    withContext(NonCancellable) { withTimeout(timeoutMillis) { action() } }
internal fun publicationRecoveryRemovesTracking(action: PublicationRecoveryAction, durableSuccess: Boolean) =
    durableSuccess && action != PublicationRecoveryAction.Complete
internal fun publicationRecoveryAllowsHandoff(p: PublicationRecoveryPermissions) =
    !p.unreadable && !p.busy && !p.backingFileMissing && p.published
internal fun publicationRecoveryOwns(reviewKey: String, reviewRevision: Long, currentKey: String?, currentRevision: Long,
    sameProof: Boolean) = reviewKey == currentKey && reviewRevision == currentRevision && sameProof

internal sealed interface PublicationRecoveryEntry {
    data class Collage(val value: CreationCollagePublicationEntry) : PublicationRecoveryEntry
    data class Gif(val value: CreationGifPublicationEntry) : PublicationRecoveryEntry
    data class Motion(val value: MotionPhotoPublicationEntry) : PublicationRecoveryEntry
    val family: String get() = when (this) { is Collage -> "collage"; is Gif -> "gif"; is Motion -> "motion" }
    val id: String get() = when (this) { is Collage -> value.id; is Gif -> value.id; is Motion -> value.id }
    val key: String get() = "$family:$id"
    val kind: PublicationRecoveryKind get() = when (this) {
        is Collage -> PublicationRecoveryKind.Collage
        is Gif -> PublicationRecoveryKind.Gif
        is Motion -> when (value.receipt?.kind) {
            MotionPhotoPublicationKind.Frame -> PublicationRecoveryKind.MotionFrame
            MotionPhotoPublicationKind.Clip -> PublicationRecoveryKind.MotionClip
            null -> PublicationRecoveryKind.MotionUnknown
        }
    }
    val phase: String? get() = when (this) { is Collage -> value.receipt?.phase?.name; is Gif -> value.receipt?.phase?.name; is Motion -> value.receipt?.phase?.name }
    val name: String? get() = when (this) { is Collage -> value.receipt?.destination?.displayName; is Gif -> value.receipt?.destination?.displayName; is Motion -> value.receipt?.destination?.displayName }
    val unreadable: Boolean get() = when (this) { is Collage -> value.unreadable; is Gif -> value.unreadable; is Motion -> value.unreadable }
    val sourceIdentity: String? get() = (this as? Motion)?.value?.receipt?.sourceIdentity
}
internal sealed interface PublicationRecoveryProof {
    data class Collage(val value: CreationCollagePublicationResolution) : PublicationRecoveryProof
    data class Gif(val value: CreationGifPublicationResolution) : PublicationRecoveryProof
    data class Motion(val value: MotionPhotoPublicationResolution) : PublicationRecoveryProof
    val entry: PublicationRecoveryEntry get() = when (this) { is Collage -> PublicationRecoveryEntry.Collage(value.entry); is Gif -> PublicationRecoveryEntry.Gif(value.entry); is Motion -> PublicationRecoveryEntry.Motion(value.entry) }
    val permissions: PublicationRecoveryPermissions get() = when (this) {
        is Collage -> with(value) { PublicationRecoveryPermissions(entry.unreadable, busy, backingFileMissing, canComplete, canRemovePending, canForget, recovery?.status == CreationCollagePublicationStatus.Published) }
        is Gif -> with(value) { PublicationRecoveryPermissions(entry.unreadable, busy, backingFileMissing, canComplete, canRemovePending, canForget, recovery?.status == CreationGifPublicationStatus.Published) }
        is Motion -> with(value) { PublicationRecoveryPermissions(entry.unreadable, busy, backingFileMissing, canComplete, canRemovePending, canForget, recovery?.status == MotionPhotoPublicationStatus.Published) }
    }
    val pendingHash: String? get() = when (this) { is Collage -> value.pendingBytesSha256; is Gif -> value.pendingBytesSha256; is Motion -> value.pendingBytesSha256 }
    val pendingSize: Long? get() = when (this) { is Collage -> value.pendingBytesSize; is Gif -> value.pendingBytesSize; is Motion -> value.pendingBytesSize }
    val destinationName: String? get() = when (this) {
        is Collage -> value.pendingDestination?.displayName ?: value.recovery?.receipt?.destination?.displayName ?: entry.name
        is Gif -> value.pendingDestination?.displayName ?: value.recovery?.receipt?.destination?.displayName ?: entry.name
        is Motion -> value.pendingDestination?.displayName ?: value.recovery?.receipt?.destination?.displayName ?: entry.name
    }
    val status: String? get() = when (this) { is Collage -> value.recovery?.status?.name; is Gif -> value.recovery?.status?.name; is Motion -> value.recovery?.status?.name }
}
internal data class PublicationRecoveryConfirmation(val action: PublicationRecoveryAction, val proof: PublicationRecoveryProof, val revision: Long)
internal data class PublicationRecoveriesState(val entries: List<PublicationRecoveryEntry> = emptyList(), val busy: Boolean = false, val initialized: Boolean = false,
    val selectedKey: String? = null, val revision: Long = 0, val proof: PublicationRecoveryProof? = null,
    val confirmation: PublicationRecoveryConfirmation? = null, val message: PublicationRecoveryMessage? = null)

/** Screen-owned coordinator. All writes remain the engines' exact-proof, per-publication transactions. */
internal class PublicationRecoveriesViewModel(context: Context, private val scope: CoroutineScope,
    private val onTrackingRemoved: (String, String, String?) -> Unit) {
    private val collage = CreationCollageExporter(context.applicationContext)
    private val gif = CreationGifExporter(context.applicationContext)
    private val motion = MotionPhotoPublication(context.applicationContext)
    private val mutableState = MutableStateFlow(PublicationRecoveriesState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var epoch = 0L
    private var disposed = false
    private fun owns(value: Long) = !disposed && epoch == value
    private fun work(action: suspend (Long) -> Unit) {
        if (disposed || mutableState.value.busy || job?.isCompleted == false) return
        val token = ++epoch
        mutableState.value = mutableState.value.copy(busy = true)
        job = scope.launch {
            try { withTimeout(60_000) { action(token) } }
            catch (_: TimeoutCancellationException) { if (owns(token)) mutableState.value = mutableState.value.copy(initialized = true, message = PublicationRecoveryMessage.LoadError, proof = null, confirmation = null) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (owns(token)) mutableState.value = mutableState.value.copy(initialized = true, message = PublicationRecoveryMessage.Failed, proof = null, confirmation = null) }
            finally { if (owns(token)) mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
    fun dispose() { disposed = true; ++epoch; job?.cancel() }
    fun refresh() = work { token -> refreshOwned(token, null) }
    private suspend fun inspect(entry: PublicationRecoveryEntry): PublicationRecoveryProof = when (entry) {
        is PublicationRecoveryEntry.Collage -> PublicationRecoveryProof.Collage(collage.inspectResolution(entry.value))
        is PublicationRecoveryEntry.Gif -> PublicationRecoveryProof.Gif(gif.inspectResolution(entry.value))
        is PublicationRecoveryEntry.Motion -> PublicationRecoveryProof.Motion(motion.inspectResolution(entry.value))
    }.also { check(it.entry.key == entry.key) }
    private suspend fun refreshOwned(token: Long, result: PublicationRecoveryMessage?) {
        val old = mutableState.value
        val entries = mutableListOf<PublicationRecoveryEntry>()
        var failed = false
        suspend fun family(name: String, read: suspend () -> List<PublicationRecoveryEntry>) {
            try { entries += read() }
            catch (_: TimeoutCancellationException) { failed = true; entries += old.entries.filter { it.family == name } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true; entries += old.entries.filter { it.family == name } }
        }
        family("collage") { collage.listPublications().map { PublicationRecoveryEntry.Collage(it) } }
        family("gif") { gif.listPublications().map { PublicationRecoveryEntry.Gif(it) } }
        family("motion") { motion.listPublications().map { PublicationRecoveryEntry.Motion(it) } }
        if (!owns(token)) return
        val selected = entries.firstOrNull { it.key == old.selectedKey }
        var proof: PublicationRecoveryProof? = null
        if (selected != null) try { proof = inspect(selected) }
        catch (_: TimeoutCancellationException) { failed = true }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        if (!owns(token)) return
        mutableState.value = old.copy(entries = entries.sortedBy { it.key }, busy = true, initialized = true,
            selectedKey = selected?.key, revision = old.revision + 1, proof = proof, confirmation = null,
            message = if (failed) PublicationRecoveryMessage.LoadError else result)
    }
    fun select(key: String) = work { token ->
        val row = mutableState.value.entries.firstOrNull { it.key == key } ?: return@work
        val selected = mutableState.value.copy(selectedKey = key, revision = mutableState.value.revision + 1,
            proof = null, confirmation = null, message = null)
        mutableState.value = selected
        try {
            val proof = inspect(row)
            if (owns(token)) mutableState.value = mutableState.value.copy(proof = proof)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (owns(token)) mutableState.value = mutableState.value.copy(message = PublicationRecoveryMessage.LoadError) }
    }
    private fun current(proof: PublicationRecoveryProof, revision: Long) = publicationRecoveryOwns(proof.entry.key, revision,
        mutableState.value.selectedKey, mutableState.value.revision, mutableState.value.proof == proof)
    fun review(action: PublicationRecoveryAction) {
        val selected = mutableState.value
        val proof = selected.proof ?: return
        if (!publicationRecoveryAllows(action, proof.permissions)) return
        work { token ->
            val fresh = inspect(proof.entry)
            if (!owns(token) || !current(proof, selected.revision)) return@work
            if (fresh != proof || !publicationRecoveryAllows(action, fresh.permissions)) {
                mutableState.value = mutableState.value.copy(proof = fresh, revision = selected.revision + 1,
                    confirmation = null, message = PublicationRecoveryMessage.Changed)
            } else mutableState.value = mutableState.value.copy(confirmation = PublicationRecoveryConfirmation(action, fresh, selected.revision))
        }
    }
    fun dismiss() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(confirmation = null) }
    fun confirm() {
        val confirmation = mutableState.value.confirmation ?: return
        if (!current(confirmation.proof, confirmation.revision) || !publicationRecoveryAllows(confirmation.action, confirmation.proof.permissions)) {
            mutableState.value = mutableState.value.copy(confirmation = null, message = PublicationRecoveryMessage.Changed)
            return
        }
        work { token ->
            mutableState.value = mutableState.value.copy(confirmation = null)
            val fresh = inspect(confirmation.proof.entry)
            if (!owns(token) || !current(confirmation.proof, confirmation.revision)) return@work
            if (fresh != confirmation.proof || !publicationRecoveryAllows(confirmation.action, fresh.permissions)) {
                refreshOwned(token, PublicationRecoveryMessage.Changed)
                return@work
            }
            val outcome = try {
                val success = publicationRecoveryApplyWithinDeadline {
                    val complete = apply(confirmation.action, confirmation.proof)
                    if (publicationRecoveryRemovesTracking(confirmation.action, complete)) {
                        val entry = confirmation.proof.entry
                        onTrackingRemoved(entry.family, entry.id, entry.sourceIdentity)
                    }
                    complete
                }
                if (success) PublicationRecoveryMessage.Completed else PublicationRecoveryMessage.Changed
            } catch (_: TimeoutCancellationException) { PublicationRecoveryMessage.Failed
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { PublicationRecoveryMessage.Failed }
            if (owns(token)) refreshOwned(token, outcome)

        }
    }
    private suspend fun apply(action: PublicationRecoveryAction, proof: PublicationRecoveryProof): Boolean = when (proof) {
        is PublicationRecoveryProof.Collage -> when (action) {
            PublicationRecoveryAction.Complete -> collage.completePublication(proof.value)
            PublicationRecoveryAction.RemovePending -> collage.removePendingPublication(proof.value)
            PublicationRecoveryAction.Forget -> collage.forgetPublication(proof.value)
        }
        is PublicationRecoveryProof.Gif -> when (action) {
            PublicationRecoveryAction.Complete -> gif.completePublication(proof.value)
            PublicationRecoveryAction.RemovePending -> gif.removePendingPublication(proof.value)
            PublicationRecoveryAction.Forget -> gif.forgetPublication(proof.value)
        }
        is PublicationRecoveryProof.Motion -> when (action) {
            PublicationRecoveryAction.Complete -> motion.completePublication(proof.value)
            PublicationRecoveryAction.RemovePending -> motion.removePendingPublication(proof.value)
            PublicationRecoveryAction.Forget -> motion.forgetPublication(proof.value)
        }
    }
    suspend fun loadPreview(proof: PublicationRecoveryProof): Bitmap? {
        val revision = mutableState.value.revision
        if (disposed || mutableState.value.busy || !current(proof, revision) || !publicationRecoveryAllowsHandoff(proof.permissions)) return null
        try {
            val bitmap = when (proof) {
                is PublicationRecoveryProof.Collage -> collage.loadVerifiedResultPreview(checkNotNull(proof.value.recovery?.receipt))
                is PublicationRecoveryProof.Gif -> gif.loadVerifiedResultPreview(checkNotNull(proof.value.recovery?.receipt))
                is PublicationRecoveryProof.Motion -> motion.loadVerifiedPreview(checkNotNull(proof.value.recovery?.receipt))
            } ?: error("Result unavailable")
            if (!disposed && !mutableState.value.busy && current(proof, revision)) return bitmap
            bitmap.recycle()
        } catch (_: TimeoutCancellationException) {
            if (!disposed && current(proof, revision)) mutableState.value = mutableState.value.copy(proof = null, confirmation = null,
                revision = revision + 1, message = PublicationRecoveryMessage.LoadError)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (!disposed && current(proof, revision)) mutableState.value = mutableState.value.copy(proof = null, confirmation = null,
                revision = revision + 1, message = PublicationRecoveryMessage.LoadError)
        }
        return null
    }
    fun handoff(callback: (PublicationRecoveryTarget) -> Unit) {
        val selected = mutableState.value
        val proof = selected.proof ?: return
        if (!publicationRecoveryAllowsHandoff(proof.permissions)) return
        work { token ->
            val fresh = inspect(proof.entry)
            if (!owns(token) || !current(proof, selected.revision)) return@work
            if (fresh != proof || !publicationRecoveryAllowsHandoff(fresh.permissions)) {
                refreshOwned(token, PublicationRecoveryMessage.Changed)
                return@work
            }
            val uri = when (proof) {
                is PublicationRecoveryProof.Collage -> proof.value.recovery?.resultUri
                is PublicationRecoveryProof.Gif -> proof.value.recovery?.resultUri
                is PublicationRecoveryProof.Motion -> proof.value.recovery?.resultUri
            } ?: error("No verified result")
            val mime = when (proof.entry.kind) {
                PublicationRecoveryKind.Collage -> "image/png"
                PublicationRecoveryKind.Gif -> "image/gif"
                PublicationRecoveryKind.MotionFrame -> "image/jpeg"
                PublicationRecoveryKind.MotionClip -> "video/mp4"
                PublicationRecoveryKind.MotionUnknown -> error("Unidentified output kind")
            }
            callback(PublicationRecoveryTarget(proof.entry.id, proof.entry.kind, Uri.parse(uri), mime))
        }
    }
}
