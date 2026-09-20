package com.ugallery.feature.privatealbum

import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.security.PrivateAlbumCrypto
import java.util.Locale
import kotlinx.coroutines.*

private enum class RecoveryAction { Complete, Open, Discard, Forget }

/** Private, session-bound review. Opening or refreshing only reads the durable recovery inventory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateExportRecoveryContent(
    repository: PrivateAlbumRepository,
    isUnlocked: Boolean,
    onBack: () -> Unit,
    onAuthenticationRequired: () -> Unit,
    initialExportFailed: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unlockedNow by rememberUpdatedState(isUnlocked)
    val authenticationRequired by rememberUpdatedState(onAuthenticationRequired)
    val openLabel by rememberUpdatedState(stringResource(R.string.private_export_recovery_open))
    var rows by remember(repository) { mutableStateOf(emptyList<PrivateExportRecovery>()) }
    var selected by remember(repository) { mutableStateOf<PrivateExportRecovery?>(null) }
    var confirmation by remember(repository) { mutableStateOf<Pair<PrivateExportRecovery, RecoveryAction>?>(null) }
    var working by remember(repository) { mutableStateOf(false) }
    var loaded by remember(repository) { mutableStateOf(false) }
    var error by remember(repository) { mutableStateOf<Int?>(null) }
    var message by remember(repository) { mutableStateOf<Int?>(null) }
    var job by remember(repository) { mutableStateOf<Job?>(null) }
    var generation by remember(repository) { mutableIntStateOf(0) }

    fun hasAccess() = unlockedNow &&
        (!repository.isSessionBacked || repository.accessState.value == PrivateIndexAccessState.Ready)
    fun cancelRunning() {
        generation++
        job?.cancel(); job = null; working = false
        selected = null; confirmation = null
    }
    fun leave() { cancelRunning(); onBack() }
    fun runOperation(operation: suspend () -> Unit) {
        if (working || !hasAccess()) return
        working = true; error = null; message = null
        val operationGeneration = ++generation
        job = scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != operationGeneration) return@launch
                selected = null; confirmation = null; rows = emptyList(); loaded = false
                if (PrivateAlbumCrypto.requiresAuthentication(failure) || !hasAccess()) {
                    authenticationRequired()
                } else error = R.string.private_export_recovery_error
            } finally {
                if (generation == operationGeneration) working = false
            }
        }
    }
    suspend fun readRows() {
        val current = withContext(Dispatchers.IO) { repository.exportRecoveries() }
        currentCoroutineContext().ensureActive()
        if (!hasAccess()) return
        rows = current; loaded = true
    }
    fun refresh() {
        if (working || !hasAccess()) return
        // A review/confirmation is bound to exactly the proof the user saw, never a refreshed row.
        selected = null; confirmation = null; rows = emptyList(); loaded = false
        runOperation { readRows() }
    }
    fun perform(row: PrivateExportRecovery, action: RecoveryAction) {
        if (selected?.id != row.id || selected?.proof != row.proof || !hasAccess()) return
        runOperation {
            when (action) {
                RecoveryAction.Complete -> {
                    withContext(Dispatchers.IO) { repository.completeExport(row.id, row.proof) }
                    currentCoroutineContext().ensureActive()
                    if (hasAccess()) {
                        selected = null
                        readRows()
                        message = R.string.private_export_recovery_completed
                    }
                }
                RecoveryAction.Open -> {
                    // Even Published rows must pass the repository's current URI/digest/proof check.
                    val uri = withContext(Dispatchers.IO) { repository.completeExport(row.id, row.proof) }
                    val mime = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    } ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        row.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT),
                    ) ?: "application/octet-stream"
                    currentCoroutineContext().ensureActive()
                    if (hasAccess()) {
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.clipData = ClipData.newRawUri(openLabel, uri)
                        context.startActivity(intent)
                    }
                }
                RecoveryAction.Discard -> {
                    withContext(Dispatchers.IO) { repository.discardExport(row.id, row.proof) }
                    currentCoroutineContext().ensureActive()
                    if (hasAccess()) { selected = null; readRows(); message = R.string.private_export_recovery_removed }
                }
                RecoveryAction.Forget -> {
                    withContext(Dispatchers.IO) { repository.forgetExport(row.id, row.proof) }
                    currentCoroutineContext().ensureActive()
                    if (hasAccess()) { selected = null; readRows(); message = R.string.private_export_recovery_forgotten }
                }
            }
        }
    }

    LaunchedEffect(repository, isUnlocked) {
        if (hasAccess()) refresh()
        else {
            cancelRunning(); rows = emptyList(); loaded = false; error = null; message = null
        }
    }
    DisposableEffect(repository) { onDispose { job?.cancel() } }
    BackHandler(enabled = isUnlocked) {
        if (selected != null && !working) { selected = null; confirmation = null }
        else leave()
    }
    if (!isUnlocked) return

    Scaffold(
        modifier = Modifier.testTag("private-export-recovery-screen").semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            GalleryTopAppBar(
                title = stringResource(if (selected == null) R.string.private_export_recovery_title else R.string.private_export_recovery_review),
                onBack = {
                    if (selected != null && !working) { selected = null; confirmation = null }
                    else leave()
                },
                navigationContentDescription = stringResource(R.string.private_export_recovery_back),
                actions = {
                    TextButton(onClick = ::refresh, enabled = !working,
                        modifier = Modifier.testTag("private-export-recovery-refresh")) {
                        Text(stringResource(R.string.private_export_recovery_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.private_export_recovery_working), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = {
                        cancelRunning(); rows = emptyList(); loaded = false
                        error = R.string.private_export_recovery_cancelled
                    }, modifier = Modifier.testTag("private-export-recovery-cancel")) {
                        Text(stringResource(R.string.private_export_recovery_cancel))
                    }
                }
            }
            val selectedRow = selected
            if (selectedRow == null) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.private_export_recovery_intro)) }
                    if (initialExportFailed) item { RecoveryNotice(R.string.private_export_recovery_export_failed, true) }
                    error?.let { value -> item { RecoveryNotice(value, true) } }
                    message?.let { value -> item { RecoveryNotice(value, false) } }
                    if (loaded && rows.isEmpty()) item {
                        Text(stringResource(R.string.private_export_recovery_empty),
                            modifier = Modifier.testTag("private-export-recovery-empty"))
                    }
                    items(rows, key = { it.id }) { row ->
                        OutlinedCard(onClick = { if (!working && hasAccess()) { selected = row; confirmation = null } },
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-row-${row.id}")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(statusLabel(row.status)), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.private_export_recovery_review), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(selectedRow.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(statusLabel(selectedRow.status)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(statusBody(selectedRow.status)))
                    when (selectedRow.status) {
                        PrivateExportStatus.Ready -> Button(onClick = { perform(selectedRow, RecoveryAction.Complete) },
                            enabled = !working, modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-complete")) {
                            Text(stringResource(R.string.private_export_recovery_complete))
                        }
                        PrivateExportStatus.Published -> {
                            Button(onClick = { perform(selectedRow, RecoveryAction.Open) }, enabled = !working,
                                modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-view")) {
                                Text(stringResource(R.string.private_export_recovery_open))
                            }
                            OutlinedButton(onClick = { confirmation = selectedRow to RecoveryAction.Forget }, enabled = !working,
                                modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-close-result")) {
                                Text(stringResource(R.string.private_export_recovery_close_result))
                            }
                        }
                        PrivateExportStatus.Partial -> OutlinedButton(
                            onClick = { confirmation = selectedRow to RecoveryAction.Discard }, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-remove")) {
                            Text(stringResource(R.string.private_export_recovery_remove))
                        }
                        PrivateExportStatus.Missing, PrivateExportStatus.Conflict, PrivateExportStatus.Unknown -> OutlinedButton(
                            onClick = { confirmation = selectedRow to RecoveryAction.Forget }, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-forget")) {
                            Text(stringResource(R.string.private_export_recovery_forget))
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (row, action) ->
        AlertDialog(
            onDismissRequest = { if (!working) confirmation = null },
            title = { Text(stringResource(if (action == RecoveryAction.Discard) R.string.private_export_recovery_remove else R.string.private_export_recovery_forget)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.displayName)
                    Text(stringResource(if (action == RecoveryAction.Discard) R.string.private_export_recovery_confirm_remove else R.string.private_export_recovery_confirm_forget))
                }
            },
            confirmButton = { TextButton(onClick = { confirmation = null; perform(row, action) }, enabled = !working,
                modifier = Modifier.testTag("private-export-recovery-confirm")) { Text(stringResource(R.string.private_export_recovery_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !working) { Text(stringResource(R.string.private_export_recovery_cancel)) } },
        )
    }
}

@Composable
private fun RecoveryNotice(message: Int, failure: Boolean) {
    Surface(color = if (failure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(message), modifier = Modifier.padding(16.dp))
    }
}

private fun statusLabel(status: PrivateExportStatus): Int = when (status) {
    PrivateExportStatus.Ready -> R.string.private_export_recovery_ready
    PrivateExportStatus.Published -> R.string.private_export_recovery_published
    PrivateExportStatus.Partial -> R.string.private_export_recovery_partial
    PrivateExportStatus.Missing -> R.string.private_export_recovery_missing
    PrivateExportStatus.Conflict -> R.string.private_export_recovery_conflict
    PrivateExportStatus.Unknown -> R.string.private_export_recovery_unknown
}
private fun statusBody(status: PrivateExportStatus): Int = when (status) {
    PrivateExportStatus.Ready -> R.string.private_export_recovery_ready_body
    PrivateExportStatus.Published -> R.string.private_export_recovery_published_body
    PrivateExportStatus.Partial -> R.string.private_export_recovery_partial_body
    PrivateExportStatus.Missing -> R.string.private_export_recovery_missing_body
    PrivateExportStatus.Conflict -> R.string.private_export_recovery_conflict_body
    PrivateExportStatus.Unknown -> R.string.private_export_recovery_unknown_body
}
