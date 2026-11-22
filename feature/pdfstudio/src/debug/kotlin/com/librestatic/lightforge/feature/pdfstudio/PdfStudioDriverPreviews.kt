package com.librestatic.lightforge.feature.pdfstudio

import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

/** Text inspector's alignment choice. Driver: `PdfTextAlignPreview`. */
@Preview
@Composable
fun PdfTextAlignPreview() = DriverFrame {
    var align by remember { mutableStateOf(PdfTextAlign.Center) }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.pdf_text_align))
        PdfTextAlignChoice(selected = align, enabled = true, onSelect = { align = it })
    }
}

/** Delete-project confirmation with a 200-character name. Driver: `PdfDeleteProjectDialogPreview`. */
@Preview
@Composable
fun PdfDeleteProjectDialogPreview() = DriverFrame {
    val name = "Quarterly scanned invoices and receipts ".repeat(6).take(200)
    Confirm(
        stringResource(R.string.pdf_deleteproject),
        stringResource(R.string.pdf_deleteproject_action),
        {},
        body = stringResource(R.string.pdf_library_delete_confirm, name),
    ) {}
}

/** Fill-mode crop-focus editor: tall 1080x2400 image in a wide 93x60 frame. Driver: `PdfCropFocusTallPreview`. */
@Preview
@Composable
fun PdfCropFocusTallPreview() = DriverFrame {
    CropFocusDemo(fakeImage(1080, 2400), frameWidth = 93.0, frameHeight = 60.0)
}

/** Wide 2400x1080 image in a tall 60x93 frame. Driver: `PdfCropFocusWidePreview`. */
@Preview
@Composable
fun PdfCropFocusWidePreview() = DriverFrame {
    CropFocusDemo(fakeImage(2400, 1080), frameWidth = 60.0, frameHeight = 93.0)
}

@Composable
private fun CropFocusDemo(bitmap: ImageBitmap, frameWidth: Double, frameHeight: Double) {
    var fx by remember { mutableStateOf(0.5) }
    var fy by remember { mutableStateOf(0.5) }
    Column(Modifier.padding(16.dp)) {
        PdfCropFocusEditor(
            bitmap = bitmap,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            focusX = fx,
            focusY = fy,
            enabled = true,
            onFocusCommitted = { x, y -> fx = x; fy = y },
            onNudge = { dx, dy -> fx = PdfCropFocus.move(fx, dx); fy = PdfCropFocus.move(fy, dy) },
        )
    }
}

/** Gradient + 10% grid test image with a corner marker so the crop window is identifiable. */
private fun fakeImage(w: Int, h: Int): ImageBitmap {
    val image = ImageBitmap(w, h)
    val canvas = Canvas(image)
    val paint = Paint()
    paint.shader = LinearGradientShader(Offset.Zero, Offset(w.toFloat(), h.toFloat()), listOf(Color(0xFF1E88E5), Color(0xFFFB8C00)))
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    val line = Paint().apply { color = Color.White; strokeWidth = w / 200f }
    for (i in 1 until 10) {
        canvas.drawLine(Offset(w * i / 10f, 0f), Offset(w * i / 10f, h.toFloat()), line)
        canvas.drawLine(Offset(0f, h * i / 10f), Offset(w.toFloat(), h * i / 10f), line)
    }
    canvas.drawRect(0f, 0f, w / 5f, w / 5f, Paint().apply { color = Color.Black })
    return image
}

/** Paper-size cards in the Layout panel. Driver: `PdfPaperCardRowPreview`. */
@Preview
@Composable
fun PdfPaperCardRowPreview() = DriverFrame {
    var selected by remember { mutableStateOf<String?>(PdfPaperPresets.A4) }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.pdf_paper))
        PdfPaperCardRow(
            landscape = false,
            selectedPreset = selected,
            customWidthMm = 100.0,
            customHeightMm = 200.0,
            enabled = true,
            onPreset = { selected = it.id },
            onCustom = { selected = PdfPaperPresets.CUSTOM },
        )
    }
}

/** Pages panel's per-page action buttons. Driver: `PdfPageActionButtonsPreview`. */
@Preview
@Composable
fun PdfPageActionButtonsPreview() = DriverFrame {
    Column(Modifier.padding(16.dp)) {
        PdfPageActionButtons(onDuplicate = {}, duplicateEnabled = true, onDelete = {}, deleteEnabled = true)
    }
}

/** Media tab source chips. Driver: `PdfMediaFilterChipsPreview`. */
@Preview
@Composable
fun PdfMediaFilterChipsPreview() = DriverFrame {
    var selected by remember { mutableStateOf(0) }
    Column(Modifier.padding(16.dp)) {
        PdfMediaFilterChips(selectedIndex = selected, onSelect = { selected = it })
    }
}

/** New-project sheet's template + paper grids (4 each). Driver: `PdfTemplateCardsPreview`. */
@Preview
@Composable
fun PdfTemplateCardsPreview() = DriverFrame {
    var selected by remember { mutableStateOf(PdfTemplate.Blank) }
    var paper by remember { mutableStateOf<String?>(PdfPaperPresets.A4) }
    Column(Modifier.padding(20.dp)) {
        PdfTemplateCardGrid(PdfTemplate.NEW_PROJECT_TILES, selected, enabled = true, onClick = { selected = it })
        PdfPaperCardRow(landscape = false, selectedPreset = paper, enabled = true, onPreset = { paper = it.id }, includeCustom = false)
    }
}

/** Library first-run template shortcuts. Driver: `PdfLibraryTemplatesPreview`. */
@Preview
@Composable
fun PdfLibraryTemplatesPreview() = DriverFrame {
    Column(Modifier.padding(20.dp)) {
        PdfTemplateCardGrid(PdfTemplate.LIBRARY_SHORTCUTS, selected = null, enabled = true, onClick = {})
    }
}

