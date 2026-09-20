@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ugallery.feature.ownsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OwnSyncContent(
    controller: OwnSyncController,
    onBack: () -> Unit,
    onManageServers: () -> Unit = {},
) {
    val jobs by controller.jobs.collectAsState()
    val runs by controller.runs.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var reviewId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var grantRun by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = true
            } finally {
                working = false
            }
        }
    }
    val regrant =
        rememberLauncherForActivityResult(OwnSyncTreePicker()) { uri ->
            val id = grantRun
            grantRun = null
            if (uri != null && id != null) action { controller.regrant(id, uri) }
        }
    LaunchedEffect(controller) { controller.reconcile() }
    Surface(
        modifier =
            Modifier.fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("own-sync-screen"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp).testTag("own-sync-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.own_sync_back)) }
                Text(
                    stringResource(R.string.own_sync_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(stringResource(R.string.own_sync_scope))
                Button(
                    onClick = { creating = true },
                    modifier = Modifier.testTag("own-sync-create"),
                ) {
                    Text(stringResource(R.string.own_sync_create))
                }
                TextButton(onClick = onManageServers) {
                    Text(stringResource(R.string.own_sync_servers))
                }
            }
            if (error)
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(stringResource(R.string.own_sync_error), Modifier.padding(12.dp))
                    }
                }
            if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(jobs, key = { "job-" + it.id }) { job ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium)
                        Text(job.tree)
                        Text(
                            "→ ${job.profile.host}:${job.profile.port}/${job.profile.share}${job.profile.root}/${job.namespace}"
                        )
                        Text(
                            stringResource(
                                if (job.policy == OwnSyncPolicy.Additive) R.string.own_sync_additive
                                else R.string.own_sync_mirror
                            )
                        )
                        Button(
                            onClick = { action { controller.rerun(job.id) } },
                            enabled = !working && runs.none { it.jobId == job.id && !it.terminal },
                            modifier = Modifier.testTag("own-sync-rerun-${job.id}"),
                        ) {
                            Text(stringResource(R.string.own_sync_scan))
                        }
                        job.outputs
                            .filter { it.quarantine != null }
                            .forEach { output ->
                                Text(
                                    stringResource(
                                        R.string.own_sync_quarantined,
                                        output.path
                                            .dropLast(1)
                                            .plus(output.quarantine!!)
                                            .joinToString("/"),
                                    )
                                )
                                TextButton(
                                    onClick = { reviewId = "restore:${job.id}:${output.id}" },
                                    enabled =
                                        !working && runs.none { it.jobId == job.id && !it.terminal },
                                ) {
                                    Text(stringResource(R.string.own_sync_restore))
                                }
                            }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.own_sync_history),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(runs, key = { it.id }) { run ->
                Card {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(jobs.firstOrNull { it.id == run.jobId }?.name ?: run.id)
                        Text(stringResource(statusLabel(run.status)))
                        Text(
                            stringResource(
                                R.string.own_sync_progress,
                                run.plan.count { it.done },
                                run.plan.count {
                                    it.action in
                                        listOf(
                                            OwnSyncAction.Add,
                                            OwnSyncAction.Quarantine,
                                            OwnSyncAction.Restore,
                                        )
                                },
                                run.bytesDone,
                            )
                        )
                        if (run.failure != null) Text(stringResource(R.string.own_sync_attention))
                        if (run.status == OwnSyncStatus.AwaitingReview)
                            Button(
                                onClick = { reviewId = run.id },
                                modifier = Modifier.testTag("own-sync-review-${run.id}"),
                            ) {
                                Text(stringResource(R.string.own_sync_review))
                            }
                        if (run.status in listOf(OwnSyncStatus.Running, OwnSyncStatus.Queued))
                            TextButton(
                                onClick = { action { controller.pause(run.id) } },
                                enabled = !working,
                            ) {
                                Text(stringResource(R.string.own_sync_pause))
                            }
                        if (
                            run.status in
                                listOf(
                                    OwnSyncStatus.Paused,
                                    OwnSyncStatus.WaitingPermission,
                                    OwnSyncStatus.NeedsReview,
                                ) && run.failure != "corrupt" && run.failure != "changed"
                        )
                            TextButton(
                                onClick = { action { controller.resume(run.id) } },
                                enabled = !working,
                            ) {
                                Text(stringResource(R.string.own_sync_resume))
                            }
                        if (run.status == OwnSyncStatus.WaitingPermission)
                            TextButton(
                                onClick = {
                                    grantRun = run.id
                                    regrant.launch(
                                        jobs
                                            .firstOrNull { it.id == run.jobId }
                                            ?.tree
                                            ?.let(Uri::parse)
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.own_sync_regrant))
                            }
                        if (!run.terminal && run.failure != "corrupt")
                            TextButton(
                                onClick = { action { controller.cancel(run.id) } },
                                enabled = !working,
                            ) {
                                Text(stringResource(R.string.own_sync_cancel))
                            }
                        if (run.residuals.isNotEmpty()) {
                            Text(stringResource(R.string.own_sync_residuals))
                            run.residuals.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (run.terminal)
                            run.plan
                                .filter {
                                    it.action == OwnSyncAction.Quarantine &&
                                        !it.done &&
                                        it.staging != null
                                }
                                .forEach { pending ->
                                    Text(
                                        pending.path
                                            .dropLast(1)
                                            .plus(pending.staging!!)
                                            .joinToString("/")
                                    )
                                    TextButton(
                                        onClick = { reviewId = "recover:${run.id}:${pending.id}" },
                                        enabled =
                                            !working &&
                                                runs.none { it.jobId == run.jobId && !it.terminal },
                                    ) {
                                        Text(stringResource(R.string.own_sync_restore))
                                    }
                                }
                        if (run.terminal) Text(stringResource(R.string.own_sync_retained))
                    }
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var tree by remember { mutableStateOf<Uri?>(null) }
        var profileId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
        var mirror by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(OwnSyncTreePicker()) { tree = it }
        AlertDialog(
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }
                    .testTag("own-sync-create-dialog"),
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.own_sync_create)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(120) },
                            label = { Text(stringResource(R.string.own_sync_name)) },
                            modifier = Modifier.testTag("own-sync-name"),
                        )
                        Button(
                            onClick = { picker.launch(tree) },
                            modifier = Modifier.testTag("own-sync-source"),
                        ) {
                            Text(stringResource(R.string.own_sync_source))
                        }
                        tree?.let { Text(it.toString()) }
                    }
                    items(profiles, key = { it.id }) { profile ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = profileId == profile.id,
                                        role = Role.RadioButton,
                                        onClick = { profileId = profile.id },
                                    )
                                    .testTag("own-sync-profile-${profile.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = profileId == profile.id, onClick = null)
                            Text(
                                "${profile.name} · ${profile.protocol} · ${profile.host}",
                                Modifier.weight(1f),
                            )
                        }
                    }
                    item {
                        TextButton(onClick = onManageServers) {
                            Text(stringResource(R.string.own_sync_servers))
                        }
                        Row {
                            Checkbox(
                                checked = mirror,
                                onCheckedChange = { mirror = it },
                                modifier = Modifier.testTag("own-sync-mirror"),
                            )
                            Text(stringResource(R.string.own_sync_mirror))
                        }
                        Text(stringResource(R.string.own_sync_mirror_scope))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = tree!!
                        val profile = profileId!!
                        action {
                            controller.create(
                                name,
                                uri,
                                profile,
                                if (mirror) OwnSyncPolicy.ManagedMirror else OwnSyncPolicy.Additive,
                            )
                            creating = false
                        }
                    },
                    enabled = !working && name.isNotBlank() && tree != null && profileId != null,
                    modifier = Modifier.testTag("own-sync-save"),
                ) {
                    Text(stringResource(R.string.own_sync_scan))
                }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) {
                    Text(stringResource(R.string.own_sync_back))
                }
            },
        )
    }
    val review = runs.firstOrNull { it.id == reviewId }
    if (review != null) {
        var partial by remember(review.id) { mutableStateOf(false) }
        var mirror by remember(review.id) { mutableStateOf(false) }
        val hasIncomplete =
            review.snapshot?.complete != true ||
                review.plan.any { it.action == OwnSyncAction.Inaccessible }
        val hasMoves = review.plan.any { it.action == OwnSyncAction.Quarantine }
        AlertDialog(
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }
                    .testTag("own-sync-review-dialog"),
            onDismissRequest = { reviewId = null },
            title = { Text(stringResource(R.string.own_sync_review)) },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(stringResource(R.string.own_sync_review_scope))
                        if (hasIncomplete) {
                            Text(stringResource(R.string.own_sync_incomplete))
                            Row {
                                Checkbox(checked = partial, onCheckedChange = { partial = it })
                                Text(stringResource(R.string.own_sync_partial))
                            }
                        }
                        if (hasMoves)
                            Row {
                                Checkbox(
                                    checked = mirror,
                                    onCheckedChange = { mirror = it },
                                    modifier = Modifier.testTag("own-sync-confirm-mirror"),
                                )
                                Text(stringResource(R.string.own_sync_confirm_mirror))
                            }
                    }
                    items(review.plan) { entry ->
                        Text(
                            stringResource(actionLabel(entry.action)) +
                                " · " +
                                entry.path.joinToString("/") +
                                " · " +
                                (entry.source?.digest?.size ?: entry.output?.digest?.size ?: 0) +
                                " B"
                        )
                    }
                    review.snapshot?.issues.orEmpty().forEach { issue -> item { Text(issue) } }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        action {
                            controller.confirm(review.id, partial, mirror)
                            reviewId = null
                        }
                    },
                    enabled = !working && (!hasIncomplete || partial) && (!hasMoves || mirror),
                    modifier = Modifier.testTag("own-sync-confirm"),
                ) {
                    Text(stringResource(R.string.own_sync_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewId = null }) {
                    Text(stringResource(R.string.own_sync_back))
                }
            },
        )
    }
    if (reviewId?.startsWith("restore:") == true || reviewId?.startsWith("recover:") == true) {
        val parts = reviewId!!.split(':')
        AlertDialog(
            onDismissRequest = { reviewId = null },
            title = { Text(stringResource(R.string.own_sync_restore)) },
            text = { Text(stringResource(R.string.own_sync_restore_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        action {
                            if (parts[0] == "recover")
                                controller.recoverCancelledMove(parts[1], parts[2])
                            else controller.restoreQuarantine(parts[1], parts[2])
                            reviewId = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.own_sync_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewId = null }) {
                    Text(stringResource(R.string.own_sync_back))
                }
            },
        )
    }
}

internal class OwnSyncTreePicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal fun statusLabel(status: OwnSyncStatus): Int =
    when (status) {
        OwnSyncStatus.Queued -> R.string.own_sync_queued
        OwnSyncStatus.Running -> R.string.own_sync_running
        OwnSyncStatus.AwaitingReview -> R.string.own_sync_awaiting
        OwnSyncStatus.Paused -> R.string.own_sync_paused
        OwnSyncStatus.WaitingPermission -> R.string.own_sync_permission
        OwnSyncStatus.NeedsReview -> R.string.own_sync_attention
        OwnSyncStatus.Completed -> R.string.own_sync_completed
        OwnSyncStatus.Cancelled -> R.string.own_sync_cancelled
    }

internal fun actionLabel(action: OwnSyncAction): Int =
    when (action) {
        OwnSyncAction.Add -> R.string.own_sync_add
        OwnSyncAction.Verified -> R.string.own_sync_verified
        OwnSyncAction.Conflict -> R.string.own_sync_conflict
        OwnSyncAction.Inaccessible -> R.string.own_sync_inaccessible
        OwnSyncAction.Quarantine -> R.string.own_sync_quarantine
        OwnSyncAction.Restore -> R.string.own_sync_restore
    }
