package com.ugallery.feature.pdfstudio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryTopAppBar

private val FINISHED = setOf(PdfExportPhase.Published, PdfExportPhase.Cancelled, PdfExportPhase.Failed)

internal fun openPdf(context: Context, uri: Uri, onFailed: () -> Unit) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (e: ActivityNotFoundException) {
        onFailed()
    }
}

internal fun sharePdf(context: Context, uri: Uri, onFailed: () -> Unit) {
    try {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            )
        )
    } catch (e: ActivityNotFoundException) {
        onFailed()
    }
}

/**
 * The Exports history as a full-height surface (not a partially expanded sheet, so long lists and
 * the Today/Earlier grouping have real room), with its own top bar: back and an overflow limited
 * to "Clear finished".
 */
@Composable
internal fun PdfExportHistoryDialog(
    jobs: List<PdfExportJob>,
    busy: Boolean,
    vm: PdfStudioViewModel,
    savePending: Boolean,
    onSave: (PdfExportJob) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false: this window draws edge-to-edge on its own terms (API
        // 35+ no longer guarantees the old "auto-inset" dialog default), so the content below
        // explicitly pads for the system bars with windowInsetsPadding instead of relying on it.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                var showOverflow by remember { mutableStateOf(false) }
                GalleryTopAppBar(
                    title = stringResource(R.string.pdf_queue),
                    onBack = onDismiss,
                    navigationContentDescription = stringResource(R.string.pdf_close),
                    actions = {
                        if (jobs.any { it.phase in FINISHED }) {
                            Box {
                                val actionsLabel = stringResource(R.string.pdf_project_actions)
                                IconButton(
                                    onClick = { showOverflow = true },
                                    modifier = Modifier.semantics { contentDescription = actionsLabel },
                                ) {
                                    Icon(GalleryIcons.More, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = showOverflow,
                                    onDismissRequest = { showOverflow = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.pdf_export_clear_finished)) },
                                        onClick = {
                                            showOverflow = false
                                            jobs.filter { it.phase in FINISHED }.forEach { vm.removeExport(it.id) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                PdfExportQueueContent(
                    jobs = jobs,
                    busy = busy,
                    vm = vm,
                    savePending = savePending,
                    onSave = onSave,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun PdfExportQueueContent(
    jobs: List<PdfExportJob>,
    busy: Boolean,
    vm: PdfStudioViewModel,
    savePending: Boolean = false,
    onSave: (PdfExportJob) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = jobs.filter { DateUtils.isToday(it.created) }
    val earlier = jobs.filterNot { DateUtils.isToday(it.created) }
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        if (jobs.isEmpty())
            Text(stringResource(R.string.pdf_queue_empty), Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (today.isNotEmpty()) {
                item(key = "today") {
                    Text(stringResource(R.string.pdf_export_today), style = MaterialTheme.typography.labelLarge)
                }
                items(today, key = { it.id }) { job ->
                    PdfExportJobCard(job, busy, savePending, context, vm, onSave)
                }
            }
            if (earlier.isNotEmpty()) {
                item(key = "earlier") {
                    Text(stringResource(R.string.pdf_export_earlier), style = MaterialTheme.typography.labelLarge)
                }
                items(earlier, key = { it.id }) { job ->
                    PdfExportJobCard(job, busy, savePending, context, vm, onSave)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfExportJobCard(
    job: PdfExportJob,
    busy: Boolean,
    savePending: Boolean,
    context: Context,
    vm: PdfStudioViewModel,
    onSave: (PdfExportJob) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(job.projectName, style = MaterialTheme.typography.titleMedium)
            val typeLabel =
                stringResource(
                    if (job.portable) R.string.pdf_export_type_project else R.string.pdf_export_type_pdf
                )
            val destinationLabel =
                job.destination?.let { uri ->
                    var label by remember(uri) { mutableStateOf<String?>(null) }
                    androidx.compose.runtime.LaunchedEffect(uri) {
                        label = resolveDestinationLabel(context.applicationContext, Uri.parse(uri), "")
                    }
                    label?.takeIf(String::isNotBlank)
                }
            Text(
                if (destinationLabel != null) "$typeLabel · $destinationLabel" else typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    when (job.phase) {
                        PdfExportPhase.Queued -> R.string.pdf_queue_queued
                        PdfExportPhase.Running ->
                            if (job.portable) R.string.pdf_queue_packaging else R.string.pdf_queue_running
                        PdfExportPhase.Ready -> R.string.pdf_queue_ready
                        PdfExportPhase.Publishing -> R.string.pdf_queue_publishing
                        PdfExportPhase.Published -> R.string.pdf_queue_published
                        PdfExportPhase.Failed -> R.string.pdf_queue_failed
                        PdfExportPhase.Cancelled ->
                            if (job.error == "DestinationPreserved") R.string.pdf_queue_preserved
                            else R.string.pdf_queue_cancelled
                        PdfExportPhase.Cancelling -> R.string.pdf_queue_cancelling
                    }
                )
            )
            if (job.phase == PdfExportPhase.Published && job.outputBytes > 0)
                Text(
                    Formatter.formatFileSize(context, job.outputBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (job.phase == PdfExportPhase.Failed) {
                Text(stringResource(PdfFailure.persisted(job.error).message))
            }
            if (job.phase == PdfExportPhase.Running) {
                LinearProgressIndicator(
                    progress = { job.completed.toFloat() / job.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.pdf_progress, job.completed, job.total))
            }
            FlowRow {
                val legacySaveLabel =
                    when {
                        job.portable -> R.string.pdf_portable
                        job.phase == PdfExportPhase.Ready -> R.string.pdf_export_choose_destination
                        job.phase == PdfExportPhase.Failed && job.destination != null ->
                            R.string.pdf_export_choose_another
                        else -> R.string.pdf_save_pdf
                    }
                if (
                    job.phase == PdfExportPhase.Ready ||
                        (job.phase == PdfExportPhase.Failed && job.destination != null)
                )
                    TextButton(onClick = { onSave(job) }, enabled = !busy && !savePending) {
                        Text(stringResource(legacySaveLabel))
                    }
                if (job.phase == PdfExportPhase.Published && job.destination != null) {
                    val uri = Uri.parse(job.destination)
                    TextButton(onClick = { openPdf(context, uri, vm::reportOpenFailed) }) {
                        Icon(
                            GalleryIcons.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.pdf_open))
                    }
                    TextButton(onClick = { sharePdf(context, uri, vm::reportOpenFailed) }) {
                        Text(stringResource(R.string.pdf_share))
                    }
                }
                if (job.phase in setOf(PdfExportPhase.Queued, PdfExportPhase.Running, PdfExportPhase.Publishing))
                    TextButton(onClick = { vm.cancelExport(job.id) }, enabled = !busy) {
                        Text(stringResource(R.string.pdf_cancel))
                    }
                if (job.phase == PdfExportPhase.Cancelling) {
                    TextButton(onClick = { vm.cancelExport(job.id) }, enabled = !busy) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                    TextButton(onClick = { vm.keepExportDestination(job.id) }, enabled = !busy) {
                        Text(stringResource(R.string.pdf_keep_destination))
                    }
                }
                if (job.phase == PdfExportPhase.Failed)
                    TextButton(onClick = { vm.retryExport(job.id) }, enabled = !busy) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                if (
                    job.phase in
                        setOf(
                            PdfExportPhase.Ready,
                            PdfExportPhase.Failed,
                            PdfExportPhase.Published,
                            PdfExportPhase.Cancelled,
                        )
                )
                    TextButton(onClick = { vm.removeExport(job.id) }, enabled = !busy) {
                        Text(stringResource(R.string.pdf_remove))
                    }
            }
        }
    }
}
