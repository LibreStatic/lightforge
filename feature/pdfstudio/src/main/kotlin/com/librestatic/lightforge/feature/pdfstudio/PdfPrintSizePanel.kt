package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import com.librestatic.lightforge.core.designsystem.GalleryIcons

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
        verticalArrangement = Arrangement.spacedBy(0.dp),
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
    // A standard M3 filter chip (32dp visual, 48dp touch target, secondaryContainer when
    // selected): the same weight as every other chip in the app instead of a heavier bespoke pill.
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        leadingIcon =
            if (selected) {
                { Icon(GalleryIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
        modifier = Modifier.selectableTile(label, selected, enabled, onClick),
    )
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
    // Two equal-width, equal-height cards on one row (a PdfEqualTileGrid, no intrinsics) so neither
    // wraps alone and leaves half the sheet empty; stacked full-width only at very large font scales.
    val stacked = LocalDensity.current.fontScale > 1.5f
    val card: @Composable (Int, Modifier) -> Unit = { index, cardModifier ->
        if (index == 0)
            PdfPlacementModeCard(
                label = stringResource(R.string.pdf_placement_fill_label),
                description = stringResource(R.string.pdf_placement_fill_desc),
                selected = mode == PdfFit.Cover,
                enabled = enabled,
                modifier = cardModifier,
            ) { onSelect(PdfFit.Cover) }
        else
            PdfPlacementModeCard(
                label = stringResource(R.string.pdf_placement_fit_label),
                description = stringResource(R.string.pdf_placement_fit_desc),
                selected = mode == PdfFit.Contain,
                enabled = enabled,
                modifier = cardModifier,
            ) { onSelect(PdfFit.Contain) }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card(0, Modifier.fillMaxWidth())
            card(1, Modifier.fillMaxWidth())
        }
    } else {
        // minTile 1dp always fits two columns; maxTile is only a cap, so cards split the full width.
        PdfEqualTileGrid(2, 1.dp, 4000.dp, Modifier.fillMaxWidth().padding(vertical = 4.dp), tile = card)
    }
}

@Composable
private fun PdfPlacementModeCard(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
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
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.selectableTile(a11yLabel, selected, enabled, onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
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
