@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.librestatic.lightforge.feature.remotebackup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.remotestorage.*
import com.librestatic.lightforge.feature.settings.LocalRestoreOrganizationOptions
import kotlinx.coroutines.*
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar

/** A complete own-server archive workflow. No secret is put into saved state. */
@Composable
fun RemoteBackupContent(
    controller: RemoteBackupController,
    onBack: () -> Unit,
    onOpenLocalTask: (String) -> Unit = {},
) {
    val profiles by controller.profiles.collectAsState()
    val tasks by controller.tasks.collectAsState()
    val probes by controller.probes.collectAsState()
    val profileFailure by controller.profileFailure.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = profiles.singleOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    var editor by remember { mutableStateOf(false) }
    var credentialsOnly by remember { mutableStateOf<RemoteProfile?>(null) }
    var sources by remember { mutableStateOf<List<String>>(emptyList()) }
    var organization by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var activeAction by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<RemoteFailure?>(null) }
    var genericError by remember { mutableStateOf(false) }
    var remoteEntries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var reviewed by remember { mutableStateOf<Pair<String, RemoteArchiveReview>?>(null) }
    var allowPartial by remember { mutableStateOf(false) }
    var importGlobal by remember { mutableStateOf(false) }
    var cancelId by remember { mutableStateOf<String?>(null) }
    var trust by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var regrantSourceTask by remember { mutableStateOf<String?>(null) }
    var regrantFolderTask by remember { mutableStateOf<String?>(null) }
    var folderTask by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        genericError = false
        activeAction =
            scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RemoteStorageException) {
                    error = failure.failure
                } catch (_: Exception) {
                    genericError = true
                } finally {
                    busy = false
                    activeAction = null
                }
            }
    }
    val picker =
        rememberLauncherForActivityResult(RemoteDocumentPicker(multiple = true)) { values ->
            val regrant = regrantSourceTask
            regrantSourceTask = null
            if (regrant != null && values.isNotEmpty())
                action { controller.regrantSources(regrant, values) }
            else if (
                values.size in 1..10000 &&
                    values.sumOf { it.toString().length.toLong() } <= 4L * 1024 * 1024
            )
                sources = values.map(Uri::toString)
            else if (values.isNotEmpty()) genericError = true
        }
    val folderPicker =
        rememberLauncherForActivityResult(RemoteTreePicker()) { uri ->
            val id = folderTask
            val regrant = regrantFolderTask
            folderTask = null
            regrantFolderTask = null
            if (uri != null && regrant != null)
                action { controller.regrantDestination(regrant, uri) }
            else if (uri != null && id != null)
                action {
                    controller.restoreReviewed(id, false, uri, LocalRestoreOrganizationOptions())
                    reviewed = null
                }
        }
    BackHandler(onBack = onBack)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
        GalleryTopAppBar(
            title = stringResource(R.string.remote_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.remote_back),
            // The shell scaffold already placed this route below the status bar.
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("remote-backup-screen"),
            contentPadding = readableListPadding(maxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.remote_scope)) }
            if (profileFailure) item { Text(stringResource(R.string.remote_corrupt)) }
            if (genericError || error != null)
                item {
                    Text(
                        stringResource(
                            when (error) {
                                RemoteFailure.UNSUPPORTED -> R.string.remote_unsupported
                                RemoteFailure.INVALID_PATH -> R.string.remote_error_invalid_path
                                else -> R.string.remote_error
                            }
                        ),
                        modifier = Modifier.testTag("remote-error"),
                    )
                }
            if (busy)
                item {
                    GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { activeAction?.cancel() }) {
                        Text(stringResource(R.string.remote_stop))
                    }
                }
            item {
                Text(
                    stringResource(R.string.remote_profiles),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedButton(
                    onClick = {
                        credentialsOnly = null
                        editor = true
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.remote_add))
                }
            }
            items(profiles, key = { "profile-${it.id}" }) { profile ->
                OutlinedButton(
                    onClick = {
                        selectedId = profile.id
                        remoteEntries = null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("remote-profile-${profile.id}"),
                    enabled = !busy,
                ) {
                    Text(
                        (if (selected?.id == profile.id) "✓ " else "") +
                            profile.name +
                            " · " +
                            profile.protocol.name +
                            " · " +
                            profile.host
                    )
                }
            }
            selected?.let { profile ->
                item {
                    Text(
                        profile.protocol.name +
                            ": " +
                            profile.username +
                            "@" +
                            profile.host +
                            ":" +
                            profile.port +
                            " / " +
                            profile.share +
                            profile.root
                    )
                    OutlinedButton(
                        onClick = {
                            credentialsOnly = profile
                            editor = true
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.remote_credentials))
                    }
                    Button(
                        onClick = { action { controller.testConnection(profile.id) } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("remote-test"),
                    ) {
                        Text(stringResource(R.string.remote_test))
                    }
                    val probe = probes.singleOrNull { it.profileId == profile.id }
                    probe?.identity?.let {
                        Text(it, modifier = Modifier.testTag("remote-connection-identity"))
                    }
                    if (probe?.encrypted != null && probe.failure == null)
                        Text(
                            stringResource(
                                R.string.remote_capabilities,
                                stringResource(
                                    if (probe.encrypted == true) R.string.remote_yes
                                    else R.string.remote_no
                                ),
                                stringResource(
                                    if (probe.signed == true) R.string.remote_yes
                                    else R.string.remote_no
                                ),
                                stringResource(
                                    if (probe.atomicPublish == true) R.string.remote_yes
                                    else R.string.remote_no
                                ),
                            )
                        )
                    probe?.observedHostKey?.let { key ->
                        if (
                            probe.failure in
                                setOf(
                                    RemoteFailure.IDENTITY_REQUIRED.name,
                                    RemoteFailure.IDENTITY_CHANGED.name,
                                )
                        ) {
                            OutlinedButton(
                                onClick = { trust = Triple(profile.id, key, false) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.remote_trust))
                            }
                        }
                    }
                    if (!probe?.residuals.isNullOrEmpty())
                        Text(
                            stringResource(
                                R.string.remote_residuals,
                                probe!!.residuals.joinToString("\n"),
                            )
                        )
                    OutlinedButton(
                        onClick = { picker.launch(Unit) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("remote-select-sources"),
                    ) {
                        Text(stringResource(R.string.remote_sources))
                    }
                    Text(pluralStringResource(R.plurals.remote_selected, sources.size, sources.size))
                    if (controller.organizationAvailable)
                        LabelledCheck(
                            organization,
                            { organization = it },
                            stringResource(R.string.remote_organization),
                        )
                    Button(
                        onClick = {
                            action {
                                controller.enqueueBackup(profile.id, sources, organization)
                                sources = emptyList()
                            }
                        },
                        enabled = !busy && sources.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("remote-prepare"),
                    ) {
                        Text(stringResource(R.string.remote_prepare))
                    }
                    OutlinedButton(
                        onClick = {
                            action { remoteEntries = controller.listArchives(profile.id) }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("remote-list"),
                    ) {
                        Text(stringResource(R.string.remote_list))
                    }
                }
                if (remoteEntries?.isEmpty() == true)
                    item { Text(stringResource(R.string.remote_empty)) }
                items(remoteEntries.orEmpty(), key = { "remote-${it.name}" }) { entry ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(entry.name)
                            Text(Formatter.formatShortFileSize(context, entry.size))
                            Button(
                                onClick = {
                                    action { controller.enqueueDownload(profile.id, entry) }
                                },
                                enabled = !busy,
                                modifier = Modifier.testTag("remote-download-${entry.name}"),
                            ) {
                                Text(stringResource(R.string.remote_download))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.remote_history),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.remote_resume_hint))
                if (tasks.isEmpty()) Text(stringResource(R.string.remote_history_empty))
            }
            items(tasks, key = { "task-${it.id}" }) { task ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp).testTag("remote-task-${task.id}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            task.name.ifBlank { stringResource(R.string.remote_corrupt) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            task.profile.name +
                                " · " +
                                task.profile.host +
                                " / " +
                                task.profile.share +
                                task.profile.root
                        )
                        Text(
                            stringResource(task.status.resource()),
                            modifier = Modifier.testTag("remote-task-status-${task.id}"),
                        )
                        if (task.totalBytes > 0) {
                            GalleryProgressIndicator(
                                progress = {
                                    (task.bytesDone.toFloat() / task.totalBytes).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.remote_transfer_progress,
                                    Formatter.formatShortFileSize(context, task.bytesDone),
                                    Formatter.formatShortFileSize(context, task.totalBytes),
                                )
                            )
                        }
                        task.archiveSha?.let { Text(stringResource(R.string.remote_archive_checksum, it)) }
                        if (task.failure == RemoteFailure.UNSUPPORTED.name)
                            Text(stringResource(R.string.remote_unsupported))
                        if (task.failure == RemoteFailure.INVALID_PATH.name)
                            Text(stringResource(R.string.remote_error_invalid_path))
                        if (task.residuals.isNotEmpty())
                            Text(
                                stringResource(
                                    R.string.remote_residuals,
                                    task.residuals.joinToString("\n"),
                                )
                            )
                        if (
                            task.status in
                                setOf(
                                    RemoteBackupStatus.AwaitingUploadReview,
                                    RemoteBackupStatus.AwaitingRestoreReview,
                                )
                        ) {
                            Button(
                                onClick = {
                                    action {
                                        reviewed = task.id to controller.review(task.id)
                                        allowPartial = false
                                        importGlobal = false
                                    }
                                },
                                enabled = !busy,
                                modifier =
                                    Modifier.fillMaxWidth().testTag("remote-review-${task.id}"),
                            ) {
                                Text(stringResource(R.string.remote_review))
                            }
                        }
                        if (
                            task.status in
                                setOf(RemoteBackupStatus.Queued, RemoteBackupStatus.Running)
                        )
                            OutlinedButton(
                                onClick = { action { controller.pause(task.id) } },
                                enabled = !busy,
                                modifier = Modifier.testTag("remote-pause-${task.id}"),
                            ) {
                                Text(stringResource(R.string.remote_pause))
                            }
                        if (
                            task.status in
                                setOf(
                                    RemoteBackupStatus.Paused,
                                    RemoteBackupStatus.WaitingConnection,
                                    RemoteBackupStatus.WaitingCredentials,
                                    RemoteBackupStatus.WaitingPermission,
                                    RemoteBackupStatus.Failed,
                                )
                        )
                            OutlinedButton(
                                onClick = { action { controller.resume(task.id) } },
                                enabled = !busy,
                                modifier = Modifier.testTag("remote-resume-${task.id}"),
                            ) {
                                Text(stringResource(R.string.remote_resume))
                            }
                        if (
                            task.status == RemoteBackupStatus.WaitingIdentity &&
                                task.observedHostKey != null
                        )
                            OutlinedButton(
                                onClick = { trust = Triple(task.id, task.observedHostKey, true) },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.remote_trust))
                            }
                        if (
                            task.status == RemoteBackupStatus.WaitingPermission &&
                                task.phase == RemoteBackupPhase.Preparing
                        ) {
                            OutlinedButton(
                                onClick = {
                                    regrantSourceTask = task.id
                                    picker.launch(Unit)
                                },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.remote_regrant_sources))
                            }
                        }
                        if (
                            task.status == RemoteBackupStatus.WaitingPermission &&
                                task.phase == RemoteBackupPhase.RestoreHandoff &&
                                task.restoreDestination != null
                        ) {
                            OutlinedButton(
                                onClick = {
                                    regrantFolderTask = task.id
                                    folderPicker.launch(Unit)
                                },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.remote_regrant_folder))
                            }
                        }
                        if (!task.terminal && task.failure != "corrupt")
                            TextButton(
                                onClick = { cancelId = task.id },
                                enabled = !busy,
                                modifier = Modifier.testTag("remote-cancel-${task.id}"),
                            ) {
                                Text(stringResource(R.string.remote_cancel))
                            }
                        task.localTaskId?.let { id ->
                            OutlinedButton(onClick = { onOpenLocalTask(id) }) {
                                Text(stringResource(R.string.remote_local_task))
                            }
                        }
                    }
                }
            }
        }
        }
        }
    }
    if (editor)
        RemoteProfileEditor(credentialsOnly, onDismiss = { editor = false }) { profile, credentials
            ->
            action {
                if (credentialsOnly != null) controller.updateCredentials(profile.id, credentials)
                else controller.saveProfile(profile, credentials)
                selectedId = profile.id
                editor = false
                credentialsOnly = null
            }
        }
    trust?.let { request ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { trust = null },
            title = { Text(stringResource(R.string.remote_trust)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.remote_trust_hint))
                    Text(
                        if (request.third)
                            tasks.singleOrNull { it.id == request.first }?.profile?.host.orEmpty()
                        else profiles.singleOrNull { it.id == request.first }?.host.orEmpty()
                    )
                    Text(request.second)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        trust = null
                        action {
                            if (request.third) controller.trustTask(request.first, request.second)
                            else controller.trustProfile(request.first, request.second)
                        }
                    }
                ) {
                    Text(stringResource(R.string.remote_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { trust = null }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }
    cancelId?.let { id ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { cancelId = null },
            title = { Text(stringResource(R.string.remote_cancel)) },
            text = { Text(stringResource(R.string.remote_cancel_hint)) },
            confirmButton = {
                Button(
                    onClick = {
                        cancelId = null
                        action { controller.cancel(id) }
                    }
                ) {
                    Text(stringResource(R.string.remote_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelId = null }) {
                    Text(stringResource(R.string.remote_back))
                }
            },
        )
    }
    reviewed?.let { (id, review) ->
        val task = tasks.singleOrNull { it.id == id }
        val upload = task?.status == RemoteBackupStatus.AwaitingUploadReview
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { reviewed = null },
            title = { Text(stringResource(R.string.remote_review)) },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(task?.name.orEmpty())
                        Text(task?.profile?.let { it.host + " / " + it.share + it.root }.orEmpty())
                        Text(
                            pluralStringResource(
                                R.plurals.remote_archive_summary,
                                review.manifest.entries.size,
                                review.manifest.version,
                                review.manifest.entries.size,
                                Formatter.formatShortFileSize(context, review.manifest.totalBytes),
                            )
                        )
                        Text(stringResource(R.string.remote_restore_scope))
                        review.organization?.let { org ->
                            Text(
                                stringResource(
                                    R.string.remote_org_summary,
                                    org.sourceCount,
                                    org.totalSources,
                                    org.omitted,
                                    org.unresolved,
                                )
                            )
                            if (!upload && org.partial)
                                LabelledCheck(
                                    allowPartial,
                                    { allowPartial = it },
                                    stringResource(R.string.remote_partial),
                                )
                            if (!upload && org.globalRuleCount > 0)
                                LabelledCheck(
                                    importGlobal,
                                    { importGlobal = it },
                                    stringResource(R.string.remote_global),
                                )
                        }
                    }
                    items(review.manifest.entries, key = { it.path }) { entry ->
                        Text(
                            entry.name +
                                " · " +
                                entry.mime +
                                " · " +
                                Formatter.formatShortFileSize(context, entry.bytes)
                        )
                    }
                }
            },
            confirmButton = {
                Column {
                    if (upload)
                        Button(
                            onClick = {
                                action {
                                    controller.confirmUpload(id)
                                    reviewed = null
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.remote_upload))
                        }
                    else {
                        Button(
                            onClick = {
                                action {
                                    controller.restoreReviewed(
                                        id,
                                        true,
                                        null,
                                        LocalRestoreOrganizationOptions(importGlobal, allowPartial),
                                    )
                                    reviewed = null
                                }
                            },
                            enabled =
                                !busy &&
                                    review.organization?.canRestore == true &&
                                    (review.organization.partial.not() || allowPartial),
                        ) {
                            Text(stringResource(R.string.remote_gallery))
                        }
                        OutlinedButton(
                            onClick = {
                                folderTask = id
                                folderPicker.launch(Unit)
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.remote_restore_folder))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewed = null }) {
                    Text(stringResource(R.string.remote_back))
                }
            },
        )
    }
}

