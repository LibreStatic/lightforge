@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.librestatic.lightforge.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

/** A complete originals-only workflow; organization/preferences are deliberately not implied. */
@Composable fun LocalBackupContent(onBack: () -> Unit) = LocalBackupContent(null, onBack)

@Composable
fun LocalBackupContent(organizationPort: LocalBackupOrganizationPort?, onBack: () -> Unit) =
    LocalBackupContent(organizationPort, null, {}, null, onBack)

@Composable
fun LocalBackupContent(
    organizationPort: LocalBackupOrganizationPort?,
    taskController: LocalBackupTaskController?,
    onOpenTasks: () -> Unit,
    reviewTaskId: String? = null,
    onBack: () -> Unit,
    preferencesPort: com.librestatic.lightforge.core.preferences.PortablePreferencesPort? = null,
) {
    val context = LocalContext.current
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    val storage = remember(context, sessionId) { LocalBackupStorage(context, sessionId) }
    val scope = rememberCoroutineScope()
    var selected by remember(storage) { mutableStateOf(arrayListOf<String>()) }
    var preferencesOperationId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupManifest?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf(0) }
    var progress by remember { mutableStateOf<LocalBackupArchive.Progress?>(null) }
    val interrupted = remember { status == R.string.local_backup_working }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var includeOrganization by rememberSaveable { mutableStateOf(false) }
    var organizationReview by remember { mutableStateOf<LocalBackupOrganizationReview?>(null) }
    var confirmGallery by remember { mutableStateOf(false) }
    var importGlobalRules by remember { mutableStateOf(false) }
    var allowPartial by remember { mutableStateOf(false) }
    var galleryResult by remember { mutableStateOf<LocalRestoreGalleryResult?>(null) }
    var previewName by rememberSaveable { mutableStateOf<String?>(null) }
    suspend fun reviewOrganization(
        manifest: BackupManifest,
        file: File,
    ): LocalBackupOrganizationReview? {
        if (manifest.organization == null || organizationPort == null) return null
        return withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            organizationPort.review(
                LocalBackupArchive.readOrganization(file, manifest) { coroutine.ensureActive() },
                manifest,
            )
        }
    }
    DisposableEffect(storage, context) {
        onDispose {
            LocalBackupSessionCleanup.closeAfterOperation(
                job,
                context.backupActivity()?.isChangingConfigurations == true,
            ) {
                storage.close()
            }
        }
    }

    fun start(block: suspend () -> Unit) {
        busy = true
        galleryResult = null
        progress = null
        status = R.string.local_backup_working
        job =
            scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    status =
                        if (cancelled.suppressed.isEmpty()) R.string.local_backup_cancelled
                        else R.string.local_backup_cleanup_failed
                    throw cancelled
                } catch (error: Exception) {
                    status =
                        if (error.suppressed.isEmpty()) R.string.local_backup_failed
                        else R.string.local_backup_cleanup_failed
                } finally {
                    busy = false
                    job = null
                    progress = null
                }
            }
    }

    LaunchedEffect(storage) {
        start {
            selected = ArrayList(withContext(Dispatchers.IO) { storage.readSelection() })
            if (reviewTaskId != null && taskController != null) {
                val reviewed =
                    withContext(Dispatchers.IO) { taskController.reviewArchive(reviewTaskId) }
                // Session cache receives a copy: leaving review never removes the durable task.
                val copy = storage.newArchive()
                withContext(Dispatchers.IO) { reviewed.first.copyTo(copy) }
                previewPath = copy.absolutePath
                previewName = reviewed.second
            }
            val path = previewPath
            if (path != null) {
                preview =
                    withContext(Dispatchers.IO) {
                        val coroutine = currentCoroutineContext()
                        LocalBackupArchive.inspect(File(path)) { coroutine.ensureActive() }
                    }
                organizationReview = preview?.let { reviewOrganization(it, File(path)) }
                status = R.string.local_backup_verified
            } else status = if (interrupted) R.string.local_backup_interrupted else 0
        }
    }

    val chooseFiles =
        rememberLauncherForActivityResult(LocalBackupPickFiles()) { uris ->
            if (uris.isNotEmpty()) {
                if (uris.size > BackupManifest.MAX_ENTRIES) {
                    status = R.string.local_backup_too_many
                } else {
                    start {
                        val next = ArrayList(uris.distinct().map(Uri::toString))
                        withContext(Dispatchers.IO) { storage.saveSelection(next) }
                        selected = next
                        status = 0
                    }
                }
            }
        }
    val createBackup =
        rememberLauncherForActivityResult(LocalBackupCreateDocument()) { uri ->
            if (uri != null)
                start {
                    if (taskController != null) {
                        taskController.enqueueBackup(
                            selected.toList(),
                            uri,
                            "Lightforge-originals.lightforge.zip",
                            includeOrganization,
                        )
                        status = R.string.local_backup_task_queued
                        return@start
                    }
                    val paths = selected.toList()
                    val file = storage.newArchive()
                    try {
                        val manifest =
                            withContext(Dispatchers.IO) {
                                val coroutine = currentCoroutineContext()
                                val check = { coroutine.ensureActive() }
                                val sources = paths.map { storage.source(Uri.parse(it)) }
                                val result =
                                    if (includeOrganization && organizationPort != null)
                                        LocalBackupArchive.createOrganized(
                                            sources,
                                            file,
                                            organizationPort,
                                        ) { value ->
                                            scope.launch { progress = value }
                                        }
                                    else
                                        LocalBackupArchive.create(sources, file, check) { value ->
                                            scope.launch { progress = value }
                                        }
                                storage.publish(file, uri, check)
                                result
                            }
                        previewPath?.let { File(it).delete() }
                        organizationReview = reviewOrganization(manifest, file)
                        previewName =
                            withContext(Dispatchers.IO) {
                                runCatching { storage.source(uri).name }.getOrNull()
                            }
                        preview = manifest
                        previewPath = file.absolutePath
                        status = R.string.local_backup_exported
                    } catch (error: Throwable) {
                        try {
                            withContext(NonCancellable + Dispatchers.IO) {
                                storage.deleteCreatedDocument(uri)
                            }
                        } catch (cleanup: Throwable) {
                            error.addSuppressed(cleanup)
                        }
                        throw error
                    } finally {
                        if (previewPath != file.absolutePath) file.delete()
                    }
                }
        }
    val openBackup =
        rememberLauncherForActivityResult(LocalBackupOpenDocument()) { uri ->
            if (uri != null)
                start {
                    val file = storage.newArchive()
                    try {
                        val manifest =
                            withContext(Dispatchers.IO) {
                                val coroutine = currentCoroutineContext()
                                storage.importArchive(uri, file) { coroutine.ensureActive() }
                            }
                        previewPath?.let { File(it).delete() }
                        organizationReview = reviewOrganization(manifest, file)
                        previewName =
                            withContext(Dispatchers.IO) {
                                runCatching { storage.source(uri).name }.getOrNull()
                            }
                        preview = manifest
                        previewPath = file.absolutePath
                        status = R.string.local_backup_verified
                    } finally {
                        if (previewPath != file.absolutePath) file.delete()
                    }
                }
        }
    val chooseRestoreFolder =
        rememberLauncherForActivityResult(LocalBackupOpenTree()) { uri ->
            val manifest = preview
            val path = previewPath
            if (uri != null && manifest != null && path != null)
                start {
                    if (taskController != null) {
                        taskController.enqueueRestore(
                            File(path),
                            previewName ?: "Lightforge",
                            uri,
                            false,
                            LocalRestoreOrganizationOptions(),
                            manifest,
                        )
                        previewPath = null
                        preview = null
                        organizationReview = null
                        status = R.string.local_backup_task_queued
                        return@start
                    }
                    var target: LocalBackupStorage.LocalRestoreFolder? = null
                    try {
                        withContext(Dispatchers.IO) {
                            val coroutine = currentCoroutineContext()
                            val check = { coroutine.ensureActive() }
                            val destination = storage.restoreTarget(uri, manifest, check)
                            target = destination
                            LocalBackupArchive.restore(File(path), manifest, destination, check) {
                                value ->
                                scope.launch { progress = value }
                            }
                        }
                        status = R.string.local_backup_restored
                    } catch (error: Throwable) {
                        try {
                            withContext(NonCancellable + Dispatchers.IO) { target?.abort() }
                        } catch (cleanup: Throwable) {
                            error.addSuppressed(cleanup)
                        }
                        throw error
                    }
                }
        }
    if (preferencesOperationId != null && preferencesPort != null && organizationPort != null) {
        LocalBackupPreferencesScreen(
            preferencesPort = preferencesPort,
            organizationPort = organizationPort,
            archivePath = previewPath,
            manifest = preview,
            operationId = requireNotNull(preferencesOperationId),
            onBack = { preferencesOperationId = null },
        )
        return
    }
    BackHandler { if (busy) confirmCancel = true else onBack() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxSize()
                .testTag("local-backup-screen")
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = { if (busy) confirmCancel = true else onBack() }) {
                    Text(stringResource(R.string.local_backup_back))
                }
                Text(
                    stringResource(R.string.local_backup_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(
                        if (organizationPort == null) R.string.local_backup_scope
                        else R.string.local_backup_organization_scope
                    )
                )
                Text(stringResource(R.string.local_backup_local_hint))
                if (taskController != null) {
                    Text(stringResource(R.string.local_backup_task_durable_hint))
                    OutlinedButton(
                        onClick = onOpenTasks,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("local-backup-tasks-open"),
                    ) {
                        Text(stringResource(R.string.local_backup_tasks_title))
                    }
                }
                if (interrupted) Text(stringResource(R.string.local_backup_interrupted))
                OutlinedButton(
                    onClick = { chooseFiles.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-backup-select"),
                ) {
                    Text(stringResource(R.string.local_backup_select))
                }
                Text(stringResource(R.string.local_backup_selected, selected.size))
                if (organizationPort != null)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeOrganization,
                            onCheckedChange = { includeOrganization = it },
                            enabled = !busy,
                            modifier = Modifier.testTag("local-backup-include-organization"),
                        )
                        Text(stringResource(R.string.local_backup_include_organization))
                    }
                Button(
                    onClick = { createBackup.launch("Lightforge-originals.lightforge.zip") },
                    enabled = !busy && selected.size in 1..BackupManifest.MAX_ENTRIES,
                    modifier = Modifier.fillMaxWidth().testTag("local-backup-create"),
                ) {
                    Text(stringResource(R.string.local_backup_create))
                }
                OutlinedButton(
                    onClick = {
                        openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-backup-open"),
                ) {
                    Text(stringResource(R.string.local_backup_open))
                }
                if (busy) {
                    GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                    progress?.let {
                        Text(localBackupProgressText(it.completed, it.total, it.bytes))
                    }
                    TextButton(
                        onClick = { confirmCancel = true },
                        modifier = Modifier.testTag("local-backup-cancel"),
                    ) {
                        Text(stringResource(R.string.local_backup_cancel))
                    }
                }
                if (status != 0)
                    Text(stringResource(status), modifier = Modifier.testTag("local-backup-status"))
                preview?.let { manifest ->
                    Text(
                        stringResource(R.string.local_backup_review_version, manifest.version),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    previewName?.let { Text(it) }
                    Text(
                        stringResource(
                            R.string.local_backup_summary,
                            pluralStringResource(R.plurals.local_backup_originals, manifest.entries.size, manifest.entries.size),
                            localBackupBytesText(manifest.totalBytes),
                        )
                    )
                    manifest.entries.take(20).forEach { Text(it.name) }
                    if (manifest.entries.size > 20)
                        Text(stringResource(R.string.local_backup_more, manifest.entries.size - 20))
                    if (manifest.organization != null) {
                        val review = organizationReview
                        if (review == null)
                            Text(stringResource(R.string.local_backup_organization_unavailable))
                        else {
                            Text(
                                stringResource(R.string.local_backup_organization_review),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                stringResource(
                                    R.string.local_backup_organization_sources,
                                    review.sourceCount,
                                    review.totalSources,
                                    review.omitted,
                                    review.unresolved,
                                )
                            )
                            review.counts.forEach { item ->
                                Text(
                                    stringResource(
                                        R.string.local_backup_organization_count,
                                        organizationKindLabel(item.kind),
                                        item.count,
                                    )
                                )
                            }
                            if (review.globalRuleCount > 0)
                                Text(
                                    stringResource(
                                        R.string.local_backup_global_impact,
                                        review.globalRuleCount,
                                    )
                                )
                            if (preferencesPort == null && (review.hasPreferences || review.hasAutomaticRules))
                                Text(stringResource(R.string.local_backup_settings_review_only))
                            else if (review.hasAutomaticRules)
                                Text(stringResource(R.string.local_backup_automatic_rules_review_only))
                            if (review.hasPreferences && preferencesPort != null) {
                                OutlinedButton(
                                    onClick = { preferencesOperationId = UUID.randomUUID().toString() },
                                    enabled = !busy && previewPath != null,
                                    modifier = Modifier.fillMaxWidth().testTag("local-backup-preferences-review"),
                                ) { Text(stringResource(R.string.portable_preferences_title)) }
                            }
                            Text(stringResource(R.string.local_backup_gallery_hint))
                            Button(
                                onClick = {
                                    importGlobalRules = false
                                    allowPartial = false
                                    confirmGallery = true
                                },
                                enabled = !busy && review.canRestore,
                                modifier = Modifier.fillMaxWidth().testTag("local-backup-gallery"),
                            ) {
                                Text(stringResource(R.string.local_backup_gallery_restore))
                            }
                        }
                    }
                    galleryResult?.let {
                        Text(
                            stringResource(
                                R.string.local_backup_gallery_result,
                                it.files,
                                it.importedObjects,
                                it.skippedObjects,
                            )
                        )
                    }
                    Text(stringResource(R.string.local_backup_restore_hint))
                    Button(
                        onClick = { confirmRestore = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("local-backup-restore"),
                    ) {
                        Text(stringResource(R.string.local_backup_restore))
                    }
                }
            }
        }
    }
    if (confirmRestore)
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.local_backup_restore)) },
            text = { Text(stringResource(R.string.local_backup_restore_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        chooseRestoreFolder.launch(null)
                    }
                ) {
                    Text(stringResource(R.string.local_backup_choose_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.local_backup_cancel))
                }
            },
        )
    if (confirmGallery)
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirmGallery = false },
            title = { Text(stringResource(R.string.local_backup_gallery_restore)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.local_backup_gallery_hint))
                    if (organizationReview?.partial == true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowPartial,
                                onCheckedChange = { allowPartial = it },
                                modifier = Modifier.testTag("local-backup-partial"),
                            )
                            Text(stringResource(R.string.local_backup_partial_opt_in))
                        }
                    if ((organizationReview?.globalRuleCount ?: 0) > 0) {
                        Text(
                            stringResource(
                                R.string.local_backup_global_impact,
                                organizationReview?.globalRuleCount ?: 0,
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = importGlobalRules,
                                onCheckedChange = { importGlobalRules = it },
                                modifier = Modifier.testTag("local-backup-global-rules"),
                            )
                            Text(stringResource(R.string.local_backup_global_opt_in))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmGallery = false
                        val manifest = preview
                        val path = previewPath
                        val port = organizationPort
                        if (manifest != null && path != null && port != null)
                            start {
                                if (taskController != null) {
                                    taskController.enqueueRestore(
                                        File(path),
                                        previewName ?: "Lightforge",
                                        null,
                                        true,
                                        LocalRestoreOrganizationOptions(
                                            importGlobalRules,
                                            allowPartial,
                                        ),
                                        manifest,
                                    )
                                    previewPath = null
                                    preview = null
                                    organizationReview = null
                                    status = R.string.local_backup_task_queued
                                    return@start
                                }
                                galleryResult =
                                    LocalBackupArchive.restoreGallery(
                                        File(path),
                                        manifest,
                                        port,
                                        LocalRestoreOrganizationOptions(
                                            importGlobalRules,
                                            allowPartial,
                                        ),
                                    ) { value ->
                                        scope.launch { progress = value }
                                    }
                                status = R.string.local_backup_gallery_done
                            }
                    },
                    enabled = organizationReview?.partial != true || allowPartial,
                    modifier = Modifier.testTag("local-backup-gallery-confirm"),
                ) {
                    Text(stringResource(R.string.local_backup_gallery_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmGallery = false }) {
                    Text(stringResource(R.string.local_backup_cancel))
                }
            },
        )
    if (confirmCancel)
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.local_backup_cancel)) },
            text = { Text(stringResource(R.string.local_backup_cancel_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        job?.cancel()
                    },
                    modifier = Modifier.testTag("local-backup-cancel-confirm"),
                ) {
                    Text(stringResource(R.string.local_backup_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.local_backup_continue))
                }
            },
        )
}

