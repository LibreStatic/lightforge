package com.ugallery.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ugallery.feature.remotebackup.RemoteBackupContent
import com.ugallery.feature.remotebackup.RemoteBackupController

/** Android 17's runtime LAN permission is requested only after opening user-owned storage. */
@Composable
internal fun RemoteBackupScreen(
    controller: RemoteBackupController,
    onBack: () -> Unit,
    onOpenLocalTask: (String) -> Unit,
) {
    OwnStorageNetworkGate(onBack) { RemoteBackupContent(controller,onBack,onOpenLocalTask) }
}

@Composable
internal fun OwnStorageNetworkGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ownStorageNetworkAllowed(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = ownStorageNetworkAllowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = it
        }
    if (allowed) content()
    else
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.remote_storage_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(stringResource(R.string.remote_storage_permission_explanation))
                Button(
                    onClick = { request.launch(LocalNetworkPermission) },
                    modifier = Modifier.testTag("remote-network-permission"),
                ) {
                    Text(stringResource(R.string.remote_storage_permission_allow))
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + context.packageName),
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.remote_storage_permission_settings))
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.remote_storage_back)) }
            }
        }
}
