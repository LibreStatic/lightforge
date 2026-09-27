package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfAdjustPanel(vm: PdfStudioViewModel, s: PdfStudioState) {
    val project = s.project ?: return
    val page = project.pages[s.page]
    Text(stringResource(R.string.pdf_adjust), style = MaterialTheme.typography.titleMedium)
    FlowRow {
        page.images.forEachIndexed { n, _ ->
            FilterChip(
                selected = n == s.image,
                onClick = { vm.selectImage(n) },
                label = { Text(stringResource(R.string.pdf_image_label, n + 1)) },
                enabled = !s.editorLocked,
            )
        }
    }
    val image = page.images.getOrNull(s.image) ?: return
    val f = project.unit.factor(project.dpi)
    NumberField("X (${listOf("mm","cm","in","px")[project.unit.ordinal]})", image.x / f) { n ->
        vm.imageEdit { PdfGeometry.constrain(it.copy(x = n * f), page) }
    }
    NumberField("Y (${listOf("mm","cm","in","px")[project.unit.ordinal]})", image.y / f) { n ->
        vm.imageEdit { PdfGeometry.constrain(it.copy(y = n * f), page) }
    }
    NumberField(stringResource(R.string.pdf_width), image.width / f) { n ->
        if (n > 0) vm.imageEdit { PdfGeometry.resize(it, page, n * f, it.height, true) }
    }
    NumberField(stringResource(R.string.pdf_height), image.height / f) { n ->
        if (n > 0) vm.imageEdit { PdfGeometry.resize(it, page, it.width, n * f, false) }
    }
    Toggle(stringResource(R.string.pdf_lockratio), image.locked) { v ->
        vm.imageEdit { it.copy(locked = v) }
    }
    Toggle(stringResource(R.string.pdf_fillimage), image.fit == PdfFit.Cover) { v ->
        vm.imageEdit { it.copy(fit = if (v) PdfFit.Cover else PdfFit.Contain) }
    }
    if (image.fit == PdfFit.Cover) {
        Text(stringResource(R.string.pdf_cropx))
        Slider(image.focusX.toFloat(), { v -> vm.imageEdit { it.copy(focusX = v.toDouble()) } })
        Text(stringResource(R.string.pdf_cropy))
        Slider(image.focusY.toFloat(), { v -> vm.imageEdit { it.copy(focusY = v.toDouble()) } })
    }
    FlowRow {
        TextButton(
            onClick = {
                vm.imageEdit {
                    PdfGeometry.constrain(
                        it.copy(
                            width = it.height,
                            height = it.width,
                            rotation = (it.rotation + 90) % 360,
                        ),
                        page,
                    )
                }
            }
        ) {
            Text(stringResource(R.string.pdf_rotate))
        }
        TextButton(
            onClick = {
                vm.imageEdit {
                    it.copy(x = (page.width - it.width) / 2, y = (page.height - it.height) / 2)
                }
            }
        ) {
            Text(stringResource(R.string.pdf_center))
        }
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(images = it.images.toMutableList().apply { add(removeAt(s.image)) })
                }
                vm.selectImage(page.images.lastIndex)
            }
        ) {
            Text(stringResource(R.string.pdf_front))
        }
        TextButton(
            onClick = {
                vm.pageEdit {
                    it.copy(images = it.images.toMutableList().apply { add(0, removeAt(s.image)) })
                }
                vm.selectImage(0)
            }
        ) {
            Text(stringResource(R.string.pdf_backlayer))
        }
        TextButton(
            onClick = {
                vm.pageEdit { it.copy(images = it.images.filterIndexed { n, _ -> n != s.image }) }
                vm.selectImage(-1)
            }
        ) {
            Text(stringResource(R.string.pdf_remove))
        }
    }
}
