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

@Composable
internal fun Confirm(title: Int, confirmLabel: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
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
    onRetryIntake: () -> Unit,
    onDiscardIntake: () -> Unit,
    onRetryGallery: (String) -> Unit,
    onDiscardGallery: (String) -> Unit,
    onSaveExport: (PdfExportJob) -> Unit,
    onDismissMessage: () -> Unit,
    onCancelBusy: () -> Unit,
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
        if (state.busy) {
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
    }
}

@Composable
private fun IssueCard(error: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
