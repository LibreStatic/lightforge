package com.ugallery.feature.ownsync

import android.content.Context
import android.net.Uri
import com.ugallery.core.remotestorage.RemoteConnectionFactory
import com.ugallery.core.remotestorage.RemoteCredentialVault
import com.ugallery.core.remotestorage.RemoteProfile
import java.io.InputStream

/**
 * Application supplies the existing server profiles and credential vault; no secrets in sync state.
 */
class OwnSyncServices(
    val profiles: () -> List<RemoteProfile>,
    val connections: RemoteConnectionFactory,
    val credentials: RemoteCredentialVault,
    val networkAllowed: () -> Boolean,
    val sourceFactory: (Context) -> OwnSyncSourcePort = { SafOwnSyncSource(it) },
)

interface OwnSyncSourcePort {
    /**
     * Write permission ownership before acquisition; regrant must retain the identical tree URI.
     */
    fun retain(jobId: String, tree: Uri)

    suspend fun scan(tree: Uri, check: () -> Unit = {}): OwnSyncSnapshot

    fun open(entry: OwnSyncSourceEntry): InputStream
}
