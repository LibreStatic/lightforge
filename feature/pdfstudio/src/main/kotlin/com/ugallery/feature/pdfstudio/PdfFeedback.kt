package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryIcon
import com.ugallery.core.designsystem.GalleryIcons

private val WATCHED_PROGRESS_PHASES =
    setOf(PdfExportPhase.Queued, PdfExportPhase.Running, PdfExportPhase.Publishing)

@Composable
internal fun Confirm(title: Int, confirmLabel: String, dismiss: () -> Unit, confirm: () -> Unit) {
    Confirm(stringResource(title), confirmLabel, dismiss, confirm)
}

/** Overload for confirmations that name the specific target (e.g. "Delete “My project”?")
 * instead of a generic string resource. */
@Composable
internal fun Confirm(title: String, confirmLabel: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = confirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

/**
 * Feedback that never displaces the canvas: rendered as an overlay anchored above the bottom tool
 * bar rather than as a Column entry, so persistent issues and transient notices never push the
 * page around. Mirrors the previous top-banner conditions 1:1 (gallery intake pending/error,
 * `state.message`/`sourceError`/`readyExport`, and busy progress) while presenting them as a single
 * stack of cards instead of stacked full-width banners that shift everything below them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfFeedbackOverlay(
    state: PdfStudioState,
    galleryRows: List<PdfGalleryDelivery>,
    exportJobs: List<PdfExportJob>,
    intakeFailed: Boolean,
    hasInitialUris: Boolean,
    saveInFlight: Boolean,
    watchedExportId: String?,
    progressHidden: Boolean,
    onRetryIntake: () -> Unit,
    onDiscardIntake: () -> Unit,
    onRetryGallery: (String) -> Unit,
    onDiscardGallery: (String) -> Unit,
    onReplaceGallerySource: (PdfGalleryDelivery) -> Unit = {},
    onRemoveGallerySource: (PdfGalleryDelivery) -> Unit = {},
    onSaveExport: (PdfExportJob) -> Unit,
    onDismissMessage: () -> Unit,
    onCancelBusy: () -> Unit,
    onCancelExport: (String) -> Unit,
    onHideProgress: () -> Unit,
    onRetryExport: (String) -> Unit,
    onDismissResult: () -> Unit,
    onOpenRecovery: (PdfExportJob) -> Unit,
    onDismissRecovery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (intakeFailed && hasInitialUris) {
            IssueCard {
                FlowRow {
                    TextButton(onClick = onRetryIntake) {
                        Text(stringResource(R.string.pdf_gallery_retry))
                    }
                    TextButton(onClick = onDiscardIntake) {
                        Text(stringResource(R.string.pdf_gallery_discard))
                    }
                }
            }
        }
        // A pending delivery (no error yet) that is actively being copied: a determinate,
        // non-blocking card with an ordered thumbnail strip, replacing the generic busy text for
        // this specific operation. Editing/navigation stay available; only this delivery's own
        // Retry/Discard-equivalents are gated on it finishing or failing.
        galleryRows
            .firstOrNull { it.error == null }
            ?.takeIf { state.busy && state.progress != null }
            ?.let { delivery ->
                val progress = state.progress
                IssueCard {
                    val total = progress?.second ?: delivery.sources().size
                    val copied = progress?.first ?: 0
                    Text(
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.pdf_intake_title,
                            total,
                            total,
                        )
                    )
                    LinearProgressIndicator(
                        progress = { copied.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.pdf_intake_progress, copied, total),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelBusy) { Text(stringResource(R.string.pdf_cancel)) }
                    }
                    PdfIntakeStrip(delivery, copied)
                }
            }
        galleryRows
            .firstOrNull { it.error != null }
            ?.let { delivery ->
                IssueCard {
                    Text(
                        stringResource(R.string.pdf_gallery_pending, delivery.sources().size)
                    )
                    val reason =
                        stringResource(
                            if (delivery.failure() == "Cancelled") R.string.pdf_queue_cancelled
                            else PdfFailure.persisted(delivery.failure()).message
                        )
                    Text(
                        delivery.failedSource()?.let { number ->
                            stringResource(R.string.pdf_failure_source, number, reason)
                        } ?: reason
                    )
                    GallerySourceStrip(delivery)
                    FlowRow {
                        TextButton(
                            onClick = { onRetryGallery(delivery.id) },
                            enabled = !state.editorLocked,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = LocalContentColor.current
                                ),
                        ) {
                            Text(stringResource(R.string.pdf_gallery_retry))
                        }
                        TextButton(
                            onClick = { onDiscardGallery(delivery.id) },
                            enabled = !state.editorLocked,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = LocalContentColor.current
                                ),
                        ) {
                            Text(stringResource(R.string.pdf_gallery_discard))
                        }
                        // Only offered when the failure names a specific rejected source: both
                        // actions edit the pending delivery and retry (never a partial commit).
                        if (delivery.failedSource() != null) {
                            TextButton(
                                onClick = { onReplaceGallerySource(delivery) },
                                enabled = !state.editorLocked,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.pdf_intake_replace_photo))
                            }
                            TextButton(
                                onClick = { onRemoveGallerySource(delivery) },
                                enabled = !state.editorLocked,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.pdf_intake_remove_retry))
                            }
                        }
                    }
                }
            }
        state.message?.let {
            val isError = state.sourceError != null
            IssueCard(error = isError) {
                val message = stringResource(it)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.sourceError?.let { number ->
                            stringResource(R.string.pdf_failure_source, number, message)
                        } ?: message,
                        Modifier.weight(1f),
                    )
                    exportJobs
                        .firstOrNull { job -> job.id == state.readyExport }
                        ?.takeIf { job -> job.phase == PdfExportPhase.Ready }
                        ?.let { job ->
                            TextButton(
                                onClick = { onSaveExport(job) },
                                enabled = !state.editorLocked && !saveInFlight,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(
                                    stringResource(
                                        if (job.portable) R.string.pdf_portable
                                        else R.string.pdf_save_pdf
                                    )
                                )
                            }
                        }
                    TextButton(
                        onClick = onDismissMessage,
                        colors =
                            ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) {
                        Text(stringResource(R.string.pdf_banner_dismiss))
                    }
                }
            }
        }
        // The richer gallery-intake card above already covers this same `busy` window with more
        // specific copy and a thumbnail strip; showing both would duplicate the progress bar.
        val intakeCardShown = galleryRows.any { it.error == null } && state.progress != null
        if (state.busy && !intakeCardShown) {
            IssueCard {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.progress?.let {
                            stringResource(R.string.pdf_progress, it.first, it.second)
                        } ?: stringResource(R.string.pdf_busy),
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelBusy) { Text(stringResource(R.string.pdf_cancel)) }
                }
            }
        }
        val watchedJob = exportJobs.firstOrNull { it.id == watchedExportId }
        if (watchedJob != null && watchedJob.phase in WATCHED_PROGRESS_PHASES && !progressHidden) {
            IssueCard {
                LinearProgressIndicator(
                    progress = { watchedJob.completed.toFloat() / watchedJob.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.pdf_export_progress_title,
                        watchedJob.projectName,
                        watchedJob.completed,
                        watchedJob.total,
                    )
                )
                FlowRow {
                    TextButton(
                        onClick = onHideProgress,
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) {
                        Text(stringResource(R.string.pdf_export_keep_editing))
                    }
                    TextButton(
                        onClick = { onCancelExport(watchedJob.id) },
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) {
                        Text(stringResource(R.string.pdf_cancel))
                    }
                }
            }
        }
        exportJobs
            .firstOrNull { it.id == state.resultJobId && it.phase == PdfExportPhase.Failed }
            ?.let { job ->
                IssueCard(error = true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalleryIcon(GalleryIcons.Error, contentDescription = null)
                        Text(stringResource(PdfFailure.persisted(job.error).message))
                    }
                    FlowRow {
                        TextButton(
                            onClick = { onRetryExport(job.id) },
                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        ) {
                            Text(stringResource(R.string.pdf_retry))
                        }
                        if (job.destination != null)
                            TextButton(
                                onClick = { onSaveExport(job) },
                                enabled = !saveInFlight,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.pdf_export_choose_another))
                            }
                        TextButton(
                            onClick = onDismissResult,
                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        ) {
                            Text(stringResource(R.string.pdf_done))
                        }
                    }
                }
            }
        exportJobs
            .firstOrNull { it.id == state.recoveryJobId }
            ?.let { job ->
                IssueCard(error = job.phase == PdfExportPhase.Failed) {
                    Text(
                        if (job.phase == PdfExportPhase.Published)
                            stringResource(
                                R.string.pdf_export_recovery_published,
                                state.recoveryLabel
                                    ?: stringResource(R.string.pdf_export_destination_default),
                            )
                        else
                            stringResource(
                                R.string.pdf_export_recovery_failed,
                                stringResource(PdfFailure.persisted(job.error).message),
                            )
                    )
                    FlowRow {
                        if (job.phase == PdfExportPhase.Published)
                            TextButton(
                                onClick = { onOpenRecovery(job) },
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.pdf_open))
                            }
                        else
                            TextButton(
                                onClick = {
                                    onDismissRecovery()
                                    onRetryExport(job.id)
                                },
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.pdf_retry))
                            }
                        TextButton(
                            onClick = onDismissRecovery,
                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        ) {
                            Text(stringResource(R.string.pdf_banner_dismiss))
                        }
                    }
                }
            }
    }
}

@Composable
internal fun IssueCard(error: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        contentColor =
            if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}
