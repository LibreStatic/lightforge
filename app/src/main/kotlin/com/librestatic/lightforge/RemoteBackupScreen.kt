package com.librestatic.lightforge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.lightforge.core.designsystem.GalleryContentWidths
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.feature.remotebackup.RemoteBackupContent
import com.librestatic.lightforge.feature.remotebackup.RemoteBackupController

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
            Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(
                title = stringResource(R.string.remote_storage_title),
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.remote_storage_back),
                // The shell scaffold already placed these routes below the status bar.
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            Column(
                Modifier.fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = GalleryContentWidths.Reading)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
            }
            }
        }
}
