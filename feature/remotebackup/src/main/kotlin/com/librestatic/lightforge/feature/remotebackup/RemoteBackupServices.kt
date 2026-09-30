package com.librestatic.lightforge.feature.remotebackup

import android.net.Uri
import com.librestatic.lightforge.core.remotestorage.RemoteConnectionFactory
import com.librestatic.lightforge.core.remotestorage.RemoteCredentialVault
import com.librestatic.lightforge.feature.settings.BackupManifest
import com.librestatic.lightforge.feature.settings.LocalBackupOrganizationPort
import com.librestatic.lightforge.feature.settings.LocalRestoreOrganizationOptions
import java.io.File

/**
 * Application bridge owns credentials, platform permission and the idempotent local restore
 * handoff.
 */
class RemoteBackupServices(
    val connections: RemoteConnectionFactory,
    val credentials: RemoteCredentialVault,
    val organization: LocalBackupOrganizationPort?,
    val restore: RemoteRestoreBridge,
    val networkAllowed: () -> Boolean,
)

fun interface RemoteRestoreBridge {
    /**
     * Read-only receipt lookup. Production bridge also recovers the deterministic child before
     * receipt publication.
     */
    suspend fun existing(requestId: String): String? = null

    /**
     * Repeating the same requestId returns the SAME durable local task, including after process
     * death.
     */
    suspend fun enqueueOnce(
        requestId: String,
        archive: File,
        manifest: BackupManifest,
        destination: Uri?,
        gallery: Boolean,
        options: LocalRestoreOrganizationOptions,
    ): String
}
