package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

private val UNIT_LABELS = listOf("mm", "cm", "in", "px")

/** Flattens the per-page template selection map (page id -> template) to a savable list of
 * primitives (Phase F item 0), so the explicit tile choice survives configuration changes and
 * process death like the rest of the panel's UI state. */
private val PdfTemplateSelectionSaver: Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>, List<Any>> =
    Saver(
        save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
        restore = { flat ->
            val map = mutableStateMapOf<String, Int>()
            var i = 0
            while (i + 1 < flat.size) {
                map[flat[i] as String] = flat[i + 1] as Int
                i += 2
            }
            map
        },
    )

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
    // Phase F item 0: which grid-template tile the user explicitly tapped, per page id, so that
    // templates sharing a column count (4 and 6 both use 2 columns in portrait) don't both show
    // as selected. Falls back to the columns-derived match only when it is unambiguous.
    val selectedTemplateByPage = rememberSaveable(saver = PdfTemplateSelectionSaver) { mutableStateMapOf() }

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
            // Distinct from the Pages panel's bulk "Rotate selected pages" (R7 review fix): the
            // expanded layout can show both panels at once, and identical text would be
            // ambiguous for uiautomator/TalkBack.
            Text(stringResource(R.string.pdf_rotatepage_this))
        }
        return
    }

    val landscape = page.width > page.height
    val selectedPreset = PdfPaperPresets.matching(page.width, page.height)

    // Visual paper cards: a proportional white-paper preview on a role-colored (selected =
    // secondaryContainer) card — the only allowed color source for the paper swatch is
    // PdfPaperTokens, never a literal.
    Text(stringResource(R.string.pdf_paper), style = MaterialTheme.typography.labelLarge)
    // D2 review fix: a LazyRow inside a sheet clipped its trailing card ("Custom") at the sheet's
    // edge with no scroll affordance, and card labels ("Letter", "10 × 15", "Square") clipped at
    // narrow widths. A wrapping FlowRow means every card is always fully visible, at any width or
    // font scale, with no horizontal-scroll discoverability problem.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PdfPaperPresets.presets.forEach { preset ->
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
    val selectedTemplate = selectedTemplateByPage[page.id]
        ?: PdfLayoutTemplates.unambiguousMatch(p.columns, landscape)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PdfLayoutTemplates.TEMPLATES.forEach { template ->
            val columns = PdfLayoutTemplates.columnsFor(template, landscape)
            val selected = selectedTemplate == template
            PdfTemplateTile(template, columns, landscape, selected, !s.editorLocked) {
                selectedTemplateByPage[page.id] = template
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
                onClick = {
                    val rowsHint = selectedTemplate?.let { PdfLayoutTemplates.rowsFor(it, landscape) }
                    vm.applyLayout(applyToAllPages) { PdfGeometry.grid(it, p.columns, p.gap, rowsHint) }
                },
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
internal fun PdfPaperCard(
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
    // D2/R6 review fix: fixed 72dp clipped labels ("Lette", "10 ×", "Squa") once font scale grew
    // past 1x. The card grows with font scale and the label gets a second line + shrink-to-fit
    // instead of a hard truncation.
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier.width(76.dp * fontScale)
                .heightIn(min = 96.dp * fontScale)
                .selectableTile(label, selected, enabled, onClick),
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
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize),
            )
        }
    }
}

/** A photos-per-page template tile with a mini grid preview, mapped to a column count by
 * [PdfLayoutTemplates.columnsFor]. */
@Composable
internal fun PdfTemplateTile(
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
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(56.dp * fontScale).selectableTile(label, selected, enabled, onClick),
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

/**
 * A visual template card (Phase G4): a proportional white-paper mini preview — reusing the same
 * [PdfPaperTokens.Paper] swatch and grid-overlay technique as [PdfPaperCard]/[PdfTemplateTile] so
 * all three read as one family — plus the template's name and a one-line description. Reused by
 * both the library empty state and the "New project" sheet's template row so they render
 * identically and share one source of truth ([PdfTemplate]).
 */
@Composable
internal fun PdfTemplateCard(
    template: PdfTemplate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val name = stringResource(template.nameRes)
    val description = stringResource(template.descriptionRes)
    val label =
        if (selected) "$name, $description, ${stringResource(R.string.pdf_template_selected_suffix)}"
        else "$name, $description"
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(104.dp * fontScale).selectableTile(label, selected, enabled, onClick),
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ratio =
                (template.widthMm() / template.heightMm()).toFloat().let { if (it.isFinite() && it > 0) it else 1f }
            Box(
                Modifier.height(48.dp).width(48.dp * ratio.coerceIn(0.4f, 1.6f))
                    .background(PdfPaperTokens.Paper, RoundedCornerShape(1.dp)),
            ) {
                // Blank's columns/photosPerPage are only the gallery-handoff defaults; its preview
                // is an empty page so it doesn't read as a duplicate of Photo grid.
                val rows =
                    if (template == PdfTemplate.Blank) 0
                    else (template.photosPerPage + template.columns - 1) / template.columns
                if (rows > 0) Column(
                    Modifier.fillMaxSize().padding(3.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    repeat(rows) { r ->
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            repeat(template.columns) { c ->
                                val hasCell = r * template.columns + c < template.photosPerPage
                                Box(
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .background(PdfPaperTokens.Ink.copy(alpha = if (hasCell) 0.35f else 0.08f)),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // G4 review fix: at 200% font, a fixed maxFontSize with only 1.6x card-width growth
            // truncated both lines ("Prints 10 ×" / "One photo per page, 10") with no ellipsis,
            // the same clipping PdfPaperCard's shrink-to-fit already fixed elsewhere in this
            // file — same autoSize technique here instead of a hard line/character cap.
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.75f),
                maxLines = 2,
                textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize),
            )
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
    com.librestatic.lightforge.core.designsystem.GalleryExpressiveChoiceGroup(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        enabled = labels.map { enabled },
    )
}
