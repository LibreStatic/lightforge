package com.ugallery.core.mediastore

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MediaActionLaunch(val requestId: Long, val intentSender: IntentSender)

fun interface MediaStoreRequestFactory {
    fun create(action: MediaAction, uris: List<Uri>): PendingIntent
}

class MediaStoreActionCoordinator(
    private val resolver: ContentResolver,
    initialSnapshot: MediaActionSnapshot,
    private val requestFactory: MediaStoreRequestFactory = PlatformMediaStoreRequestFactory(resolver),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persist: (MediaActionSnapshot) -> Unit = {},
    private val moveGuard: suspend (MediaAction.MoveDelete, List<MediaActionTarget>) -> Unit = { _, _ ->
        error("VerifiedMoveGuardRequired")
    },
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot.also(MediaActionReducer::validate))
    val snapshot: StateFlow<MediaActionSnapshot> = mutableSnapshot

    private val moveAttempts = Mutex()
    private var guardedMoveRequest: Long? = null

    private fun requireOrdinaryAction() {
        require(mutableSnapshot.value.progress.action !is MediaAction.MoveDelete) { "Use verified move entry point" }
    }
    fun stageChunk(targets: List<MediaActionTarget>): MediaActionLaunch {
        requireOrdinaryAction()
        return update(MediaActionReducer.stage(mutableSnapshot.value, targets)).launchCurrent()
    }
    /** Recreates ordinary system requests; move deletion must freshly verify its proof. */
    fun recreateCurrentRequest(): MediaActionLaunch {
        requireOrdinaryAction()
        return mutableSnapshot.value.launchCurrent()
    }
    fun retryCurrent(): MediaActionLaunch {
        requireOrdinaryAction()
        return update(MediaActionReducer.retry(mutableSnapshot.value)).launchCurrent()
    }
    suspend fun stageVerifiedMove(targets: List<MediaActionTarget>): MediaActionLaunch = moveAttempts.withLock {
        require(mutableSnapshot.value.progress.action is MediaAction.MoveDelete)
        require(targets.size == 1)
        launchVerifiedMove(update(MediaActionReducer.stage(mutableSnapshot.value, targets)))
    }
    suspend fun retryVerifiedMove(): MediaActionLaunch = moveAttempts.withLock {
        require(mutableSnapshot.value.progress.action is MediaAction.MoveDelete)
        launchVerifiedMove(update(MediaActionReducer.retry(mutableSnapshot.value)))
    }
    suspend fun recreateVerifiedMove(): MediaActionLaunch = moveAttempts.withLock {
        require(mutableSnapshot.value.progress.action is MediaAction.MoveDelete)
        launchVerifiedMove(mutableSnapshot.value)
    }
    private suspend fun launchVerifiedMove(current: MediaActionSnapshot): MediaActionLaunch {
        val action = current.progress.action as MediaAction.MoveDelete
        val awaiting = current.phase as? MediaActionPhase.AwaitingSystem ?: error("No system request is waiting")
        require(awaiting.targets.size == 1)
        guardedMoveRequest = null
        return try {
            val launch = withContext(ioDispatcher) {
                moveGuard(action, awaiting.targets)
                currentCoroutineContext().ensureActive()
                // Do not create a request after another callback invalidated this attempt.
                check(mutableSnapshot.value === current) { "StaleVerifiedMoveAttempt" }
                MediaActionLaunch(awaiting.requestId,
                    requestFactory.create(action, awaiting.targets.map { it.mediaUri() }).intentSender)
            }
            check(mutableSnapshot.value === current) { "StaleVerifiedMoveAttempt" }
            guardedMoveRequest = awaiting.requestId
            launch
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (mutableSnapshot.value === current) {
                update(MediaActionReducer.requestFailed(current, awaiting.requestId, failure.safeReason()))
            }
            throw failure
        }
    }

    fun skipCancelled() = update(MediaActionReducer.skipCancelled(mutableSnapshot.value))

    suspend fun onSystemResult(requestId: Long, approved: Boolean): MediaActionSnapshot {
        if (!approved) return update(MediaActionReducer.cancel(mutableSnapshot.value, requestId))
        val current = mutableSnapshot.value
        val awaiting = current.phase as? MediaActionPhase.AwaitingSystem ?: return current
        if (awaiting.requestId != requestId) return current
        if (current.progress.action is MediaAction.MoveDelete && guardedMoveRequest != requestId) {
            return update(MediaActionReducer.requestFailed(current, requestId, "VerifiedMoveGuardRequired"))
        }
        return try {
            val result = withContext(ioDispatcher) { verify(current.progress.action, awaiting.targets) }
            if (mutableSnapshot.value !== current) return mutableSnapshot.value
            update(
                MediaActionReducer.verified(
                    current,
                    requestId,
                    result.succeeded,
                    result.failed,
                    result.disposition,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (mutableSnapshot.value !== current) mutableSnapshot.value
            else update(MediaActionReducer.requestFailed(current, requestId, failure.safeReason()))
        }
    }

    private fun MediaActionSnapshot.launchCurrent(): MediaActionLaunch {
        val awaiting = phase as? MediaActionPhase.AwaitingSystem
            ?: error("No system request is waiting")
        require(progress.action !is MediaAction.MoveDelete)
        return try {
            val uris = awaiting.targets.map { it.mediaUri() }
            MediaActionLaunch(awaiting.requestId, requestFactory.create(progress.action, uris).intentSender)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            update(MediaActionReducer.requestFailed(this, awaiting.requestId, failure.safeReason()))
            throw failure
        }
    }

    private fun verify(action: MediaAction, targets: List<MediaActionTarget>): VerificationResult = when (action) {
        MediaAction.Write -> VerificationResult(targets.size, 0, VerifiedDisposition.Authorized)
        MediaAction.Delete, is MediaAction.MoveDelete -> targets.countResult { target -> !exists(target.mediaUri()) }
        is MediaAction.Favorite -> targets.countResult { target ->
            booleanColumn(target.mediaUri(), MediaStore.MediaColumns.IS_FAVORITE) == action.enabled
        }
        is MediaAction.Trash -> targets.countResult { target ->
            booleanColumn(target.mediaUri(), MediaStore.MediaColumns.IS_TRASHED) == action.enabled
        }
    }

    private fun exists(uri: Uri): Boolean = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
        ?.use { it.moveToFirst() } == true

    private fun booleanColumn(uri: Uri, column: String): Boolean? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getInt(0) != 0
        }

    private fun List<MediaActionTarget>.countResult(predicate: (MediaActionTarget) -> Boolean): VerificationResult {
        val succeeded = count(predicate)
        return VerificationResult(succeeded, size - succeeded, VerifiedDisposition.Completed)
    }

    private fun update(value: MediaActionSnapshot): MediaActionSnapshot {
        MediaActionReducer.validate(value)
        mutableSnapshot.value = value
        persist(value)
        return value
    }

    private fun MediaActionTarget.mediaUri(): Uri = ContentUris.withAppendedId(
        when (kind) {
            MediaKind.Image -> MediaStore.Images.Media.getContentUri(key.volumeName)
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(key.volumeName)
        },
        key.mediaStoreId,
    )

    private fun Throwable.safeReason(): String = javaClass.simpleName.takeIf(String::isNotBlank)
        ?: "SystemRequestFailure"

    private data class VerificationResult(
        val succeeded: Int,
        val failed: Int,
        val disposition: VerifiedDisposition,
    )
}

private class PlatformMediaStoreRequestFactory(
    private val resolver: ContentResolver,
) : MediaStoreRequestFactory {
    override fun create(action: MediaAction, uris: List<Uri>): PendingIntent = when (action) {
        MediaAction.Write -> MediaStore.createWriteRequest(resolver, uris)
        is MediaAction.Favorite -> MediaStore.createFavoriteRequest(resolver, uris, action.enabled)
        is MediaAction.Trash -> MediaStore.createTrashRequest(resolver, uris, action.enabled)
        MediaAction.Delete, is MediaAction.MoveDelete -> MediaStore.createDeleteRequest(resolver, uris)
    }
}
