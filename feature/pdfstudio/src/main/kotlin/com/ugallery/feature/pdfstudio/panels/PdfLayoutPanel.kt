package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfLayoutPanel(vm: PdfStudioViewModel, s: PdfStudioState) {
    val p = s.project ?: return
    val page = p.pages[s.page]
    Text(stringResource(R.string.pdf_design), style = MaterialTheme.typography.titleMedium)
    if (page.source != null) {
        Text(stringResource(R.string.pdf_sourcepdf))
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(
                        width = it.height,
                        height = it.width,
                        rotation = (it.rotation + 90) % 360,
                    )
                }
            },
            enabled = !s.editorLocked,
        ) {
            Text(stringResource(R.string.pdf_rotatepage))
        }
        return
    }
    FlowRow {
        listOf(
                "A4" to (210.0 to 297.0),
                "Letter" to (215.9 to 279.4),
                "10 × 15 cm" to (100.0 to 150.0),
            )
            .forEach { (label, size) ->
                TextButton(
                    onClick = {
                        vm.pageEdit {
                            val next =
                                it.copy(
                                    width = size.first,
                                    height = size.second,
                                    margin = min(it.margin, min(size.first, size.second) / 4),
                                )
                            next.copy(
                                images =
                                    it.images.map { image -> PdfGeometry.constrain(image, next) }
                            )
                        }
                    },
                    enabled = !s.editorLocked,
                ) {
                    Text(label)
                }
            }
        TextButton(
            onClick = {
                vm.pageEdit {
                    val next = it.copy(width = it.height, height = it.width)
                    next.copy(
                        images = it.images.map { image -> PdfGeometry.constrain(image, next) }
                    )
                }
            },
            enabled = !s.editorLocked,
        ) {
            Text(stringResource(R.string.pdf_rotatepage))
        }
    }
    FlowRow {
        PdfUnit.entries.forEach { u ->
            FilterChip(
                selected = u == p.unit,
                onClick = { vm.update { it.copy(unit = u) } },
                label = { Text(listOf("mm", "cm", "in", "px")[u.ordinal]) },
                enabled = !s.editorLocked,
            )
        }
    }
    val factor = p.unit.factor(p.dpi)
    NumberField(stringResource(R.string.pdf_resolution), p.dpi.toDouble()) { n ->
        if (n in 72.0..600.0) vm.update { it.copy(dpi = n.toInt()) }
    }
    NumberField(stringResource(R.string.pdf_width), page.width / factor) { n ->
        if (n * factor >= 20)
            vm.pageEdit {
                val next = it.copy(width = n * factor)
                next.copy(images = it.images.map { i -> PdfGeometry.constrain(i, next) })
            }
    }
    NumberField(stringResource(R.string.pdf_height), page.height / factor) { n ->
        if (n * factor >= 20)
            vm.pageEdit {
                val next = it.copy(height = n * factor)
                next.copy(images = it.images.map { i -> PdfGeometry.constrain(i, next) })
            }
    }
    NumberField(stringResource(R.string.pdf_margin), page.margin / factor) { n ->
        if (n * factor < min(page.width, page.height) / 4)
            vm.pageEdit {
                val next = it.copy(margin = n * factor)
                next.copy(images = it.images.map { i -> PdfGeometry.constrain(i, next) })
            }
    }
    NumberField(stringResource(R.string.pdf_columns), p.columns.toDouble()) { n ->
        if (n in 1.0..6.0) vm.update { it.copy(columns = n.toInt()) }
    }
    NumberField(stringResource(R.string.pdf_gap), p.gap / factor) { n ->
        if (n * factor in 0.0..30.0) vm.update { it.copy(gap = n * factor) }
    }
    Toggle(stringResource(R.string.pdf_snap), p.snap) { enabled ->
        vm.update { it.copy(snap = enabled) }
    }
    TextButton(
        onClick = { vm.pageEdit { PdfGeometry.grid(it, p.columns, p.gap) } },
        enabled = !s.editorLocked,
    ) {
        Text(stringResource(R.string.pdf_arrangegrid))
    }
}
