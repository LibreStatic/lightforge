package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    // Feedback item B: the project's current print size (null = free grid) — "changeable" from
    // here per the Layout panel spec, and item A's placement mode, always shown/changeable.
    val currentPrintSize = PdfPrintSize.fromId(p.printSize)

    /** Re-lays out the print-size project for a new paper/margin/gap (paper cards, custom size,
     * orientation, margin/gap steppers below all route through this when a print size is active,
     * instead of the free-grid per-page transform), or falls back to [fallback] for free grid. */
    fun applyPaperChange(widthMm: Double, heightMm: Double, marginMm: Double, gapMm: Double, fallback: () -> Unit) {
        val size = currentPrintSize
        if (size != null) vm.applyPrintLayoutSettings(size, p.placementMode, widthMm, heightMm, marginMm, gapMm)
        else fallback()
    }

    // Visual paper cards: a proportional white-paper preview on a role-colored (selected =
    // secondaryContainer) card — the only allowed color source for the paper swatch is
    // PdfPaperTokens, never a literal.
    Text(stringResource(R.string.pdf_paper), style = MaterialTheme.typography.labelLarge)
    PdfPaperCardRow(
        landscape = landscape,
        selectedPreset = selectedPreset,
        customWidthMm = page.width,
        customHeightMm = page.height,
        enabled = !s.editorLocked,
        onPreset = { preset ->
            val w = if (landscape) preset.heightMm else preset.widthMm
            val h = if (landscape) preset.widthMm else preset.heightMm
            val m = min(page.margin, min(w, h) / 4)
            applyPaperChange(w, h, m, p.gap) {
                vm.applyLayout(applyToAllPages) {
                    val next = it.copy(width = w, height = h, margin = m)
                    next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                }
            }
        },
        onCustom = { showCustomSize = true },
    )

    if (showCustomSize) {
        PdfCustomSizeSheet(
            initialWidthMm = page.width,
            initialHeightMm = page.height,
            initialUnit = p.unit,
            initialDpi = p.dpi,
            onDismiss = { showCustomSize = false },
            onUse = { w, h ->
                showCustomSize = false
                val m = min(page.margin, min(w, h) / 4)
                applyPaperChange(w, h, m, p.gap) {
                    vm.applyLayout(applyToAllPages) {
                        val next = it.copy(width = w, height = h, margin = m)
                        next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                    }
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
                applyPaperChange(page.height, page.width, page.margin, p.gap) {
                    vm.applyLayout(applyToAllPages) {
                        val next = it.copy(width = it.height, height = it.width)
                        next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                    }
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
        step = PdfStepperMath.layoutStep(p.unit),
        min = 0.0,
        max = min(page.width, page.height) / 4 / factor,
        enabled = !s.editorLocked,
        onValue = { n ->
            val m = n * factor
            applyPaperChange(page.width, page.height, m, p.gap) {
                vm.applyLayout(applyToAllPages) {
                    val next = it.copy(margin = m)
                    next.copy(images = it.images.map { image -> PdfGeometry.constrain(image, next) })
                }
            }
        },
    )
    PdfStepperField(
        stringResource(R.string.pdf_gap),
        p.gap / factor,
        unitLabel,
        step = PdfStepperMath.layoutStep(p.unit),
        min = 0.0,
        max = 30.0 / factor,
        enabled = !s.editorLocked,
        onValue = { n ->
            val g = n * factor
            applyPaperChange(page.width, page.height, page.margin, g) { vm.update { it.copy(gap = g) } }
        },
    )

    Spacer(Modifier.height(8.dp))
    // Feedback item B: "Print size" is changeable here — Free grid keeps the photos-per-page
    // tiles below; any [PdfPrintSize] replaces them with the computed slot count.
    Text(stringResource(R.string.pdf_print_size_label), style = MaterialTheme.typography.labelLarge)
    PdfPrintSizeSelector(selected = currentPrintSize, enabled = !s.editorLocked) { size ->
        vm.applyPrintLayoutSettings(size, p.placementMode, page.width, page.height, page.margin, p.gap)
    }
    val printSizeFit =
        currentPrintSize?.let { PdfPrintLayout.fit(page.width, page.height, page.margin, p.gap, it) }
    if (printSizeFit != null) PdfPrintSizeCountLine(printSizeFit)

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pdf_placement_mode_label), style = MaterialTheme.typography.labelLarge)
    PdfPlacementModeSelector(mode = p.placementMode, enabled = !s.editorLocked) { mode ->
        if (currentPrintSize != null)
            vm.applyPrintLayoutSettings(currentPrintSize, mode, page.width, page.height, page.margin, p.gap)
        else vm.applyPlacementMode(mode, applyToAllPages)
    }

    val selectedTemplate = selectedTemplateByPage[page.id]
        ?: PdfLayoutTemplates.unambiguousMatch(p.columns, landscape)
    if (currentPrintSize == null) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.pdf_grid_template), style = MaterialTheme.typography.labelLarge)
        // One row of equal square tiles spanning the sheet, so none wraps or clips at the edge.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            PdfLayoutTemplates.TEMPLATES.forEach { template ->
                val columns = PdfLayoutTemplates.columnsFor(template, landscape)
                val selected = selectedTemplate == template
                PdfTemplateTile(
                    template,
                    columns,
                    landscape,
                    selected,
                    !s.editorLocked,
                    Modifier.weight(1f).aspectRatio(1f),
                ) {
                    selectedTemplateByPage[page.id] = template
                    vm.update { it.copy(columns = columns) }
                }
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

    // Feedback item B: a print-size project's layout is fully computed — every paper/margin/gap/
    // print-size/placement-mode change above already re-lays out and repaginates automatically
    // (see applyPaperChange and the placement-mode control), so there is nothing left for a manual
    // "Apply to This page/All pages" + Arrange action to do (it operates per-page, which doesn't
    // match this feature's whole-project repagination). Exception: a project with imported PDF
    // pages is never repaginated by a print-size change, so the manual Arrange stays available.
    if (currentPrintSize == null || p.pages.any { it.source != null }) {
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
}

