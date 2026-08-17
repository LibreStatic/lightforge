package com.ugallery.core.mediastore

import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import java.io.Serializable

sealed interface MediaAction : Serializable {
    data object Write : MediaAction
    data class Favorite(val enabled: Boolean) : MediaAction
    data class Trash(val enabled: Boolean) : MediaAction
    data object Delete : MediaAction
}

data class MediaActionTarget(val key: MediaKey, val kind: MediaKind) : Serializable

data class MediaActionProgress(
    val action: MediaAction,
    val totalSelected: Long,
    val completed: Long = 0,
    val authorized: Long = 0,
    val failed: Long = 0,
    val skipped: Long = 0,
) : Serializable {
    val accounted: Long get() = completed + authorized + failed + skipped

    init {
        require(totalSelected >= 0)
        require(listOf(completed, authorized, failed, skipped).all { it >= 0 })
        require(accounted <= totalSelected)
    }
}

sealed interface MediaActionPhase : Serializable {
    data object ReadyForChunk : MediaActionPhase
    data class AwaitingSystem(val requestId: Long, val targets: List<MediaActionTarget>) : MediaActionPhase
    data class Cancelled(val requestId: Long, val targets: List<MediaActionTarget>) : MediaActionPhase
    data class RequestFailed(val requestId: Long, val targets: List<MediaActionTarget>, val reason: String) : MediaActionPhase
    data object Complete : MediaActionPhase
}

data class MediaActionSnapshot(
    val version: Int = CurrentVersion,
    val progress: MediaActionProgress,
    val phase: MediaActionPhase,
    val nextRequestId: Long,
) : Serializable {
    companion object { const val CurrentVersion = 1 }
}

enum class VerifiedDisposition { Completed, Authorized }

object MediaActionReducer {
    const val MaxChunkSize = 500

    fun start(action: MediaAction, totalSelected: Long) = MediaActionSnapshot(
        progress = MediaActionProgress(action, totalSelected),
        phase = if (totalSelected == 0L) MediaActionPhase.Complete else MediaActionPhase.ReadyForChunk,
        nextRequestId = 1,
    )

    fun stage(snapshot: MediaActionSnapshot, targets: List<MediaActionTarget>): MediaActionSnapshot {
        validate(snapshot)
        require(snapshot.phase == MediaActionPhase.ReadyForChunk)
        require(targets.isNotEmpty() && targets.size <= MaxChunkSize)
        require(targets.map { it.key }.distinct().size == targets.size)
        require(snapshot.progress.accounted + targets.size <= snapshot.progress.totalSelected)
        return snapshot.copy(
            phase = MediaActionPhase.AwaitingSystem(snapshot.nextRequestId, targets.toList()),
            nextRequestId = snapshot.nextRequestId + 1,
        )
    }

    fun cancel(snapshot: MediaActionSnapshot, requestId: Long): MediaActionSnapshot {
        val awaiting = snapshot.phase as? MediaActionPhase.AwaitingSystem
            ?: return snapshot
        if (awaiting.requestId != requestId) return snapshot
        return snapshot.copy(phase = MediaActionPhase.Cancelled(requestId, awaiting.targets))
    }

    fun retry(snapshot: MediaActionSnapshot): MediaActionSnapshot {
        val targets = when (val phase = snapshot.phase) {
            is MediaActionPhase.Cancelled -> phase.targets
            is MediaActionPhase.RequestFailed -> phase.targets
            else -> error("Only a cancelled or failed chunk can be retried")
        }
        return snapshot.copy(
            phase = MediaActionPhase.AwaitingSystem(snapshot.nextRequestId, targets),
            nextRequestId = snapshot.nextRequestId + 1,
        )
    }

    fun requestFailed(snapshot: MediaActionSnapshot, requestId: Long, reason: String): MediaActionSnapshot {
        val awaiting = snapshot.phase as? MediaActionPhase.AwaitingSystem ?: return snapshot
        if (awaiting.requestId != requestId) return snapshot
        return snapshot.copy(
            phase = MediaActionPhase.RequestFailed(requestId, awaiting.targets, reason.take(300)),
        )
    }

    fun skipCancelled(snapshot: MediaActionSnapshot): MediaActionSnapshot {
        val cancelled = snapshot.phase as? MediaActionPhase.Cancelled
            ?: error("No cancelled chunk to skip")
        val progress = snapshot.progress.copy(
            skipped = snapshot.progress.skipped + cancelled.targets.size,
        )
        return snapshot.copy(progress = progress, phase = nextPhase(progress))
    }

    fun verified(
        snapshot: MediaActionSnapshot,
        requestId: Long,
        succeeded: Int,
        failed: Int,
        disposition: VerifiedDisposition,
    ): MediaActionSnapshot {
        val awaiting = snapshot.phase as? MediaActionPhase.AwaitingSystem ?: return snapshot
        if (awaiting.requestId != requestId) return snapshot
        require(succeeded >= 0 && failed >= 0 && succeeded + failed == awaiting.targets.size)
        val current = snapshot.progress
        val progress = when (disposition) {
            VerifiedDisposition.Completed -> current.copy(
                completed = current.completed + succeeded,
                failed = current.failed + failed,
            )
            VerifiedDisposition.Authorized -> current.copy(
                authorized = current.authorized + succeeded,
                failed = current.failed + failed,
            )
        }
        return snapshot.copy(progress = progress, phase = nextPhase(progress))
    }

    fun failUnresolvedRemainder(snapshot: MediaActionSnapshot): MediaActionSnapshot {
        require(snapshot.phase == MediaActionPhase.ReadyForChunk)
        val remaining = snapshot.progress.totalSelected - snapshot.progress.accounted
        val progress = snapshot.progress.copy(failed = snapshot.progress.failed + remaining)
        return snapshot.copy(progress = progress, phase = MediaActionPhase.Complete)
    }

    fun validate(snapshot: MediaActionSnapshot) {
        require(snapshot.version == MediaActionSnapshot.CurrentVersion)
        require(snapshot.nextRequestId > 0)
        val pending = when (val phase = snapshot.phase) {
            is MediaActionPhase.AwaitingSystem -> phase.targets.size
            is MediaActionPhase.Cancelled -> phase.targets.size
            is MediaActionPhase.RequestFailed -> phase.targets.size
            else -> 0
        }
        require(pending <= MaxChunkSize)
        require(snapshot.progress.accounted + pending <= snapshot.progress.totalSelected)
    }

    private fun nextPhase(progress: MediaActionProgress): MediaActionPhase =
        if (progress.accounted == progress.totalSelected) MediaActionPhase.Complete
        else MediaActionPhase.ReadyForChunk
}
