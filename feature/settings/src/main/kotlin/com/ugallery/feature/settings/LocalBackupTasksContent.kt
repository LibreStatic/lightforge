package com.ugallery.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LocalBackupTasksContent(
    controller: LocalBackupTaskController,
    onReviewTask: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val tasks by controller.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<String?>(null) }
    var granting by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                error = false
            } catch (_: Exception) {
                error = true
            }
        }
    }
    val openTree =
        rememberLauncherForActivityResult(LocalBackupOpenTree()) { uri ->
            val id = granting
            granting = null
            if (uri != null && id != null) action { controller.regrant(id, uri) }
        }
    val openFile =
        rememberLauncherForActivityResult(LocalBackupOpenDocument()) { uri ->
            val id = granting
            granting = null
            if (uri != null && id != null) action { controller.regrant(id, uri) }
        }
    BackHandler(onBack = onBack)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxSize()
                .testTag("local-backup-tasks-screen")
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.local_backup_back)) }
            Text(
                stringResource(R.string.local_backup_tasks_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(stringResource(R.string.local_backup_task_durable_hint))
            if (error) Text(stringResource(R.string.local_backup_task_action_failed))
            if (tasks.isEmpty()) Text(stringResource(R.string.local_backup_tasks_empty))
            tasks.forEach { task ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("local-backup-task-${task.id}"),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (task.failure == "corrupt")
                                stringResource(R.string.local_backup_task_corrupt)
                            else task.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(task.status.stringId()),
                            modifier = Modifier.testTag("local-backup-task-status-${task.id}"),
                        )
                        Text(
                            stringResource(
                                R.string.local_backup_progress,
                                task.filesDone,
                                task.filesTotal,
                                task.bytesDone,
                            )
                        )
                        if (task.status == LocalBackupTaskStatus.Running) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (task.status == LocalBackupTaskStatus.NeedsReview)
                            Text(stringResource(R.string.local_backup_task_review_hint))
                        if (task.status == LocalBackupTaskStatus.WaitingPermission)
                            Text(stringResource(R.string.local_backup_task_permission_hint))
                        task.destination?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!task.terminal && task.failure != "corrupt") {
                            if (
                                task.status == LocalBackupTaskStatus.Running ||
                                    task.status == LocalBackupTaskStatus.Queued
                            ) {
                                OutlinedButton(
                                    onClick = { action { controller.pause(task.id) } },
                                    enabled = !task.pauseRequested,
                                    modifier =
                                        Modifier.testTag("local-backup-task-pause-${task.id}"),
                                ) {
                                    Text(stringResource(R.string.local_backup_task_pause))
                                }
                            } else if (task.status != LocalBackupTaskStatus.Cancelling) {
                                Button(
                                    onClick = { action { controller.resume(task.id) } },
                                    modifier =
                                        Modifier.testTag("local-backup-task-resume-${task.id}"),
                                ) {
                                    Text(stringResource(R.string.local_backup_task_resume))
                                }
                            }
                            if (
                                task.destination != null &&
                                    task.status == LocalBackupTaskStatus.WaitingPermission
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        granting = task.id
                                        if (task.kind == LocalBackupTaskKind.RestoreFolder)
                                            openTree.launch(null)
                                        else openFile.launch(arrayOf("*/*"))
                                    },
                                    modifier =
                                        Modifier.testTag("local-backup-task-regrant-${task.id}"),
                                ) {
                                    Text(stringResource(R.string.local_backup_task_regrant))
                                }
                            }
                            TextButton(
                                onClick = { cancelling = task.id },
                                enabled = !task.cancelRequested,
                                modifier = Modifier.testTag("local-backup-task-cancel-${task.id}"),
                            ) {
                                Text(stringResource(R.string.local_backup_cancel))
                            }
                        } else if (task.terminal) {
                            if (task.status == LocalBackupTaskStatus.Completed)
                                OutlinedButton(
                                    onClick = { onReviewTask(task.id) },
                                    modifier =
                                        Modifier.testTag("local-backup-task-review-${task.id}"),
                                ) {
                                    Text(stringResource(R.string.local_backup_task_inspect))
                                }
                            TextButton(
                                onClick = { action { controller.forget(task.id) } },
                                modifier = Modifier.testTag("local-backup-task-forget-${task.id}"),
                            ) {
                                Text(stringResource(R.string.local_backup_task_forget))
                            }
                        }
                    }
                }
            }
        }
    }
    if (cancelling != null)
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text(stringResource(R.string.local_backup_cancel)) },
            text = { Text(stringResource(R.string.local_backup_task_cancel_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = cancelling
                        cancelling = null
                        if (id != null) action { controller.cancel(id) }
                    },
                    modifier = Modifier.testTag("local-backup-task-cancel-confirm"),
                ) {
                    Text(stringResource(R.string.local_backup_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelling = null }) {
                    Text(stringResource(R.string.local_backup_task_keep))
                }
            },
        )
}

private fun LocalBackupTaskStatus.stringId(): Int =
    when (this) {
        LocalBackupTaskStatus.Queued -> R.string.local_backup_task_queued
        LocalBackupTaskStatus.Running -> R.string.local_backup_working
        LocalBackupTaskStatus.Paused -> R.string.local_backup_task_paused
        LocalBackupTaskStatus.WaitingPermission -> R.string.local_backup_task_waiting_permission
        LocalBackupTaskStatus.Failed -> R.string.local_backup_failed
        LocalBackupTaskStatus.NeedsReview -> R.string.local_backup_task_needs_review
        LocalBackupTaskStatus.Cancelling -> R.string.local_backup_task_cancelling
        LocalBackupTaskStatus.Cancelled -> R.string.local_backup_cancelled
        LocalBackupTaskStatus.Completed -> R.string.local_backup_task_completed
    }