@Composable
private fun LabelledCheck(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(checked, onChange)
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
private fun RemoteProfileEditor(
    existing: RemoteProfile?,
    onDismiss: () -> Unit,
    onSave: (RemoteProfile, RemoteCredentials) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: RemoteProtocol.SFTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    // The SFTP connector needs an absolute folder; SMB folders are relative to the share.
    var root by remember { mutableStateOf(if (protocol == RemoteProtocol.SFTP) "/" else "") }
    var share by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var keyAuth by remember { mutableStateOf(existing?.authKind == RemoteAuthKind.PRIVATE_KEY) }
    var secret by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf(false) }
    var fieldErrors by remember { mutableStateOf(emptyMap<RemoteField, Int>()) }
    DisposableEffect(Unit) {
        onDispose {
            key?.fill(0)
            secret = ""
        }
    }
    val picker =
        rememberLauncherForActivityResult(RemoteDocumentPicker(multiple = false, anyType = true)) {
            values ->
            values.firstOrNull()?.let { uri ->
                scope.launch {
                    try {
                        val bytes =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)!!.use {
                                    RemoteBackupIO.readBounded(it, 1024 * 1024)
                                }
                            }
                        if (bytes.size !in 1..1024 * 1024) {
                            bytes.fill(0)
                            error = true
                        } else {
                            key?.fill(0)
                            key = bytes
                            error = false
                            fieldErrors = fieldErrors - RemoteField.KEY
                        }
                    } catch (_: Exception) {
                        error = true
                    }
                }
            }
        }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.remote_add else R.string.remote_credentials
                )
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (existing == null) {
                    OutlinedTextField(
                        name,
                        {
                            name = it.take(120)
                            fieldErrors = fieldErrors - RemoteField.NAME
                        },
                        label = { Text(stringResource(R.string.remote_name)) },
                        isError = fieldErrors.containsKey(RemoteField.NAME),
                        supportingText =
                            fieldErrors[RemoteField.NAME]?.let { message ->
                                { Text(stringResource(message)) }
                            },
                        singleLine = true,
                        modifier = Modifier.testTag("remote-profile-name"),
                    )
                    Row {
                        RemoteProtocol.entries.forEach { value ->
                            TextButton(
                                onClick = {
                                    protocol = value
                                    fieldErrors = emptyMap()
                                    port = if (value == RemoteProtocol.SFTP) "22" else "445"
                                    if (value == RemoteProtocol.SFTP && root.isEmpty()) root = "/"
                                    if (value == RemoteProtocol.SMB && root == "/") root = ""
                                    if (value == RemoteProtocol.SMB) keyAuth = false
                                }
                            ) {
                                Text((if (protocol == value) "✓ " else "") + value.name)
                            }
                        }
                    }
                    OutlinedTextField(
                        host,
                        {
                            host = it.take(253)
                            fieldErrors = fieldErrors - RemoteField.HOST
                        },
                        label = { Text(stringResource(R.string.remote_host)) },
                        isError = fieldErrors.containsKey(RemoteField.HOST),
                        supportingText =
                            fieldErrors[RemoteField.HOST]?.let { message ->
                                { Text(stringResource(message)) }
                            },
                        singleLine = true,
                        modifier = Modifier.testTag("remote-profile-host"),
                    )
                    OutlinedTextField(
                        port,
                        {
                            port = it.take(5)
                            fieldErrors = fieldErrors - RemoteField.PORT
                        },
                        label = { Text(stringResource(R.string.remote_port)) },
                        isError = fieldErrors.containsKey(RemoteField.PORT),
                        supportingText =
                            fieldErrors[RemoteField.PORT]?.let { message ->
                                { Text(stringResource(message)) }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.testTag("remote-profile-port"),
                    )
                    OutlinedTextField(
                        username,
                        {
                            username = it.take(256)
                            fieldErrors = fieldErrors - RemoteField.USERNAME
                        },
                        label = { Text(stringResource(R.string.remote_user)) },
                        isError = fieldErrors.containsKey(RemoteField.USERNAME),
                        supportingText =
                            fieldErrors[RemoteField.USERNAME]?.let { message ->
                                { Text(stringResource(message)) }
                            },
                        singleLine = true,
                        modifier = Modifier.testTag("remote-profile-user"),
                    )
                    if (protocol == RemoteProtocol.SMB) {
                        OutlinedTextField(
                            share,
                            {
                                share = it.take(240)
                                fieldErrors = fieldErrors - RemoteField.SHARE
                            },
                            label = { Text(stringResource(R.string.remote_share)) },
                            isError = fieldErrors.containsKey(RemoteField.SHARE),
                            supportingText =
                                fieldErrors[RemoteField.SHARE]?.let { message ->
                                    { Text(stringResource(message)) }
                                },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            domain,
                            { domain = it.take(256) },
                            label = { Text(stringResource(R.string.remote_domain)) },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        root,
                        {
                            root = it.take(4096)
                            fieldErrors = fieldErrors - RemoteField.ROOT
                        },
                        label = { Text(stringResource(R.string.remote_folder)) },
                        isError = fieldErrors.containsKey(RemoteField.ROOT),
                        supportingText =
                            fieldErrors[RemoteField.ROOT]?.let { message ->
                                { Text(stringResource(message)) }
                            },
                        singleLine = true,
                        modifier = Modifier.testTag("remote-profile-folder"),
                    )
                    if (protocol == RemoteProtocol.SFTP)
                        LabelledCheck(
                            keyAuth,
                            { keyAuth = it },
                            stringResource(R.string.remote_key),
                        )
                } else Text(existing.name + " · " + existing.host)
                if (keyAuth) {
                    Text(stringResource(R.string.remote_key_hint))
                    OutlinedButton(onClick = { picker.launch(Unit) }) {
                        Text(stringResource(R.string.remote_key_pick))
                    }
                    if (key != null) Text(stringResource(R.string.remote_key_loaded))
                    fieldErrors[RemoteField.KEY]?.let { message ->
                        Text(
                            stringResource(message),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("remote-profile-key-error"),
                        )
                    }
                }
                OutlinedTextField(
                    secret,
                    { secret = it.take(4096) },
                    label = {
                        Text(
                            stringResource(
                                if (keyAuth) R.string.remote_passphrase
                                else R.string.remote_password
                            )
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.testTag("remote-secret"),
                )
                if (error) Text(stringResource(R.string.remote_error))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (existing == null) {
                        val problems =
                            validateRemoteDraft(
                                name = name,
                                protocol = protocol,
                                host = host,
                                port = port,
                                username = username,
                                root = root,
                                share = share,
                                keyAuth = keyAuth,
                                hasKey = key != null,
                            )
                        fieldErrors = problems
                        if (problems.isNotEmpty()) return@Button
                    } else if (keyAuth && key == null) {
                        fieldErrors = mapOf(RemoteField.KEY to R.string.remote_error_key_required)
                        return@Button
                    }
                    try {
                        val profile =
                            existing
                                ?: RemoteProfile(
                                    name = name,
                                    protocol = protocol,
                                    host = host.trim(),
                                    port = port.trim().toInt(),
                                    username = username,
                                    root = root,
                                    share = share,
                                    domain = domain,
                                    authKind =
                                        if (keyAuth) RemoteAuthKind.PRIVATE_KEY
                                        else RemoteAuthKind.PASSWORD,
                                )
                        profile.validate()
                        val credentials =
                            if (keyAuth)
                                RemoteCredentials.PrivateKey(
                                    requireNotNull(key).copyOf(),
                                    secret.toCharArray(),
                                )
                            else RemoteCredentials.Password(secret.toCharArray())
                        onSave(profile, credentials)
                        secret = ""
                        key?.fill(0)
                        key = null
                    } catch (_: Exception) {
                        error = true
                    }
                },
            ) {
                Text(stringResource(R.string.remote_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) }
        },
    )
}

