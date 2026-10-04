package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import java.text.DecimalFormatSymbols
import java.util.Locale

private val UNIT_SUFFIXES = listOf("mm", "cm", "in", "px")

/** Pixels are whole numbers, inches need two decimals (0.79 in must not read "1 in"). */
private fun formatValue(mm: Double, unit: PdfUnit, dpi: Int): String {
    val value = mm / unit.factor(dpi)
    val decimals = when (unit) {
        PdfUnit.Pixel -> 0
        PdfUnit.Inch -> 2
        else -> 1
    }
    // ASCII digits with the locale's decimal separator (210,0 in es/fr/pt/it/de); the parser
    // accepts both ',' and '.'.
    val separator = DecimalFormatSymbols.getInstance().decimalSeparator
    return "%.${decimals}f".format(Locale.ROOT, value).replace('.', separator)
}

private fun formatMm(mm: Double, unit: PdfUnit, dpi: Int): String =
    "${formatValue(mm, unit, dpi)} ${UNIT_SUFFIXES[unit.ordinal]}"

/**
 * A displayed field text together with the exact millimeter value it was rendered from. Re-expressing
 * a size in another unit rounds the *text* (297 mm -> "11.7 in"); parsing that text back would drift
 * the size (297.18 mm), so while the text is untouched the exact value wins.
 */
private data class PinnedMm(val text: String, val dpi: Int, val mm: Double)

/**
 * Custom page size sheet (Phase D item 3), opened from the Layout panel's Custom paper card.
 * `skipPartiallyExpanded = true` because its action row (Cancel / Use size) would otherwise be
 * hidden behind a half-open sheet. One unit menu governs both fields (mixed units aren't allowed);
 * DPI only shows for the px unit; the proportional preview always shows the *last valid* size and
 * says so when the current input is out of range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfCustomSizeSheet(
    initialWidthMm: Double,
    initialHeightMm: Double,
    initialUnit: PdfUnit,
    initialDpi: Int,
    onDismiss: () -> Unit,
    onUse: (widthMm: Double, heightMm: Double) -> Unit,
) {
    var unit by remember { mutableStateOf(initialUnit) }
    var dpi by remember { mutableStateOf(initialDpi) }
    val factor = unit.factor(dpi)
    var widthText by remember { mutableStateOf(formatValue(initialWidthMm, unit, dpi)) }
    var heightText by remember { mutableStateOf(formatValue(initialHeightMm, unit, dpi)) }
    var widthPin by remember { mutableStateOf<PinnedMm?>(PinnedMm(widthText, dpi, initialWidthMm)) }
    var heightPin by remember { mutableStateOf<PinnedMm?>(PinnedMm(heightText, dpi, initialHeightMm)) }
    var lastValidWidthMm by remember { mutableStateOf(initialWidthMm) }
    var lastValidHeightMm by remember { mutableStateOf(initialHeightMm) }

    fun parsedMm(text: String, pin: PinnedMm?): Double? =
        pin?.takeIf { it.text == text && it.dpi == dpi }?.mm
            ?: text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.times(factor)

    val widthMm = parsedMm(widthText, widthPin)
    val heightMm = parsedMm(heightText, heightPin)
    val validation = PdfCustomSize.validate(widthMm, heightMm)
    LaunchedEffect(widthMm, validation.width.isValid) {
        if (widthMm != null && validation.width.isValid) lastValidWidthMm = widthMm
    }
    LaunchedEffect(heightMm, validation.height.isValid) {
        if (heightMm != null && validation.height.isValid) lastValidHeightMm = heightMm
    }

    @Composable
    fun problemText(problem: PdfCustomSize.Problem?): String? =
        when (problem) {
            PdfCustomSize.Problem.TooSmall ->
                stringResource(R.string.pdf_custom_size_min, formatMm(PdfCustomSize.MIN_MM, unit, dpi))
            PdfCustomSize.Problem.TooLarge ->
                stringResource(R.string.pdf_custom_size_max, formatMm(PdfCustomSize.MAX_MM, unit, dpi))
            PdfCustomSize.Problem.Invalid -> stringResource(R.string.pdf_custom_size_invalid)
            null -> null
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .heightIn(max = 560.dp),
        ) {
            Text(stringResource(R.string.pdf_custom_size_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            // Proportional preview of the last VALID size; captioned when the current input
            // can't be shown directly.
            val previewWidth = lastValidWidthMm
            val previewHeight = lastValidHeightMm
            val ratio = (previewWidth / previewHeight).toFloat().let { if (it.isFinite() && it > 0) it else 1f }
            Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                val previewHeightDp = 100.dp
                val previewWidthDp = previewHeightDp * ratio.coerceIn(0.2f, 3f)
                Box(
                    Modifier.height(previewHeightDp)
                        .width(previewWidthDp)
                        .background(PdfPaperTokens.Paper, RoundedCornerShape(2.dp))
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
            if (!validation.isValid)
                Text(
                    stringResource(R.string.pdf_custom_size_preview_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            Spacer(Modifier.height(12.dp))

            var unitMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { unitMenu = true }) { Text(UNIT_SUFFIXES[unit.ordinal]) }
                DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                    PdfUnit.entries.forEach { u ->
                        DropdownMenuItem(
                            text = { Text(UNIT_SUFFIXES[u.ordinal]) },
                            onClick = {
                                unitMenu = false
                                // Re-express the current, still-valid values in the new unit;
                                // mixed units are never shown, only one unit governs both fields.
                                val w = widthMm
                                val h = heightMm
                                unit = u
                                if (w != null) {
                                    widthText = formatValue(w, u, dpi)
                                    widthPin = PinnedMm(widthText, dpi, w)
                                }
                                if (h != null) {
                                    heightText = formatValue(h, u, dpi)
                                    heightPin = PinnedMm(heightText, dpi, h)
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        widthText,
                        { widthText = it },
                        label = { Text(stringResource(R.string.pdf_width)) },
                        singleLine = true,
                        isError = !validation.width.isValid,
                        supportingText = { problemText(validation.width.problem)?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val swapLabel = stringResource(R.string.pdf_custom_size_swap)
                IconButton(
                    onClick = {
                        val w = widthText
                        widthText = heightText
                        heightText = w
                        val wp = widthPin
                        widthPin = heightPin
                        heightPin = wp
                    },
                    modifier = Modifier.padding(top = 8.dp).size(48.dp).semantics { contentDescription = swapLabel },
                ) {
                    Icon(GalleryIcons.SwapHoriz, contentDescription = null)
                }
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        heightText,
                        { heightText = it },
                        label = { Text(stringResource(R.string.pdf_height)) },
                        singleLine = true,
                        isError = !validation.height.isValid,
                        supportingText = { problemText(validation.height.problem)?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (unit == PdfUnit.Pixel) {
                Spacer(Modifier.height(8.dp))
                NumberField(stringResource(R.string.pdf_resolution), dpi.toDouble()) { n ->
                    if (n in 72.0..600.0) dpi = n.toInt()
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pdf_cancel))
                }
                Button(
                    onClick = { onUse(lastValidWidthMm, lastValidHeightMm) },
                    enabled = validation.isValid,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.pdf_custom_size_use))
                }
            }
        }
    }
}
