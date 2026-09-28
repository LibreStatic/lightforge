package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Feedback item B's "Print size" selector: "Free grid" plus every [PdfPrintSize], as wrapping
 * visual chips (never a horizontally-scrolling row — same rationale as [PdfPaperCard]'s D2 review
 * fix: at 320dp/200% font every chip must stay fully reachable without relying on scroll
 * discoverability). Shared by the New project sheet and the Layout panel so both read one
 * implementation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfPrintSizeSelector(
    selected: PdfPrintSize?,
    enabled: Boolean,
    onSelect: (PdfPrintSize?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PdfPrintSizeChip(stringResource(R.string.pdf_print_size_free_grid), selected == null, enabled) {
            onSelect(null)
        }
        PdfPrintSize.entries.forEach { size ->
            PdfPrintSizeChip(stringResource(size.labelRes()), selected == size, enabled) { onSelect(size) }
        }
    }
}

@Composable
private fun PdfPrintSizeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier.heightIn(min = 40.dp * fontScale)
                .selectableTile(label, selected, enabled, onClick),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Feedback item A's placement-mode control: two selectable cards (not a plain segmented control,
 * since the spec calls for a short description under each label — "Fill (crop to fit)" / "Fit
 * (whole photo)"). Wraps at narrow widths/large font scale like every other selector in this
 * screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfPlacementModeSelector(
    mode: PdfFit,
    enabled: Boolean,
    onSelect: (PdfFit) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PdfPlacementModeCard(
            label = stringResource(R.string.pdf_placement_fill_label),
            description = stringResource(R.string.pdf_placement_fill_desc),
            selected = mode == PdfFit.Cover,
            enabled = enabled,
        ) { onSelect(PdfFit.Cover) }
        PdfPlacementModeCard(
            label = stringResource(R.string.pdf_placement_fit_label),
            description = stringResource(R.string.pdf_placement_fit_desc),
            selected = mode == PdfFit.Contain,
            enabled = enabled,
        ) { onSelect(PdfFit.Contain) }
    }
}

@Composable
private fun PdfPlacementModeCard(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    // Comma separator (not a period) matches PdfTemplateCard's convention elsewhere in this
    // screen, which device-driving tooling (verify_pdf_screen_flow.py's match_prefix) relies on.
    val a11yLabel = "$label, $description"
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(min = 140.dp, max = 220.dp).selectableTile(a11yLabel, selected, enabled, onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * The live "N photos per page" line (feedback item B), or the "doesn't fit" error when [fit]'s
 * `perPage == 0` — computed from the current paper/margin/gap/print size, never hardcoded.
 * Returns whether the current combination is valid, so callers (New project sheet's Create
 * button, Layout panel's Arrange) can gate on it.
 */
@Composable
internal fun PdfPrintSizeCountLine(fit: PdfPrintLayout.SlotFit): Boolean {
    if (fit.perPage <= 0) {
        Text(
            stringResource(R.string.pdf_print_size_doesnt_fit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return false
    }
    Text(
        pluralStringResource(R.plurals.pdf_photos_per_page, fit.perPage, fit.perPage),
        style = MaterialTheme.typography.bodyMedium,
    )
    return true
}
