package com.librestatic.lightforge.feature.remotebackup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.remotestorage.RemoteConnectionFactory
import com.librestatic.lightforge.core.remotestorage.RemoteCredentialVault
import com.librestatic.lightforge.core.remotestorage.RemoteCredentials
import com.librestatic.lightforge.core.remotestorage.RemoteProfile
import com.librestatic.lightforge.core.remotestorage.RemoteProtocol

// Debug-only, zero-argument entry point for tools/compose-driver. It runs the real
// RemoteBackupContent on a real controller whose services are inert fakes (no network, no vault).

/** Own-server backup with two saved servers, the first one selected. */
@Composable
fun RemoteBackupPreview() {
    val context = LocalContext.current
    val controller = remember {
        RemoteBackupController(
            context,
            RemoteBackupServices(
                RemoteConnectionFactory { _, _, _ -> error("No network in the driver") },
                object : RemoteCredentialVault {
                    override fun save(profileId: String, credentials: RemoteCredentials) {}

                    override fun load(profileId: String): RemoteCredentials? = null

                    override fun delete(profileId: String) {}
                },
                null,
                RemoteRestoreBridge { _, _, _, _, _, _ -> "fixture" },
                { true },
            ),
        ) {}
    }
    LaunchedEffect(controller) {
        controller.saveProfile(
            RemoteProfile(
                name = "Home NAS",
                protocol = RemoteProtocol.SFTP,
                host = "nas.local",
                username = "photos",
                root = "/backups",
            ),
            RemoteCredentials.Password("pw".toCharArray()),
        )
        controller.saveProfile(
            RemoteProfile(
                name = "Office share",
                protocol = RemoteProtocol.SMB,
                host = "files.office.lan",
                username = "photos",
                root = "/lightforge",
                share = "archive",
            ),
            RemoteCredentials.Password("pw".toCharArray()),
        )
    }
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            RemoteBackupContent(controller, onBack = {})
        }
    }
}
