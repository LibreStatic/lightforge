package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.min

private val UNIT_LABELS = listOf("mm", "cm", "in", "px")

/**
 * Layout panel (Phase D item 2): visual paper cards with proportional mini previews, an
 * orientation segmented control, margin/gap steppers, visual grid-template tiles, a Snap switch,
 * a unit segmented control, and a sticky "Apply to: This page / All pages" + Arrange row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfLayoutPanel(vm: PdfStudioViewModel, s: PdfStudioState) {
    val p = s.project ?: return
    val page = p.pages[s.page]
    var showCustomSize by remember { mutableStateOf(false) }
    var applyToAllPages by remember { mutableStateOf(false) }

    Text(stringResource(R.string.pdf_design), style = MaterialTheme.typography.titleMedium)

    if (page.source != null) {
        Text(stringResource(R.string.pdf_sourcepdf), style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(width = it.height, height = it.width, rotation = (it.rotation + 90) % 360)
                }
            },
            enabled = !s.editorLocked,
        ) {
            Text(stringResource(R.string.pdf_rotatepage))
        }
        return
    }

    val landscape = page.width > page.height
    val selectedPreset = PdfPaperPresets.matching(page.width, page.height)

    // Visual paper cards: a proportional white-paper preview on a role-colored (selected =
    // secondaryContainer) card — the only allowed color source for the paper swatch is
    // PdfPaperTokens, never a literal.
    Text(stringResource(R.string.pdf_paper), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(PdfPaperPresets.presets, key = { it.id }) { preset ->
            val selected = preset.id == selectedPreset
            PdfPaperCard(
                label = preset.label,
                widthMm = if (landscape) preset.heightMm else preset.widthMm,
                heightMm = if (landscape) preset.widthMm else preset.heightMm,
                selected = selected,
                enabled = !s.editorLocked,
            ) {
                val w = if (landscape) preset.heightMm else preset.widthMm
                val h = if (landscape) preset.widthMm else preset.heightMm
                vm.applyLayout(applyToAllPages) {
                    val next = it.copy(width = w, height = h, margin = min(it.margin, min(w, h) / 4))
                    next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                }
            }
        }
        item(key = PdfPaperPresets.CUSTOM) {
            PdfPaperCard(
                label = stringResource(R.string.pdf_paper_custom),
                widthMm = if (landscape) page.height else page.width,
                heightMm = if (landscape) page.width else page.height,
                selected = selectedPreset == PdfPaperPresets.CUSTOM,
                enabled = !s.editorLocked,
            ) {
                showCustomSize = true
            }
        }
    }

    if (showCustomSize) {
        PdfCustomSizeSheet(
            initialWidthMm = page.width,
            initialHeightMm = page.height,
            initialUnit = p.unit,
            initialDpi = p.dpi,
            onDismiss = { showCustomSize = false },
            onUse = { w, h ->
                showCustomSize = false
                vm.applyLayout(applyToAllPages) {
                    val next = it.copy(width = w, height = h, margin = min(it.margin, min(w, h) / 4))
                    next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                }
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pdf_orientation), style = MaterialTheme.typography.labelLarge)
    GalleryExpressiveChoiceGroupCompat(
        labels = listOf(stringResource(R.string.pdf_export_portrait), stringResource(R.string.pdf_export_landscape)),
        selectedIndex = if (landscape) 1 else 0,
        onSelect = { index ->
            val wantLandscape = index == 1
            if (wantLandscape != landscape)
                vm.applyLayout(applyToAllPages) {
                    val next = it.copy(width = it.height, height = it.width)
                    next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                }
        },
        enabled = !s.editorLocked,
    )

    Spacer(Modifier.height(8.dp))
    val factor = p.unit.factor(p.dpi)
    val unitLabel = UNIT_LABELS[p.unit.ordinal]
    PdfStepperField(
        stringResource(R.string.pdf_margin),
        page.margin / factor,
        unitLabel,
        step = 1.0,
        min = 0.0,
        max = min(page.width, page.height) / 4 / factor,
        enabled = !s.editorLocked,
        onValue = { n ->
            vm.applyLayout(applyToAllPages) {
                val next = it.copy(margin = n * factor)
                next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
            }
        },
    )
    PdfStepperField(
        stringResource(R.string.pdf_gap),
        p.gap / factor,
        unitLabel,
        step = 1.0,
        min = 0.0,
        max = 30.0 / factor,
        enabled = !s.editorLocked,
        onValue = { n -> vm.update { it.copy(gap = n * factor) } },
    )

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pdf_grid_template), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        PdfLayoutTemplates.TEMPLATES.forEach { template ->
            val columns = PdfLayoutTemplates.columnsFor(template, landscape)
            val selected = columns == p.columns
            PdfTemplateTile(template, columns, landscape, selected, !s.editorLocked) {
                vm.update { it.copy(columns = columns) }
            }
        }
    }

    Toggle(stringResource(R.string.pdf_snap), p.snap) { enabled -> vm.update { it.copy(snap = enabled) } }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pdf_units), style = MaterialTheme.typography.labelLarge)
    GalleryExpressiveChoiceGroupCompat(
        labels = UNIT_LABELS,
        selectedIndex = p.unit.ordinal,
        onSelect = { index -> vm.update { it.copy(unit = PdfUnit.entries[index]) } },
        enabled = !s.editorLocked,
    )

    Spacer(Modifier.height(12.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(stringResource(R.string.pdf_apply_to), style = MaterialTheme.typography.labelMedium)
            GalleryExpressiveChoiceGroupCompat(
                labels = listOf(stringResource(R.string.pdf_apply_scope_page), stringResource(R.string.pdf_apply_scope_all)),
                selectedIndex = if (applyToAllPages) 1 else 0,
                onSelect = { applyToAllPages = it == 1 },
                enabled = !s.editorLocked,
            )
            Button(
                onClick = { vm.applyLayout(applyToAllPages) { PdfGeometry.grid(it, p.columns, p.gap) } },
                enabled = !s.editorLocked,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.pdf_arrangegrid))
            }
        }
    }
}

/** A small role-colored card with a proportional white-paper preview (Phase D visual paper
 * cards). Only [PdfPaperTokens.Paper] supplies the literal white; the card itself is a Material
 * role pair (secondaryContainer/onSecondaryContainer when selected, surfaceContainer otherwise). */
