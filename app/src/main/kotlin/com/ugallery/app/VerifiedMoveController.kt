package com.ugallery.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ugallery.core.mediastore.*
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.Serializable
import java.util.UUID

/** Captured before opening SAF. Never substitute the viewer item current at callback time. */
internal data class PendingTreeOperation(
    val target: MediaActionTarget,
    val name: String,
    val mime: String,
    val lastModifiedMillis: Long?,
    val move: Boolean,
) : Serializable

internal data class VerifiedMoveUi(
    val entry: VerifiedMoveEntry? = null,
    val busy: Boolean = true,
    val authorization: String? = null,
    val failed: Boolean = false,
    val unreadable: Boolean = false,
    val corruptReview: CorruptMoveReview? = null,
    val copyDraft: MoveCopyDraft? = null,
    val copyReview: MoveCopyReview? = null,
)

/** One durable Move. Authorization is ephemeral and each attempt freshly reads both objects. */
internal class VerifiedMoveController(
    private val context: Context,
    private val scope: CoroutineScope,
    journalDirectory: File = File(context.noBackupFilesDir.canonicalFile, "verified-move"),
    private val requestFactory: MediaStoreRequestFactory = MediaStoreRequestFactory { _, uris ->
        android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
    },
) {
    private val resolver = context.contentResolver
    private val journal = VerifiedMoveJournal(journalDirectory, null) { path ->
        val fd = android.system.Os.open(path.toString(), android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
        try { check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)); android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    private val mutable = MutableStateFlow(VerifiedMoveUi())
    val state = mutable.asStateFlow()
    private val outgoing = Channel<MediaActionLaunch>(Channel.BUFFERED)
    val launches = outgoing.receiveAsFlow()
    private var authorizationInFlight: String? = null
    private var epoch = 0L
    private var copyAction: String? = null

    init { scope.launch { reload() } }
    private suspend fun reload(failed: Boolean = false) {
        try {
            mutable.value = withContext(Dispatchers.IO) {
                val copy = journal.readCopyDraft()
                if (copy != null) VerifiedMoveUi(busy = false, failed = failed, copyDraft = copy)
                else VerifiedMoveUi(journal.readActive(), busy = false, failed = failed)
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = VerifiedMoveUi(busy = false, failed = true, unreadable = true) }
    }

    suspend fun copy(input: PendingTreeOperation, tree: Uri): Uri {
        check(input.move && !state.value.busy && state.value.entry == null && state.value.copyDraft == null && !state.value.unreadable)
        mutable.value = state.value.copy(busy = true, failed = false)
        try {
            val acquired = persistCopyAccess(tree)
            val captured = MoveCopyOperations.capture(resolver, input.target, tree, input.name, input.mime, input.lastModifiedMillis, acquired)
            var draft = withContext(Dispatchers.IO) { journal.beginCopy(captured) }
            val destination = MoveCopyOperations.createDestination(resolver, draft)
            draft = withContext(Dispatchers.IO) { journal.attachCopyDestination(draft, destination.toString()) }
            val empty = MoveCopyOperations.review(resolver, draft)
            check(empty.bytes == 0L) { "New destination is not empty" }
            MoveCopyOperations.rewrite(resolver, draft, empty)
            val entry = withContext(Dispatchers.IO) { journal.promoteCopy(draft) }
            mutable.value = VerifiedMoveUi(entry, busy = false)
            requestAttempt()
            return destination
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { reload(failed = true) }; throw cancelled
        } catch (failure: Exception) { reload(failed = true); throw failure }
    }

    private fun persistCopyAccess(tree: Uri): Boolean {
        val previous = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return !previous && resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
    }

    private fun persistRead(tree: Uri): Boolean {
        val previous = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (previous) return false
        return runCatching {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        }.getOrDefault(false)
    }

    /** Reauthorize precisely the original tree; changing destination never authorizes deletion. */
    fun reauthorize(tree: Uri) {
        val current = state.value
        if (current.busy) return
        val expected = current.copyDraft?.treeUri ?: current.entry?.proof?.treeUri ?: return
        if (tree.toString() != expected) { mutable.value = current.copy(failed = true); return }
        if (current.copyDraft != null) persistCopyAccess(tree) else persistRead(tree)
        mutable.value = current.copy(failed = false, copyReview = null)
    }

    // An empty MediaStore cursor can also mean lost/partial access, not deletion.
    private fun requireAbsenceVisibility(proof: VerifiedMoveProof) {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (proof.targetKind == "Image") android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_MEDIA_VIDEO
        } else android.Manifest.permission.READ_EXTERNAL_STORAGE
        check(context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Full collection access is required to reconcile an unavailable original"
        }
    }

    fun requestAttempt() {
        if (state.value.copyDraft != null) { reviewInterruptedCopy(); return }
        val current = state.value
        if (current.busy || current.entry == null || current.entry.phase == VerifiedMovePhase.Completed) return
        val token = UUID.randomUUID().toString()
        mutable.value = current.copy(authorization = token, busy = true, failed = false)
    }

    fun claimAuthorization(token: String): Boolean {
        if (state.value.authorization != token || authorizationInFlight != null) return false
        authorizationInFlight = token
        mutable.value = state.value.copy(authorization = null)
        return true
    }

    fun finishAuthorization(token: String, approved: Boolean) {
        if (authorizationInFlight != token) return
        authorizationInFlight = null
        val reviewedAction = copyAction
        copyAction = null
        if (!approved) { mutable.value = state.value.copy(busy = false); return }
        if (reviewedAction != null) { executeReviewedCopy(reviewedAction); return }
        val entry = state.value.entry ?: return
        val attemptEpoch = ++epoch
        scope.launch {
            try {
                var active = withContext(Dispatchers.IO) {
                    check(journal.readActive() == entry)
                    if (entry.phase == VerifiedMovePhase.AwaitingSystem)
                        journal.advance(entry, VerifiedMovePhase.RequestFailed, entry.requestId) else entry
                }
                val requestId = Math.addExact(active.requestId, 1L)
                active = withContext(Dispatchers.IO) { journal.advance(active, VerifiedMovePhase.AwaitingSystem, requestId) }
                mutable.value = VerifiedMoveUi(active, busy = true)
                val originalPresent = VerifiedMoveOperations.identity(resolver, Uri.parse(active.proof.sourceUri)) != null
                check(epoch == attemptEpoch)
                if (!originalPresent) {
                    requireAbsenceVisibility(active.proof)
                    VerifiedMoveOperations.verifyDestination(resolver, active.proof)
                    val complete = withContext(Dispatchers.IO) { journal.advance(active, VerifiedMovePhase.Completed, requestId) }
                    mutable.value = VerifiedMoveUi(complete, busy = false)
                    return@launch
                }
                val snapshot = MediaActionReducer.start(MediaAction.MoveDelete(active.proof.id), 1).copy(nextRequestId = requestId)
                val coordinator = MediaStoreActionCoordinator(resolver, snapshot, requestFactory = requestFactory, moveGuard = { action, targets ->
                    check(action.proofId == active.proof.id && targets == listOf(VerifiedMoveOperations.target(active.proof)))
                    check(journal.readActive() == active && epoch == attemptEpoch)
                    VerifiedMoveOperations.verify(resolver, active.proof)
                })
                val launch = coordinator.stageVerifiedMove(listOf(VerifiedMoveOperations.target(active.proof)))
                currentCoroutineContext().ensureActive()
                check(epoch == attemptEpoch)
                outgoing.send(launch)
                // Remains busy until Android returns. Recreation never re-emits this request.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.IO) { runCatching {
                    journal.readActive()?.takeIf { it.proof.id == entry.proof.id && it.phase == VerifiedMovePhase.AwaitingSystem }?.let {
                        journal.advance(it, VerifiedMovePhase.RequestFailed, it.requestId)
                    }
                } }
                reload(failed = true)
            }
        }
    }

    /** Revalidate at UI handoff too: a queued IntentSender is not a reusable integrity proof. */
    suspend fun confirmLaunch(requestId: Long): Boolean {
        val entry = state.value.entry ?: return false
        if (entry.phase != VerifiedMovePhase.AwaitingSystem || entry.requestId != requestId || !state.value.busy) return false
        return try {
            withContext(Dispatchers.IO) { check(journal.readActive() == entry) }
            VerifiedMoveOperations.verify(resolver, entry.proof)
            withContext(Dispatchers.IO) { check(journal.readActive() == entry) }
            currentCoroutineContext().ensureActive()
            state.value.entry == entry && state.value.busy
        } catch (cancelled: CancellationException) {
            deferLaunch(requestId); throw cancelled
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { runCatching { if (journal.readActive() == entry) journal.advance(entry, VerifiedMovePhase.RequestFailed, requestId) } }
            reload(failed = true)
            false
        }
    }

    fun deferLaunch(requestId: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { journal.readActive()?.takeIf {
                it.phase == VerifiedMovePhase.AwaitingSystem && it.requestId == requestId
            }?.let { journal.advance(it, VerifiedMovePhase.RequestFailed, requestId) } } }
            reload(failed = true)
        }
    }

    fun onSystemResult(requestId: Long, approved: Boolean) {
        scope.launch {
            val entry = try { withContext(Dispatchers.IO) { journal.readActive() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reload(failed = true); return@launch }
                ?: return@launch
            if (entry.phase != VerifiedMovePhase.AwaitingSystem || entry.requestId != requestId) return@launch
            ++epoch
            mutable.value = VerifiedMoveUi(entry, busy = true)
            try {
                val phase = if (!approved) VerifiedMovePhase.Cancelled else {
                    VerifiedMoveOperations.verifyDestination(resolver, entry.proof)
                    requireAbsenceVisibility(entry.proof)
                    check(VerifiedMoveOperations.identity(resolver, Uri.parse(entry.proof.sourceUri)) == null) { "Original remains" }
                    VerifiedMovePhase.Completed
                }
                val result = withContext(Dispatchers.IO) { journal.advance(entry, phase, requestId) }
                mutable.value = VerifiedMoveUi(result, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.IO) { runCatching { journal.readActive()?.takeIf { it == entry }?.let { journal.advance(it, VerifiedMovePhase.RequestFailed, requestId) } } }
                reload(failed = true)
            }
        }
    }

    fun reviewInterruptedCopy() {
        val current = state.value
        val draft = current.copyDraft ?: return
        if (current.busy || draft.destinationUri == null) return
        mutable.value = current.copy(busy = true)
        scope.launch {
            try {
                val review = withContext(Dispatchers.IO) {
                    check(journal.readCopyDraft() == draft)
                    MoveCopyOperations.review(resolver, draft)
                }
                mutable.value = state.value.copy(busy = false, copyReview = review)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reload(failed = true) }
        }
    }

    fun dismissCopyReview() {
        if (!state.value.busy) mutable.value = state.value.copy(copyReview = null)
    }

    fun retryReviewedCopy() = requestCopyAuthorization("retry")
    fun discardReviewedCopy() = requestCopyAuthorization("discard")
    private fun requestCopyAuthorization(action: String) {
        val current = state.value
        if (current.busy || current.copyDraft == null || current.copyReview == null) return
        copyAction = action
        mutable.value = current.copy(busy = true, authorization = UUID.randomUUID().toString())
    }

    private fun executeReviewedCopy(action: String) {
        val draft = state.value.copyDraft ?: return
        val review = state.value.copyReview ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(journal.readCopyDraft() == draft)
                    if (action == "retry") {
                        MoveCopyOperations.rewrite(resolver, draft, review)
                        journal.promoteCopy(draft)
                    } else {
                        check(action == "discard")
                        MoveCopyOperations.discard(resolver, draft, review)
                        check(journal.forgetCopy(draft))
                    }
                }
                reload()
                if (action == "retry") requestAttempt() // Separate fresh authorization for original deletion.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reload(failed = true) }
        }
    }

    /** Explicit tracking-only closure, including unknown createDocument outcomes. */
    fun forgetInterruptedCopy() {
        val current = state.value
        val draft = current.copyDraft ?: return
        if (current.busy) return
        mutable.value = current.copy(busy = true)
        scope.launch {
            try { withContext(Dispatchers.IO) { check(journal.forgetCopy(draft)) }; reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reload(failed = true) }
        }
    }

    fun reviewUnreadable() {
        if (state.value.busy || !state.value.unreadable) return
        mutable.value = state.value.copy(busy = true)
        scope.launch {
            try {
                val review = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext(); journal.reviewCorrupt { job.ensureActive() }
                }
                mutable.value = state.value.copy(busy = false, corruptReview = review)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reload(failed = true) }
        }
    }

    fun dismissCorruptReview() {
        if (!state.value.busy) mutable.value = state.value.copy(corruptReview = null)
    }

    fun preserveReviewedCorruption() {
        val review = state.value.corruptReview ?: return
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext(); journal.quarantineCorrupt(review) { job.ensureActive() }
                }
                reload()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reload(failed = true) }
        }
    }

    /** Ends tracking only. Keep public copies and grants: another app feature may now use the tree. */
    fun forget() {
        val entry = state.value.entry ?: return
        if (state.value.busy) return
        ++epoch
        mutable.value = state.value.copy(busy = true)
        scope.launch {
            try { withContext(Dispatchers.IO) { check(journal.forget(entry)) }; reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reload(failed = true) }
        }
    }
}