/** New-project sheet's placement-mode cards. Driver: `PdfPlacementModePreview`. */
@Preview
@Composable
fun PdfPlacementModePreview() = DriverFrame {
    var mode by remember { mutableStateOf(PdfFit.Cover) }
    Column(Modifier.padding(20.dp)) {
        PdfPlacementModeSelector(mode = mode, enabled = true, onSelect = { mode = it })
    }
}

/** New-project sheet body in its first frame (below-the-fold deferred). Driver: `PdfNewProjectSheetFirstFramePreview`. */
@Preview
@Composable
fun PdfNewProjectSheetFirstFramePreview() = DriverFrame {
    PdfNewProjectSheetContent("Project 1", null, belowFoldReady = false, onCreate = { _, _, _, _, _, _, _, _ -> })
}

/** New-project sheet body once the deferred sections have arrived. Driver: `PdfNewProjectSheetPreview`. */
@Preview
@Composable
fun PdfNewProjectSheetPreview() = DriverFrame {
    PdfNewProjectSheetContent("Project 1", null, belowFoldReady = true, onCreate = { _, _, _, _, _, _, _, _ -> })
}

/** Exports history with no jobs, under its real top bar. Driver: `PdfExportQueueEmptyPreview`. */
@Preview
@Composable
fun PdfExportQueueEmptyPreview() = DriverFrame {
    Column {
        GalleryTopAppBar(title = stringResource(R.string.pdf_queue), onBack = {})
        PdfExportQueueEmpty(Modifier.weight(1f))
    }
}

/** Print-size selector, project in mm, with two recent customs and 30 x 20 selected. Driver: `PdfPrintSizeSelectorCustomPreview`. */
@Preview
@Composable
fun PdfPrintSizeSelectorCustomPreview() = DriverFrame { PrintSizeSelectorDemo(PdfUnit.Millimeter) }

/** The same selector for a project in cm. Driver: `PdfPrintSizeSelectorCmPreview`. */
@Preview
@Composable
fun PdfPrintSizeSelectorCmPreview() = DriverFrame { PrintSizeSelectorDemo(PdfUnit.Centimeter) }

@Composable
private fun PrintSizeSelectorDemo(unit: PdfUnit) {
    var size by remember { mutableStateOf<PdfPrintSize?>(PdfPrintSize.custom(30.0, 20.0)) }
    var recents by remember {
        mutableStateOf(listOf(PdfPrintSize.custom(30.0, 20.0), PdfPrintSize.custom(45.0, 35.5)))
    }
    Column(Modifier.padding(16.dp)) {
        PdfPrintSizeSelectorContent(
            selected = size,
            enabled = true,
            unit = unit,
            recents = recents,
            onRecord = { recents = PdfRecentPrintSizes.record(recents, it) },
            onSelect = { size = it },
        )
    }
}

/** Layout panel's Snap section with the grid fields (3 x 2 cm). Driver: `PdfSnapSectionPreview`. */
@Preview
@Composable
fun PdfSnapSectionPreview() = DriverFrame {
    var on by remember { mutableStateOf(true) }
    var gx by remember { mutableStateOf(30.0) }
    var gy by remember { mutableStateOf(20.0) }
    Column(Modifier.padding(16.dp)) {
        PdfSnapSection(
            snap = on,
            gridXMm = gx,
            gridYMm = gy,
            unit = PdfUnit.Centimeter,
            dpi = 300,
            pageWidthMm = 210.0,
            pageHeightMm = 297.0,
            enabled = true,
            onSnap = { on = it },
            onGridX = { gx = it },
            onGridY = { gy = it },
        )
    }
}

/** The faint snap grid (30 x 20 mm) over a white A4 page. Driver: `PdfSnapGridOverlayPreview`. */
@Preview
@Composable
fun PdfSnapGridOverlayPreview() = DriverFrame {
    val page = PdfPage(width = 210.0, height = 297.0)
    val density = LocalDensity.current
    val width = 300.dp
    val height = width * (page.height / page.width).toFloat()
    val band = 48.dp
    val zoom = 1.5f
    // The paper box carries the same offset/size/transform PdfCanvas gives it; the grid overlay
    // must land exactly on it at zoom != 1 with a badge band.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.offset(y = -(band / 2))
                .size(width, height)
                .graphicsLayer { scaleX = zoom; scaleY = zoom }
                .background(PdfPaperTokens.Paper)
        )
        PdfSnapGridOverlay(
            grid = PdfSnapGrid(30.0, 20.0),
            page = page,
            density = density,
            width = width,
            height = height,
            badgeBandHeight = band,
            zoom = zoom,
            panX = 0f,
            panY = 0f,
        )
    }
}

/** Custom print size sheet, opened in print mode (cm, no px). Driver: `PdfPrintSizeSheetPreview`. */
@Preview
@Composable
fun PdfPrintSizeSheetPreview() = DriverFrame {
    PdfCustomSizeSheet(
        initialWidthMm = 30.0,
        initialHeightMm = 20.0,
        initialUnit = PdfUnit.Centimeter,
        initialDpi = 300,
        onDismiss = {},
        onUse = { _, _ -> },
        title = stringResource(R.string.pdf_print_size_custom_title),
        minMm = PdfPrintSize.MIN_CUSTOM_MM,
        maxMm = PdfPrintSize.MAX_CUSTOM_MM,
        allowPixelUnit = false,
    )
}

@Composable
private fun DriverFrame(content: @Composable () -> Unit) {
    LightforgeTheme { Surface(Modifier.fillMaxSize(), content = content) }
}
