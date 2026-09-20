package com.ugallery.feature.remotebackup

import com.ugallery.core.remotestorage.RemoteEntry
import com.ugallery.core.remotestorage.RemoteProfile
import com.ugallery.feature.settings.LocalRestoreOrganizationOptions

/** Neither secrets nor exception messages are persisted or exposed by these models. */
enum class RemoteBackupDirection {
    Upload,
    Download,
}

enum class RemoteBackupStatus {
    Queued,
    Running,
    Paused,
    AwaitingUploadReview,
    AwaitingRestoreReview,
    WaitingConnection,
    WaitingCredentials,
    WaitingPermission,
    WaitingIdentity,
    NeedsReview,
    Failed,
    Cancelled,
    Completed,
    RestoringLocally,
}

enum class RemoteBackupPhase {
    Preparing,
    Transfer,
    Verify,
    Publish,
    RestoreHandoff,
    Done,
}

data class RemoteBackupTask(
    val id: String,
    val profile: RemoteProfile,
    val direction: RemoteBackupDirection,
    val createdAt: Long,
    val status: RemoteBackupStatus = RemoteBackupStatus.Queued,
    val phase: RemoteBackupPhase = RemoteBackupPhase.Preparing,
    val sources: List<String> = emptyList(),
    val organization: Boolean = false,
    val remote: RemoteEntry? = null,
    val name: String = "",
    val staging: String? = null,
    /**
     * Every attempted create is written ahead. Unknown outcomes stay visible, never deleted by
     * name.
     */
    val residuals: List<String> = emptyList(),
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val files: Int = 0,
    val archiveSha: String? = null,
    val pauseRequested: Boolean = false,
    val cancelRequested: Boolean = false,
    val failure: String? = null,
    val observedHostKey: String? = null,
    val localTaskId: String? = null,
    val restoreGallery: Boolean = false,
    val restoreDestination: String? = null,
    val restoreOptions: LocalRestoreOrganizationOptions = LocalRestoreOrganizationOptions(),
) {
    val terminal: Boolean
        get() =
            status in
                setOf(
                    RemoteBackupStatus.Cancelled,
                    RemoteBackupStatus.Completed,
                    RemoteBackupStatus.RestoringLocally,
                )
}

data class RemoteConnectionReview(
    val identity: String,
    val encrypted: Boolean,
    val signed: Boolean,
    val atomicPublish: Boolean,
)

data class RemoteProfileProbe(
    val profileId: String,
    val identity: String? = null,
    val failure: String? = null,
    val observedHostKey: String? = null,
    val residuals: List<String> = emptyList(),
    val encrypted: Boolean? = null,
    val signed: Boolean? = null,
    val atomicPublish: Boolean? = null,
)
