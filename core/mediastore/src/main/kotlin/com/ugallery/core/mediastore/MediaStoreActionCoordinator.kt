package com.ugallery.core.mediastore

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot.also(MediaActionReducer::validate))
    val snapshot: StateFlow<MediaActionSnapshot> = mutableSnapshot

    fun stageChunk(targets: List<MediaActionTarget>): MediaActionLaunch =
        update(MediaActionReducer.stage(mutableSnapshot.value, targets)).launchCurrent()

    /** Recreates a system request after process recreation without losing the current chunk. */
    fun recreateCurrentRequest(): MediaActionLaunch = mutableSnapshot.value.launchCurrent()

    fun retryCurrent(): MediaActionLaunch = update(MediaActionReducer.retry(mutableSnapshot.value)).launchCurrent()

    fun skipCancelled() = update(MediaActionReducer.skipCancelled(mutableSnapshot.value))

    suspend fun onSystemResult(requestId: Long, approved: Boolean): MediaActionSnapshot {
        if (!approved) return update(MediaActionReducer.cancel(mutableSnapshot.value, requestId))
        val current = mutableSnapshot.value
        val awaiting = current.phase as? MediaActionPhase.AwaitingSystem ?: return current
        if (awaiting.requestId != requestId) return current
        return try {
            val result = withContext(ioDispatcher) { verify(current.progress.action, awaiting.targets) }
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
            update(MediaActionReducer.requestFailed(current, requestId, failure.safeReason()))
        }
    }

    private fun MediaActionSnapshot.launchCurrent(): MediaActionLaunch {
        val awaiting = phase as? MediaActionPhase.AwaitingSystem
            ?: error("No system request is waiting")
        return try {
            val uris = awaiting.targets.map { it.mediaUri() }
            MediaActionLaunch(awaiting.requestId, requestFactory.create(progress.action, uris).intentSender)
        } catch (failure: Throwable) {
            update(MediaActionReducer.requestFailed(this, awaiting.requestId, failure.safeReason()))
            throw failure
        }
    }

    private fun verify(action: MediaAction, targets: List<MediaActionTarget>): VerificationResult = when (action) {
        MediaAction.Write -> VerificationResult(targets.size, 0, VerifiedDisposition.Authorized)
        MediaAction.Delete -> targets.countResult { target -> !exists(target.mediaUri()) }
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
        MediaAction.Delete -> MediaStore.createDeleteRequest(resolver, uris)
    }
}
