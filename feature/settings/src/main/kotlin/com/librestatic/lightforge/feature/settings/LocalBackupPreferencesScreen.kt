package com.librestatic.lightforge.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.preferences.PortablePreferencesPort
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keeps the archive session alive; only the operation UUID goes into parent SavedState. */
@Composable
internal fun LocalBackupPreferencesScreen(
    preferencesPort: PortablePreferencesPort,
    organizationPort: LocalBackupOrganizationPort,
    archivePath: String?,
    manifest: BackupManifest?,
    operationId: String,
    onBack: () -> Unit,
) {
    var bytes by remember(operationId, archivePath) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(operationId, archivePath) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(operationId, archivePath, manifest, retry) {
        if (archivePath == null || manifest == null) return@LaunchedEffect
        failed = false
        bytes = null
        try {
            bytes = withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                val sidecar = LocalBackupArchive.readOrganization(File(archivePath), manifest) {
                    coroutine.ensureActive()
                }
                requireNotNull(organizationPort.preferencesForReview(sidecar, manifest))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val payload = bytes
    if (payload != null) {
        PortablePreferencesContent(preferencesPort, payload, operationId, onBack)
    } else {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.portable_preferences_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onBack) { Text(stringResource(R.string.portable_preferences_back)) }
                if (failed) {
                    Text(stringResource(R.string.local_backup_failed))
                    OutlinedButton(onClick = { retry++ }) { Text(stringResource(R.string.portable_preferences_reload)) }
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