private class LocalBackupPickFiles : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class LocalBackupCreateDocument :
    ActivityResultContracts.CreateDocument("application/zip") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal class LocalBackupOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal class LocalBackupOpenTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private fun Context.backupActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.backupActivity()
        else -> null
    }

@Composable
private fun organizationKindLabel(kind: LocalBackupOrganizationKind): String =
    stringResource(
        when (kind) {
            LocalBackupOrganizationKind.PhotoEdits -> R.string.local_backup_kind_photo_edits
            LocalBackupOrganizationKind.Albums -> R.string.local_backup_kind_albums
            LocalBackupOrganizationKind.Memories -> R.string.local_backup_kind_memories
            LocalBackupOrganizationKind.Stacks -> R.string.local_backup_kind_stacks
            LocalBackupOrganizationKind.SmartAlbums -> R.string.local_backup_kind_smart
            LocalBackupOrganizationKind.Documents -> R.string.local_backup_kind_documents
            LocalBackupOrganizationKind.Archived -> R.string.local_backup_kind_archived
            LocalBackupOrganizationKind.DateRules -> R.string.local_backup_kind_dates
            LocalBackupOrganizationKind.PersonRules -> R.string.local_backup_kind_people
            LocalBackupOrganizationKind.Favorites -> R.string.local_backup_kind_favorites
            LocalBackupOrganizationKind.Preferences -> R.string.local_backup_kind_preferences
            LocalBackupOrganizationKind.AutomaticRules -> R.string.local_backup_kind_automatic
        }
    )
