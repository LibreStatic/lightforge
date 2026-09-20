package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfExportQueueContent(
    jobs: List<PdfExportJob>,
    busy: Boolean,
    vm: PdfStudioViewModel,
    savePending: Boolean = false,
    onSave: (PdfExportJob) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.pdf_queue), style = MaterialTheme.typography.titleLarge)
        if (jobs.isEmpty())
            Text(stringResource(R.string.pdf_queue_empty), Modifier.padding(vertical = 16.dp))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(jobs, key = { it.id }) { job ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(job.projectName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                when (job.phase) {
                                    PdfExportPhase.Queued -> R.string.pdf_queue_queued
                                    PdfExportPhase.Running ->
                                        if (job.portable) R.string.pdf_queue_packaging
                                        else R.string.pdf_queue_running
                                    PdfExportPhase.Ready -> R.string.pdf_queue_ready
                                    PdfExportPhase.Publishing -> R.string.pdf_queue_publishing
                                    PdfExportPhase.Published -> R.string.pdf_queue_published
                                    PdfExportPhase.Failed -> R.string.pdf_queue_failed
                                    PdfExportPhase.Cancelled ->
                                        if (job.error == "DestinationPreserved")
                                            R.string.pdf_queue_preserved
                                        else R.string.pdf_queue_cancelled
                                    PdfExportPhase.Cancelling -> R.string.pdf_queue_cancelling
                                }
                            )
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
                            if (
                                job.phase == PdfExportPhase.Ready ||
                                    (job.phase == PdfExportPhase.Failed && job.destination != null)
                            )
                                TextButton(
                                    onClick = { onSave(job) },
                                    enabled = !busy && !savePending,
                                ) {
                                    Text(
                                        stringResource(
                                            if (job.portable) R.string.pdf_portable
                                            else R.string.pdf_save_pdf
                                        )
                                    )
                                }
                            if (
                                job.phase in
                                    setOf(
                                        PdfExportPhase.Queued,
                                        PdfExportPhase.Running,
                                        PdfExportPhase.Publishing,
                                    )
                            )
                                TextButton(onClick = { vm.cancelExport(job.id) }, enabled = !busy) {
                                    Text(stringResource(R.string.pdf_cancel))
                                }
                            if (job.phase == PdfExportPhase.Cancelling) {
                                TextButton(onClick = { vm.cancelExport(job.id) }, enabled = !busy) {
                                    Text(stringResource(R.string.pdf_retry))
                                }
                                TextButton(
                                    onClick = { vm.keepExportDestination(job.id) },
                                    enabled = !busy,
                                ) {
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
        }
    }
}