/**
 * The paper-size cards (presets, plus Custom when [includeCustom]). D2 review fix: a LazyRow
 * inside a sheet clipped its trailing card at the sheet's edge and a fixed-width FlowRow wrapped
 * "Personalizado" onto a second row. Now a [PdfEqualTileGrid]: every card has the same width and
 * height, balanced rows, and as many columns as fit at 52dp * fontScale (so 5 cards share one row
 * on ~380dp phones). Labels are single-line (scaled to fit, never split mid-word); cards narrower
 * than 72dp * fontScale are "tight" (reduced padding, smaller label floor), decided inside the
 * card's own layout from its constraints so no subcomposition is needed.
 */
@Composable
internal fun PdfPaperCardRow(
    landscape: Boolean,
    selectedPreset: String?,
    enabled: Boolean,
    onPreset: (PdfPaperPreset) -> Unit,
    modifier: Modifier = Modifier,
    includeCustom: Boolean = true,
    customWidthMm: Double = 0.0,
    customHeightMm: Double = 0.0,
    onCustom: () -> Unit = {},
) {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val presets = PdfPaperPresets.presets
    val count = presets.size + if (includeCustom) 1 else 0
    val minTile = 52.dp * fontScale
    val maxTile = 140.dp * fontScale
    PdfEqualTileGrid(count, minTile, maxTile, modifier.fillMaxWidth().padding(vertical = 4.dp), uniformHeight = true) { index, tileModifier ->
        val preset = presets.getOrNull(index)
        if (preset != null)
            PdfPaperCard(
                label = if (preset.id == PdfPaperPresets.SQUARE) stringResource(R.string.pdf_export_square) else preset.label,
                widthMm = if (landscape) preset.heightMm else preset.widthMm,
                heightMm = if (landscape) preset.widthMm else preset.heightMm,
                selected = preset.id == selectedPreset,
                enabled = enabled,
                modifier = tileModifier,
            ) {
                onPreset(preset)
            }
        else
            PdfPaperCard(
                label = stringResource(R.string.pdf_paper_custom),
                widthMm = customWidthMm,
                heightMm = customHeightMm,
                selected = selectedPreset == PdfPaperPresets.CUSTOM,
                enabled = enabled,
                modifier = tileModifier,
                onClick = onCustom,
            )
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
    modifier: Modifier = Modifier.widthIn(min = 64.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)),
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    // D2/R6 review fix: fixed 72dp clipped labels ("Lette", "10 ×", "Squa") once font scale grew
    // past 1x. The card now sizes from its caller (equal weight in the one-row layout, content
    // width with a font-scaled minimum in the wrapping fallback) and the label is a single line
    // that scales to fit: a second line only ever broke words mid-token ("Personali|zado").
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val labelStyle = MaterialTheme.typography.labelSmall
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier =
            modifier
                .heightIn(min = 96.dp * fontScale)
                .selectableTile(label, selected, enabled, onClick),
    ) {
        val ratio = (widthMm / heightMm).toFloat().let { if (it.isFinite() && it > 0) it else 1f }
        PdfPaperCardLayout(tightBelow = 72.dp * fontScale, normalFloor = 8.sp, tightFloor = 6.sp, baseSize = labelStyle.fontSize) {
            Box(
                Modifier.height(40.dp)
                    .width(40.dp * ratio.coerceIn(0.4f, 1.6f))
                    .background(PdfPaperTokens.Paper, RoundedCornerShape(1.dp)),
            )
            // Single text layout per measure: it is scaled down (never wrapped) when wider than the
            // card, instead of TextAutoSize's several trial layouts.
            Text(label, style = labelStyle, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Content of [PdfPaperCard]: paper swatch, 4dp gap, one-line label, centered. Cards narrower than
 * [tightBelow] are "tight" (4dp instead of 8dp side padding, label may shrink to [tightFloor]
 * rather than [normalFloor]). The label is laid out once at its natural size and drawn scaled
 * down to fit, never below floor/[baseSize]; this replaces a BoxWithConstraints plus
 * TextAutoSize.StepBased (subcomposition and repeated text layouts on every measure).
 */
@Composable
private fun PdfPaperCardLayout(
    tightBelow: Dp,
    normalFloor: TextUnit,
    tightFloor: TextUnit,
    baseSize: TextUnit,
    content: @Composable () -> Unit,
) {
    Layout(content) { measurables, constraints ->
        val tight = constraints.maxWidth < tightBelow.roundToPx()
        val padX = (if (tight) 4.dp else 8.dp).roundToPx()
        val padY = 8.dp.roundToPx()
        val gap = 4.dp.roundToPx()
        val floor = (if (tight) tightFloor else normalFloor).value / baseSize.value
        val swatch = measurables[0].measure(Constraints())
        val label = measurables[1].measure(Constraints())
        val avail = (constraints.maxWidth - 2 * padX).coerceAtLeast(1)
        val scale = if (label.width > avail) (avail.toFloat() / label.width).coerceAtLeast(floor) else 1f
        val labelH = (label.height * scale).roundToInt()
        val width = constraints.constrainWidth(max(swatch.width, (label.width * scale).roundToInt()) + 2 * padX)
        val height = constraints.constrainHeight(swatch.height + gap + labelH + 2 * padY)
        layout(width, height) {
            swatch.placeRelative((width - swatch.width) / 2, padY)
            label.placeWithLayer((width - label.width) / 2, padY + swatch.height + gap) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
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
    /** Overrides the default fixed 56dp (font-scaled) square, e.g. to share a row's width. */
    modifier: Modifier? = null,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val label = pluralStringResource(R.plurals.pdf_template_photos, template, template)
    val rows = (template + columns - 1) / columns
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = (modifier ?: Modifier.size(56.dp * fontScale)).selectableTile(label, selected, enabled, onClick),
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
    modifier: Modifier = Modifier,
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
    // Size comes from the caller ([PdfEqualTileGrid]) so every card in a row/grid is identical.
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.selectableTile(label, selected, enabled, onClick),
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
            // Names such as "Cuadrícula de fotos" or "Impresiones 10 × 15" don't fit one line of a
            // 104dp card; a single auto-sized line bottomed out at its minimum size and clipped
            // with no ellipsis. Two wrapped lines at a readable size, ellipsis only as a fallback.
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** [templates] as a [PdfEqualTileGrid] of [PdfTemplateCard]s (identical width and height,
 * balanced rows), shared by the library first-run states and the New-project sheet. */
@Composable
internal fun PdfTemplateCardGrid(
    templates: List<PdfTemplate>,
    selected: PdfTemplate?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    onClick: (PdfTemplate) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    PdfEqualTileGrid(templates.size, 104.dp * fontScale, 200.dp * fontScale, modifier.fillMaxWidth(), gap) { i, tileModifier ->
        val t = templates[i]
        PdfTemplateCard(t, selected == t, enabled, tileModifier) { onClick(t) }
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