@Composable
private fun PdfPaperCard(
    label: String,
    widthMm: Double,
    heightMm: Double,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier.width(72.dp)
                .heightIn(min = 96.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics2(label),
    ) {
        Column(
            Modifier.padding(8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ratio = (widthMm / heightMm).toFloat().let { if (it.isFinite() && it > 0) it else 1f }
            Box(
                Modifier.height(40.dp)
                    .width(40.dp * ratio.coerceIn(0.4f, 1.6f))
                    .background(PdfPaperTokens.Paper, RoundedCornerShape(1.dp)),
            )
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

/** A photos-per-page template tile with a mini grid preview, mapped to a column count by
 * [PdfLayoutTemplates.columnsFor]. */
@Composable
private fun PdfTemplateTile(
    template: Int,
    columns: Int,
    landscape: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.pdf_template_photos, template)
    val rows = (template + columns - 1) / columns
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(56.dp).clickable(enabled = enabled, onClick = onClick).semantics2(label),
    ) {
        Column(Modifier.padding(6.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(rows) { r ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(columns) { c ->
                        val hasCell = r * columns + c < template
                        Box(
                            Modifier.weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if (hasCell) content.copy(alpha = 0.4f) else content.copy(alpha = 0.1f),
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Thin wrapper over [GalleryExpressiveChoiceGroup] with a per-item `enabled` list built from a
 * single flag, since every segmented control in this panel is either fully enabled or fully
 * disabled by [PdfStudioState.editorLocked]. */
@Composable
internal fun GalleryExpressiveChoiceGroupCompat(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
) {
    com.ugallery.core.designsystem.GalleryExpressiveChoiceGroup(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        enabled = labels.map { enabled },
    )
}
