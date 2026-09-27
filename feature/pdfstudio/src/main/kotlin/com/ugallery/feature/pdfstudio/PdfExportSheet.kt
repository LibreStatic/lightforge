package com.ugallery.feature.pdfstudio

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryExpressiveChoiceGroup
import com.ugallery.core.designsystem.GalleryIcon
import com.ugallery.core.designsystem.GalleryIcons
import kotlin.math.abs

/**
 * The destination-first export sheet: filename, which pages, quality (with a live "≈ size"
 * estimate per card), a paper/orientation/dpi summary, and the destination row, behind a sticky
 * Cancel/Export action row. Tapping either "Export" or the destination row's "Change" link starts
 * the same "choose where to save, then render" flow (`PdfStudioViewModel.beginNewExport`); there is
 * no separate "pick a destination without exporting yet" step, matching how this sheet is driven.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfExportSheet(
    project: PdfProject,
    currentPageId: String?,
    selectedPageIds: Set<String>,
    defaultFilename: String,
    lastDestinationLabel: String?,
    onEstimate: suspend (PdfExportPagesChoice, Boolean) -> Long,
    onDismiss: () -> Unit,
    onExport: (filename: String, pagesChoice: PdfExportPagesChoice, compact: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var filename by remember { mutableStateOf(defaultFilename) }
    var pagesChoice by remember {
        mutableStateOf(
            if (selectedPageIds.isNotEmpty()) PdfExportPagesChoice.Selected else PdfExportPagesChoice.All
        )
    }
    var compact by remember { mutableStateOf(false) }
    val oversized = remember(project) { PdfExportEstimator.oversizedOriginalPhotoNumbers(project) }
    var originalEstimate by remember { mutableStateOf<Long?>(null) }
    var compactEstimate by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pagesChoice, project.updated) {
        originalEstimate = null
        compactEstimate = null
        originalEstimate = onEstimate(pagesChoice, false)
        compactEstimate = onEstimate(pagesChoice, true)
    }
    val filenameValid = filename.isNotBlank()
    // Fully expanded: a partially expanded sheet would push the sticky Cancel/Export row
    // below the fold, leaving the sheet with no visible way to export.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The filename field can open the IME; imePadding keeps the sticky Cancel/Export row (and
        // the rest of the sheet) above the keyboard instead of letting it cover the row.
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(R.string.pdf_pdfexport), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text(stringResource(R.string.pdf_export_filename)) },
                    singleLine = true,
                    isError = !filenameValid,
                    supportingText = {
                        if (!filenameValid) Text(stringResource(R.string.pdf_export_filename_invalid))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pdf_pages), style = MaterialTheme.typography.titleSmall)
                    val labels =
                        listOf(
                            stringResource(R.string.pdf_exportall),
                            stringResource(R.string.pdf_export_pages_current),
                            stringResource(R.string.pdf_exportselected),
                        )
                    GalleryExpressiveChoiceGroup(
                        labels = labels,
                        selectedIndex = pagesChoice.ordinal,
                        onSelect = { index -> pagesChoice = PdfExportPagesChoice.entries[index] },
                        enabled = listOf(true, true, selectedPageIds.isNotEmpty()),
                    )
                    if (selectedPageIds.isEmpty())
                        Text(
                            stringResource(R.string.pdf_export_pages_selected_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pdf_quality), style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QualityCard(
                            label = stringResource(R.string.pdf_originalquality),
                            estimate = originalEstimate?.let { Formatter.formatShortFileSize(context, it) },
                            selected = !compact,
                            onSelect = { compact = false },
                            modifier = Modifier.weight(1f),
                        )
                        QualityCard(
                            label = stringResource(R.string.pdf_compactquality),
                            estimate = compactEstimate?.let { Formatter.formatShortFileSize(context, it) },
                            selected = compact,
                            onSelect = { compact = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!compact && oversized.isNotEmpty()) {
                        val names =
                            oversized.map { n -> stringResource(R.string.pdf_image_label, n) }
                                .joinToString(", ")
                        Column(
                            Modifier.fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.pdf_export_warning_oversized, names),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            TextButton(onClick = { compact = true }) {
                                Text(
                                    stringResource(R.string.pdf_export_use_compact),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
                Text(
                    exportSummary(project),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Never claim a "last used" location before one exists: a first export
                    // says the destination is chosen next, in the system picker.
                    Text(
                        lastDestinationLabel?.let {
                            stringResource(R.string.pdf_export_destination_label, it)
                        } ?: stringResource(R.string.pdf_export_destination_choose),
                        modifier = Modifier.weight(1f),
                    )
                    if (lastDestinationLabel != null) {
                        TextButton(
                            onClick = { onExport(filename.trim(), pagesChoice, compact) },
                            enabled = filenameValid,
                        ) {
                            Text(stringResource(R.string.pdf_export_destination_change))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) }
                Spacer(Modifier.width(8.dp))
                GalleryExpressiveButton(
                    onClick = { onExport(filename.trim(), pagesChoice, compact) },
                    enabled = filenameValid,
                ) {
                    Text(stringResource(R.string.pdf_export))
                }
            }
        }
    }
}

@Composable
private fun QualityCard(
    label: String,
    estimate: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val borderModifier =
        if (selected)
            Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
        else Modifier
    val estimateText =
        estimate?.let { stringResource(R.string.pdf_export_size_estimate, it) }
            ?: stringResource(R.string.pdf_export_size_calculating)
    // Composed explicitly (rather than left to rely on the selectable node's default descendant
    // merge) so TalkBack always reads the live size estimate along with the label, not just
    // "Original"/"Compact": the previous explicit contentDescription = label hid it entirely.
    val description = stringResource(R.string.pdf_export_quality_card_label, label, estimateText)
    Column(
        modifier
            .heightIn(min = 48.dp)
            .background(container, RoundedCornerShape(12.dp))
            .then(borderModifier)
            // One accessibility node owns role, state, action and the composed label, so
            // TalkBack and UI Automator never see an unlabeled radio with a stray child label.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.RadioButton
                this.selected = selected
                onClick { onSelect(); true }
            }
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected) GalleryIcon(GalleryIcons.CheckCircle, contentDescription = null, tint = onContainer)
            Text(label, style = MaterialTheme.typography.titleMedium, color = onContainer)
        }
        Text(estimateText, color = onContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun approx(a: Double, b: Double) = abs(a - b) < 2.0

/**
 * "A4"/"Letter"/"10×15" are the real-world paper standard names (like "mm"/"in"), not sentences,
 * so they are not run through string resources; only "Square" and the raw-size fallback are.
 */