internal class RemoteDocumentPicker(
    private val multiple: Boolean,
    private val anyType: Boolean = false,
) : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .apply {
                if (!anyType)
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("image/*", "video/*", "application/pdf"),
                    )
            }
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        return if (clip != null) List(clip.itemCount.coerceAtMost(10001)) { clip.getItemAt(it).uri }
        else listOfNotNull(intent.data)
    }
}

internal class RemoteTreePicker : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun RemoteBackupStatus.resource(): Int =
    when (this) {
        RemoteBackupStatus.Queued -> R.string.remote_status_queued
        RemoteBackupStatus.Running -> R.string.remote_status_running
        RemoteBackupStatus.Paused -> R.string.remote_status_paused
        RemoteBackupStatus.AwaitingUploadReview -> R.string.remote_status_awaitinguploadreview
        RemoteBackupStatus.AwaitingRestoreReview -> R.string.remote_status_awaitingrestorereview
        RemoteBackupStatus.WaitingConnection -> R.string.remote_status_waitingconnection
        RemoteBackupStatus.WaitingCredentials -> R.string.remote_status_waitingcredentials
        RemoteBackupStatus.WaitingPermission -> R.string.remote_status_waitingpermission
        RemoteBackupStatus.WaitingIdentity -> R.string.remote_status_waitingidentity
        RemoteBackupStatus.NeedsReview -> R.string.remote_status_needsreview
        RemoteBackupStatus.Failed -> R.string.remote_status_failed
        RemoteBackupStatus.Cancelled -> R.string.remote_status_cancelled
        RemoteBackupStatus.Completed -> R.string.remote_status_completed
        RemoteBackupStatus.RestoringLocally -> R.string.remote_status_restoringlocally
    }
