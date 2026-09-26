package com.ugallery.feature.ownsync

import com.ugallery.core.remotestorage.RemoteDigest
import com.ugallery.core.remotestorage.RemoteNames
import com.ugallery.core.remotestorage.RemoteProfile
import java.security.MessageDigest
import java.util.UUID

enum class OwnSyncPolicy {
    Additive,
    ManagedMirror,
}

enum class OwnSyncStatus {
    Queued,
    Running,
    AwaitingReview,
    Paused,
    WaitingPermission,
    NeedsReview,
    Completed,
    Cancelled,
}

enum class OwnSyncAction {
    Add,
    Verified,
    Conflict,
    Inaccessible,
    Quarantine,
    Restore,
}

data class OwnSyncSourceEntry(
    val key: String,
    val uri: String,
    val path: List<String>,
    val mime: String,
    val digest: RemoteDigest,
    val modified: Long,
)

data class OwnSyncSnapshot(
    val entries: List<OwnSyncSourceEntry>,
    val issues: List<String> = emptyList(),
    val directories: List<List<String>> = emptyList(),
) {
    val complete
        get() = issues.isEmpty()

    val fingerprint
        get() =
            ownSyncHash(
                (entries
                        .sortedBy { it.key }
                        .joinToString("\n") { "${it.key}:${it.path}:${it.digest}" } +
                        directories.sortedBy { it.joinToString("/") }.toString())
                    .toByteArray()
            )
}

data class OwnSyncOutput(
    val id: String,
    val sourceKey: String,
    val sourcePath: List<String>,
    val path: List<String>,
    val digest: RemoteDigest,
    val quarantine: String? = null,
)

data class OwnSyncPlanEntry(
    val id: String,
    val action: OwnSyncAction,
    val source: OwnSyncSourceEntry? = null,
    val output: OwnSyncOutput? = null,
    val path: List<String>,
    val done: Boolean = false,
    val staging: String? = null,
)

data class OwnSyncJob(
    val id: String,
    val name: String,
    val tree: String,
    val profile: RemoteProfile,
    val policy: OwnSyncPolicy,
    val createdAt: Long = System.currentTimeMillis(),
    val outputs: List<OwnSyncOutput> = emptyList(),
) {
    val namespace
        get() = "UGallery-Sync-$id"
}

data class OwnSyncRun(
    val id: String,
    val jobId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: OwnSyncStatus = OwnSyncStatus.Queued,
    val snapshot: OwnSyncSnapshot? = null,
    val plan: List<OwnSyncPlanEntry> = emptyList(),
    val approved: Boolean = false,
    val mirrorApproved: Boolean = false,
    val pauseRequested: Boolean = false,
    val cancelRequested: Boolean = false,
    val failure: String? = null,
    val residuals: List<String> = emptyList(),
    val restoration: Boolean = false,
) {
    val terminal
        get() = status == OwnSyncStatus.Completed || status == OwnSyncStatus.Cancelled

    /**
     * Status after the running lease stops. Only a user request pauses or cancels; a scheduler
     * stop (constraints, quota, Doze) re-queues so the worker retry resumes the run.
     */
    val interruptedStatus
        get() =
            when {
                cancelRequested -> OwnSyncStatus.Cancelled
                pauseRequested -> OwnSyncStatus.Paused
                else -> OwnSyncStatus.Queued
            }

    val bytesDone
        get() =
            plan
                .filter { it.done && it.action == OwnSyncAction.Add }
                .sumOf { it.source!!.digest.size }
}

internal fun ownSyncHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun ownSyncUuid() = UUID.randomUUID().toString()

internal fun ownSyncPath(path: List<String>) {
    require(path.isNotEmpty() && path.size <= 65)
    path.forEach(RemoteNames::requireChild)
}

/**
 * Stable alternate names preserve every version and every distinct source, including identical
 * bytes.
 */
internal fun ownSyncVersionName(name: String, sourceKey: String, digest: RemoteDigest): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 }
    val extension = dot?.let { name.substring(it) }.orEmpty()
    val stem = dot?.let { name.substring(0, it) } ?: name
    return RemoteNames.requireChild(
        stem.take(160) +
            "~" +
            ownSyncHash(sourceKey.toByteArray()).take(12) +
            "-" +
            digest.sha256.take(16) +
            extension
    )
}