@Composable
private fun paperLabel(page: PdfPage): String {
    val short = minOf(page.width, page.height)
    val long = maxOf(page.width, page.height)
    return when {
        approx(short, 210.0) && approx(long, 297.0) -> "A4"
        approx(short, 216.0) && approx(long, 279.0) -> "Letter"
        approx(short, 100.0) && approx(long, 150.0) -> "10×15"
        approx(short, long) -> stringResource(R.string.pdf_export_square)
        else -> "%.0f×%.0f mm".format(long, short)
    }
}

@Composable
private fun exportSummary(project: PdfProject): String {
    val page = project.pages.firstOrNull() ?: return ""
    val paper = paperLabel(page)
    val orientation =
        stringResource(
            if (page.width > page.height) R.string.pdf_export_landscape else R.string.pdf_export_portrait
        )
    return stringResource(R.string.pdf_export_summary, paper, orientation, project.dpi)
}

/**
 * Shown once the watched export reaches Published (Phase B item 6): exact page count and output
 * size (unlike the sheet's own "≈" estimates), the location it was saved to, and Open/Share/Done.
 * [onOpen]/[onShare] should call [openPdf]/[sharePdf] and are responsible for reporting a failure
 * (`PdfStudioViewModel.reportOpenFailed`) themselves; this composable only renders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfExportResultSheet(
    job: PdfExportJob,
    locationLabel: String?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GalleryIcon(
                    GalleryIcons.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                val title =
                    if (job.portable) R.string.pdf_export_project_saved_title
                    else R.string.pdf_export_saved_title
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            }
            val pages =
                androidx.compose.ui.res.pluralStringResource(
                    R.plurals.pdf_export_saved_pages,
                    job.total,
                    job.total,
                )
            Text(
                stringResource(
                    R.string.pdf_export_saved_summary,
                    pages,
                    Formatter.formatShortFileSize(context, job.outputBytes),
                    locationLabel ?: stringResource(R.string.pdf_export_destination_default),
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) {
                    Icon(GalleryIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pdf_open))
                }
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.pdf_share)) }
                Spacer(Modifier.weight(1f))
                GalleryExpressiveButton(onClick = onDone) { Text(stringResource(R.string.pdf_done)) }
            }
        }
    }
}
