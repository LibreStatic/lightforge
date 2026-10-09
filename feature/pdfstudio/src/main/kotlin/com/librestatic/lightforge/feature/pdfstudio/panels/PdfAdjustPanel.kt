package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing

private val UNIT_LABELS = listOf("mm", "cm", "in", "px")

/**
 * Adjust / inspector panel (Phase D item 5) for the selected image: an asset header (thumbnail +
 * pixel size), a 2×2 grid of [PdfStepperField]s for X/Y/W/H, a lock-ratio chain toggle, a Fit/Fill
 * segmented control, a 2D crop-focus viewport (shown for Fill, replacing the old Crop X/Y
 * sliders), Rotate, Align (relative to Page or Margins) and Layer menus, and Reset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfAdjustPanel(vm: PdfStudioViewModel, s: PdfStudioState) {
    val project = s.project ?: return
    val page = project.pages[s.page]
    // Phase G2: 2+ elements selected as a group replaces this panel's content with the compact
    // group inspector — same "Adjust" tab/sheet slot as the single-element inspector below.
    if (s.groupSelected) {
        PdfGroupInspector(vm, s)
        return
    }
    // Item 5: a selected text replaces this panel's content with the text inspector instead of
    // the image one — same "Adjust" tab/sheet slot, just different content for the selection kind.
    val selectedText = s.selectedTextId?.let { id -> page.texts.firstOrNull { it.id == id } }
    if (selectedText != null) {
        PdfTextInspector(vm, s, page, selectedText)
        return
    }
    Text(stringResource(R.string.pdf_adjust), style = MaterialTheme.typography.titleMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) {
        page.images.forEachIndexed { n, _ ->
            FilterChip(
                selected = n == s.image,
                onClick = { vm.selectImage(n) },
                label = { Text(stringResource(R.string.pdf_image_label, n + 1)) },
                enabled = !s.editorLocked,
            )
        }
    }
    val image = page.images.getOrNull(s.image) ?: run {
        Text(stringResource(R.string.pdf_selectimage), style = MaterialTheme.typography.bodySmall)
        return
    }
    val asset = project.assets.firstOrNull { it.hash == image.asset }
    val f = project.unit.factor(project.dpi)
    val unitLabel = UNIT_LABELS[project.unit.ordinal]

    // Asset header: thumbnail + pixel size (no stored filename in PdfAsset today, so this omits
    // the name rather than fabricate one).
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PdfBitmap(
            vm.repository.file(image.asset),
            0,
            PdfFit.Contain,
            .5,
            .5,
            Modifier.size(48.dp).background(PdfPaperTokens.Paper, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        if (asset != null)
            Text(
                stringResource(R.string.pdf_asset_pixel_size, asset.width, asset.height),
                style = MaterialTheme.typography.bodySmall,
            )
    }

    // D1 review fix: at compact widths (and always at large font scale) a fixed 2-column grid
    // squeezed each PdfStepperField's text box so narrow the typed value wasn't visible and its
    // label word-split ("Widt/h"). Two columns are only used once each field's own text box would
    // actually get >= ~120dp*fontScale — otherwise every stepper gets the full row width.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
        val minFieldTextWidth = 120.dp * fontScale
        val stepperButtons = 48.dp * 4 // two [-]/[+] pairs, one per field, in a two-column row
        val twoColumns = maxWidth >= (minFieldTextWidth * 2 + stepperButtons + 24.dp)
        @Composable
        fun stepperRow(
            first: @Composable (Modifier) -> Unit,
            second: @Composable (Modifier) -> Unit,
        ) {
            if (twoColumns) {
                Row(Modifier.fillMaxWidth()) {
                    first(Modifier.weight(1f))
                    second(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    first(Modifier.fillMaxWidth())
                    second(Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            stepperRow(
                { m ->
                    PdfStepperField(
                        "X",
                        image.x / f,
                        unitLabel,
                        step = 1.0,
                        min = 0.0,
                        max = page.width / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n -> vm.imageEdit { PdfGeometry.constrainToPage(it.copy(x = n * f), page) } },
                    )
                },
                { m ->
                    PdfStepperField(
                        "Y",
                        image.y / f,
                        unitLabel,
                        step = 1.0,
                        min = 0.0,
                        max = page.height / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n -> vm.imageEdit { PdfGeometry.constrainToPage(it.copy(y = n * f), page) } },
                    )
                },
            )
            stepperRow(
                { m ->
                    PdfStepperField(
                        stringResource(R.string.pdf_width),
                        image.width / f,
                        unitLabel,
                        step = 1.0,
                        min = 1.0 / f,
                        max = page.width / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n -> if (n > 0) vm.imageEdit { PdfGeometry.resize(it, page, n * f, it.height, true) } },
                    )
                },
                { m ->
                    PdfStepperField(
                        stringResource(R.string.pdf_height),
                        image.height / f,
                        unitLabel,
                        step = 1.0,
                        min = 1.0 / f,
                        max = page.height / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n -> if (n > 0) vm.imageEdit { PdfGeometry.resize(it, page, it.width, n * f, false) } },
                    )
                },
            )
        }
    }

    val lockLabel = stringResource(R.string.pdf_lockratio)
    val lockState =
        stringResource(if (image.locked) R.string.pdf_lockratio_on else R.string.pdf_lockratio_off)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconToggleButton(
            checked = image.locked,
            onCheckedChange = { v -> vm.imageEdit { it.copy(locked = v) } },
            enabled = !s.editorLocked,
            modifier = Modifier.size(48.dp).semantics { contentDescription = lockLabel; stateDescription = lockState },
        ) {
            Icon(if (image.locked) GalleryIcons.Link else GalleryIcons.LinkOff, contentDescription = null)
        }
        Text(lockLabel)
    }

    Text(stringResource(R.string.pdf_fitfill), style = MaterialTheme.typography.labelLarge)
    GalleryExpressiveChoiceGroupCompat(
        labels = listOf(stringResource(R.string.pdf_fit_label), stringResource(R.string.pdf_fill_label)),
        selectedIndex = if (image.fit == PdfFit.Cover) 1 else 0,
        onSelect = { index -> vm.imageEdit { it.copy(fit = if (index == 1) PdfFit.Cover else PdfFit.Contain) } },
        enabled = !s.editorLocked,
    )

    if (image.fit == PdfFit.Cover) {
        Spacer(Modifier.height(8.dp))
        PdfCropFocusViewport(vm, image, enabled = !s.editorLocked)
    }

    Spacer(Modifier.height(GallerySpacing.Lg))
    // User feedback item 2: these five were bare TextButtons crammed edge to edge ("Girar /
    // Alinear / Capa / Restablecer / Eliminar" with no shared spacing rule). Now a row of
    // same-styled icon+label tonal buttons (Material 3 Expressive, ≥48dp targets, GallerySpacing
    // between them), with Eliminar in the errorContainer/onErrorContainer role pair like every
    // other destructive action in this feature (PdfMultiSelectBar, PdfGroupInspector).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        FilledTonalButton(
            onClick = { vm.imageEdit { PdfGeometry.constrain(it.copy(width = it.height, height = it.width, rotation = (it.rotation + 90) % 360), page) } },
            enabled = !s.editorLocked,
        ) {
            Icon(GalleryIcons.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pdf_rotate))
        }
        var showAlign by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { showAlign = true }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_toolbar_align))
            }
            DropdownMenu(expanded = showAlign, onDismissRequest = { showAlign = false }) {
                Text(
                    stringResource(R.string.pdf_align_relative_margins),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                alignEntries().forEach { (align, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = { showAlign = false; vm.alignSelectedImage(align, relativeToMargins = true) },
                    )
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.pdf_align_relative_page),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                alignEntries().forEach { (align, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = { showAlign = false; vm.alignSelectedImage(align, relativeToMargins = false) },
                    )
                }
            }
        }
        var showLayer by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { showLayer = true }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_toolbar_layer))
            }
            DropdownMenu(expanded = showLayer, onDismissRequest = { showLayer = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_layer_forward)) },
                    onClick = { showLayer = false; vm.bringSelectedForward() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_layer_backward)) },
                    onClick = { showLayer = false; vm.sendSelectedBackward() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_front)) },
                    onClick = { showLayer = false; vm.bringSelectedToFront() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_backlayer)) },
                    onClick = { showLayer = false; vm.sendSelectedToBack() },
                )
            }
        }
        OutlinedButton(onClick = vm::resetSelectedImage, enabled = !s.editorLocked) {
            Text(stringResource(R.string.pdf_reset))
        }
        FilledTonalButton(
            onClick = {
                vm.pageEdit { it.copy(images = it.images.filterIndexed { n, _ -> n != s.image }) }
                vm.selectImage(-1)
            },
            enabled = !s.editorLocked,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(GalleryIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pdf_remove))
        }
    }
}

private fun alignEntries() =
    listOf(
        PdfGeometry.Align.Left to R.string.pdf_align_left,
        PdfGeometry.Align.Center to R.string.pdf_align_center,
        PdfGeometry.Align.Right to R.string.pdf_align_right,
        PdfGeometry.Align.Top to R.string.pdf_align_top,
        PdfGeometry.Align.Middle to R.string.pdf_align_middle,
        PdfGeometry.Align.Bottom to R.string.pdf_align_bottom,
    )

/**
 * 2D crop-focus viewport (Phase D item 5), replacing the old Crop X/Y sliders. Loads the image
 * exactly like the canvas does (same rotation, same shared bitmap cache) and wires
 * [PdfCropFocusEditor] to the view model.
 */
@Composable
private fun PdfCropFocusViewport(vm: PdfStudioViewModel, image: PdfImage, enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = vm.repository.file(image.asset)
    val bitmap by
        produceState<ImageBitmap?>(null, file.path, image.rotation) {
            value =
                try {
                    PdfBitmapStore.get(context).load(file, image.rotation, 1024, immutable = true).asImageBitmap()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
        }
    PdfCropFocusEditor(
        bitmap = bitmap,
        frameWidth = image.width,
        frameHeight = image.height,
        focusX = image.focusX,
        focusY = image.focusY,
        enabled = enabled,
        onFocusCommitted = vm::setSelectedImageFocus,
        onNudge = vm::moveSelectedImageFocus,
    )
}

/**
 * Stateless crop-focus editor: the whole image aspect-fit, with the part that will actually be
 * visible in the Fill frame outlined and the rest dimmed. Dragging moves that window 1:1 with the
 * finger; a tap centers it there. The geometry is [PdfCropFocus.window], i.e. the same
 * [PdfPrintLayout.contentRect] math the canvas and exporter use. [bitmap] is the displayed
 * (already rotated) image, so its size is the content size. Keyboard/TalkBack adjustable via
 * custom actions that nudge the focus [PdfCropFocus.NUDGE] in each direction; the merged
 * accessibility value reads "Focus 50%, 50%" style text ([R.string.pdf_crop_focus_value]).
 */
@Composable
internal fun PdfCropFocusEditor(
    bitmap: ImageBitmap?,
    frameWidth: Double,
    frameHeight: Double,
    focusX: Double,
    focusY: Double,
    enabled: Boolean,
    onFocusCommitted: (Double, Double) -> Unit,
    onNudge: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val left = stringResource(R.string.pdf_crop_focus_left)
    val right = stringResource(R.string.pdf_crop_focus_right)
    val up = stringResource(R.string.pdf_crop_focus_up)
    val down = stringResource(R.string.pdf_crop_focus_down)
    val focusValue =
        stringResource(R.string.pdf_crop_focus_value, PdfCropFocus.percent(focusX), PdfCropFocus.percent(focusY))
    val label = stringResource(R.string.pdf_crop_focus)
    // Live drag position for visual feedback only; the actual undo-tracked commit happens once,
    // at drag end/cancel (R2 review fix) — a per-pointer-event commit was flooding undo with one
    // step per frame, the same trap `moveImage`'s own live overlay + `moveImageTo` on release
    // avoids on the canvas.
    var liveFocusX by remember { mutableStateOf(focusX) }
    var liveFocusY by remember { mutableStateOf(focusY) }
    var dragging by remember { mutableStateOf(false) }
    val shownFocusX = if (dragging) liveFocusX else focusX
    val shownFocusY = if (dragging) liveFocusY else focusY
    val currentFocusX by rememberUpdatedState(focusX)
    val currentFocusY by rememberUpdatedState(focusY)
    val commit by rememberUpdatedState(onFocusCommitted)
    val bmpW = bitmap?.width?.toDouble() ?: 0.0
    val bmpH = bitmap?.height?.toDouble() ?: 0.0
    val valid = bmpW > 0 && bmpH > 0 && frameWidth > 0 && frameHeight > 0
    // Visible fraction per axis does not depend on the focus, so it is stable during a drag.
    val visible = if (valid) PdfCropFocus.window(frameWidth, frameHeight, bmpW, bmpH, .5, .5) else null
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        // The image box is as wide as fits but never taller than 240dp (a panorama just gets
        // less height); the surrounding container shows through beside a tall image.
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.heightIn(max = 240.dp)
                    .aspectRatio(if (valid) (bmpW / bmpH).toFloat() else 1.5f)
                    .clipToBounds()
                    .testTag("pdf-crop-focus")
                    .semantics {
                        contentDescription = label
                        stateDescription = focusValue
                        if (enabled)
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(left) {
                                        onNudge(-PdfCropFocus.NUDGE, 0.0)
                                        true
                                    },
                                    CustomAccessibilityAction(right) {
                                        onNudge(PdfCropFocus.NUDGE, 0.0)
                                        true
                                    },
                                    CustomAccessibilityAction(up) {
                                        onNudge(0.0, -PdfCropFocus.NUDGE)
                                        true
                                    },
                                    CustomAccessibilityAction(down) {
                                        onNudge(0.0, PdfCropFocus.NUDGE)
                                        true
                                    },
                                )
                    }
                    .pointerInput(enabled, visible) {
                        if (!enabled || visible == null) return@pointerInput
                        detectTapGestures { tap ->
                            val x = PdfCropFocus.focusCenteredAt(currentFocusX, tap.x / size.width.toDouble(), visible.width)
                            val y = PdfCropFocus.focusCenteredAt(currentFocusY, tap.y / size.height.toDouble(), visible.height)
                            if (x != currentFocusX || y != currentFocusY) commit(x, y)
                        }
                    }
                    .pointerInput(enabled, visible) {
                        if (!enabled || visible == null) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                liveFocusX = currentFocusX
                                liveFocusY = currentFocusY
                                dragging = true
                            },
                            onDragEnd = {
                                dragging = false
                                if (liveFocusX != currentFocusX || liveFocusY != currentFocusY)
                                    commit(liveFocusX, liveFocusY)
                            },
                            onDragCancel = { dragging = false },
                        ) { change, dragAmount ->
                            change.consume()
                            liveFocusX = PdfCropFocus.focusForDrag(liveFocusX, dragAmount.x.toDouble(), size.width.toDouble(), visible.width)
                            liveFocusY = PdfCropFocus.focusForDrag(liveFocusY, dragAmount.y.toDouble(), size.height.toDouble(), visible.height)
                        }
                    }
            ) {
                if (bitmap != null && valid) {
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val win = PdfCropFocus.window(frameWidth, frameHeight, bmpW, bmpH, shownFocusX, shownFocusY)
                        val wl = (win.left * size.width).toFloat()
                        val wt = (win.top * size.height).toFloat()
                        val ww = (win.width * size.width).toFloat()
                        val wh = (win.height * size.height).toFloat()
                        // Dim everything outside the window with four rects around it.
                        drawRect(scrim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, wt))
                        drawRect(scrim, Offset(0f, wt + wh), androidx.compose.ui.geometry.Size(size.width, size.height - wt - wh))
                        drawRect(scrim, Offset(0f, wt), androidx.compose.ui.geometry.Size(wl, wh))
                        drawRect(scrim, Offset(wl + ww, wt), androidx.compose.ui.geometry.Size(size.width - wl - ww, wh))
                        val outline = androidx.compose.ui.geometry.Size(ww, wh)
                        drawRect(PdfPaperTokens.GuideOuter, Offset(wl, wt), outline, style = Stroke(width = 3.dp.toPx()))
                        drawRect(PdfPaperTokens.GuideInner, Offset(wl, wt), outline, style = Stroke(width = 1.dp.toPx()))
                    }
                }
            }
        }
    }
}

/**
 * Text inspector (Phase G1b item 5): content field (multi-line, validated), font family/weight,
 * size stepper, alignment, ink swatches, X/Y/W/H steppers, Align/Layer menus and Delete — the
 * text-layer twin of [PdfAdjustPanel]'s image content, shown in the same "Adjust" slot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfTextInspector(vm: PdfStudioViewModel, s: PdfStudioState, page: PdfPage, text: PdfText) {
    val project = s.project ?: return
    val f = project.unit.factor(project.dpi)
    val unitLabel = UNIT_LABELS[project.unit.ordinal]
    Text(stringResource(R.string.pdf_adjust), style = MaterialTheme.typography.titleMedium)

    // Content: multi-line, validated against the same glyph gate every commit path uses. Round-2
    // fix (item E): the draft stays LOCAL and commits once (focus loss / IME Done / leaving this
    // text via selection change or closing the panel) instead of one vm.textEdit per keystroke,
    // matching the canvas's own inline editor.
    // Keyed on text.text too (not just text.id): confirmed on-device that mounting this panel
    // early (auto-opened the instant a text is selected) then editing the SAME text through the
    // canvas's own inline editor left this field showing the stale placeholder while the canvas
    // already showed the real content - and worse, closing/leaving this panel would then silently
    // commit that stale draft, overwriting the real edit back to the placeholder. text.text only
    // changes from a genuinely external commit (this field's own commit sets draft == text.text
    // already, so re-keying on it then is a no-op), never from typing here (which no longer
    // commits per keystroke - item E), so this never resets mid-edit.
    var draft by remember(text.id, text.text) { mutableStateOf(text.text) }
    var error by remember(text.id) { mutableStateOf<Int?>(null) }
    fun commitContent() {
        val value = draft
        when {
            value.isBlank() -> error = R.string.pdf_text_empty
            PdfTextSupport.check(value).isFailure -> error = PdfFailure.UnsupportedGlyph.message
            else -> {
                error = null
                if (value != text.text) vm.textEdit(text.id) { it.copy(text = value) }
            }
        }
    }
    DisposableEffect(text.id) { onDispose { commitContent() } }
    OutlinedTextField(
        value = draft,
        onValueChange = { value ->
            draft = value
            error = null
        },
        label = { Text(stringResource(R.string.pdf_content)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        enabled = !s.editorLocked,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitContent() },
        minLines = 2,
        maxLines = 6,
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
        keyboardActions =
            androidx.compose.foundation.text.KeyboardActions(onDone = { commitContent() }),
    )

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pdf_text_font), style = MaterialTheme.typography.labelLarge)
    com.librestatic.lightforge.core.designsystem.GalleryExpressiveChoiceGroup(
        labels = listOf(stringResource(R.string.pdf_font_sans), stringResource(R.string.pdf_font_serif)),
        selectedIndex = if (text.font == PdfFontFamily.Serif) 1 else 0,
        onSelect = { index ->
            vm.textEdit(text.id) {
                it.copy(font = if (index == 1) PdfFontFamily.Serif else PdfFontFamily.Sans)
            }
        },
        enabled = listOf(!s.editorLocked, !s.editorLocked),
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        val boldLabel = stringResource(R.string.pdf_text_bold)
        val boldState =
            stringResource(if (text.weight == PdfFontWeight.Bold) R.string.pdf_text_bold_on else R.string.pdf_text_bold_off)
        IconToggleButton(
            checked = text.weight == PdfFontWeight.Bold,
            onCheckedChange = { checked ->
                vm.textEdit(text.id) {
                    it.copy(weight = if (checked) PdfFontWeight.Bold else PdfFontWeight.Regular)
                }
            },
            enabled = !s.editorLocked,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = boldLabel
                stateDescription = boldState
            },
        ) {
            Icon(GalleryIcons.FormatBold, contentDescription = null)
        }
        Text(boldLabel)
    }

    PdfStepperField(
        stringResource(R.string.pdf_text_size),
        text.sizePt,
        "pt",
        step = 1.0,
        min = 6.0,
        max = 144.0,
        enabled = !s.editorLocked,
        modifier = Modifier.fillMaxWidth(),
        onValue = { n -> vm.textEdit(text.id) { it.copy(sizePt = n.coerceIn(6.0, 144.0)) } },
    )

    Text(stringResource(R.string.pdf_text_align), style = MaterialTheme.typography.labelLarge)
    PdfTextAlignChoice(
        selected = text.align,
        enabled = !s.editorLocked,
        onSelect = { align -> vm.textEdit(text.id) { it.copy(align = align) } },
    )

    Text(stringResource(R.string.pdf_text_ink), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PdfInk.entries.forEach { ink ->
            val name =
                stringResource(
                    when (ink) {
                        PdfInk.Black -> R.string.pdf_ink_black
                        PdfInk.DarkGray -> R.string.pdf_ink_darkgray
                        PdfInk.Red -> R.string.pdf_ink_red
                        PdfInk.Blue -> R.string.pdf_ink_blue
                        PdfInk.Green -> R.string.pdf_ink_green
                    }
                )
            val selectedInk = text.ink == ink
            // Round-2 fix (item F): the swatch's own visual stays a compact 36dp circle, but the
            // touch target (and the semantics/clickable that make it selectable) is the full 48dp
            // box around it, so it meets the same touch-target floor as every other control here.
            Box(
                Modifier.size(48.dp)
                    .semantics {
                        contentDescription = name
                        this.selected = selectedInk
                        role = androidx.compose.ui.semantics.Role.RadioButton
                    }
                    .let { m ->
                        if (s.editorLocked) m
                        else
                            m.clickable { vm.textEdit(text.id) { it.copy(ink = ink) } }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp)
                        .background(PdfPaperTokens.compose(ink), androidx.compose.foundation.shape.CircleShape)
                        .border(
                            width = if (selectedInk) 3.dp else 1.dp,
                            color =
                                if (selectedInk) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                if (selectedInk)
                    Icon(
                        GalleryIcons.Check,
                        contentDescription = null,
                        tint = PdfPaperTokens.GuideOuter,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
        val minFieldTextWidth = 120.dp * fontScale
        val stepperButtons = 48.dp * 4
        val twoColumns = maxWidth >= (minFieldTextWidth * 2 + stepperButtons + 24.dp)
        @Composable
        fun stepperRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
            if (twoColumns) {
                Row(Modifier.fillMaxWidth()) {
                    first(Modifier.weight(1f))
                    second(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    first(Modifier.fillMaxWidth())
                    second(Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            stepperRow(
                { m ->
                    PdfStepperField(
                        "X",
                        text.x / f,
                        unitLabel,
                        step = 1.0,
                        min = 0.0,
                        max = page.width / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n ->
                            vm.textEdit(text.id) {
                                PdfGeometry.constrainTextToPage(it.copy(x = n * f), page)
                            }
                        },
                    )
                },
                { m ->
                    PdfStepperField(
                        "Y",
                        text.y / f,
                        unitLabel,
                        step = 1.0,
                        min = 0.0,
                        max = page.height / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n ->
                            vm.textEdit(text.id) {
                                PdfGeometry.constrainTextToPage(it.copy(y = n * f), page)
                            }
                        },
                    )
                },
            )
            stepperRow(
                { m ->
                    PdfStepperField(
                        stringResource(R.string.pdf_width),
                        text.width / f,
                        unitLabel,
                        step = 1.0,
                        min = 1.0 / f,
                        max = page.width / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n ->
                            if (n > 0)
                                vm.textEdit(text.id) {
                                    PdfGeometry.constrainTextToPage(it.copy(width = n * f), page)
                                }
                        },
                    )
                },
                { m ->
                    PdfStepperField(
                        stringResource(R.string.pdf_height),
                        text.height / f,
                        unitLabel,
                        step = 1.0,
                        min = 1.0 / f,
                        max = page.height / f,
                        enabled = !s.editorLocked,
                        modifier = m,
                        onValue = { n ->
                            if (n > 0)
                                vm.textEdit(text.id) {
                                    PdfGeometry.constrainTextToPage(it.copy(height = n * f), page)
                                }
                        },
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(GallerySpacing.Lg))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        var showAlign by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { showAlign = true }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_toolbar_align))
            }
            DropdownMenu(expanded = showAlign, onDismissRequest = { showAlign = false }) {
                alignEntries().forEach { (align, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = { showAlign = false; vm.alignSelectedText(align) },
                    )
                }
            }
        }
        var showLayer by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { showLayer = true }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_toolbar_layer))
            }
            DropdownMenu(expanded = showLayer, onDismissRequest = { showLayer = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_layer_forward)) },
                    onClick = { showLayer = false; vm.bringSelectedForward() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_layer_backward)) },
                    onClick = { showLayer = false; vm.sendSelectedBackward() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_front)) },
                    onClick = { showLayer = false; vm.bringSelectedToFront() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_backlayer)) },
                    onClick = { showLayer = false; vm.sendSelectedToBack() },
                )
            }
        }
        FilledTonalButton(
            onClick = vm::deleteSelected,
            enabled = !s.editorLocked,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(GalleryIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pdf_delete_text))
        }
    }
}

/**
 * Compact group inspector (Phase G2 item 4): shown in the "Adjust" tab/sheet slot in place of the
 * single-element inspector whenever 2+ elements are selected — element count, Align (relative to
 * the selection's own bounding box), Distribute (disabled with a reason below 3 selected), and
 * Delete (error role pair). Duplicate lives on the canvas's [PdfMultiSelectBar] only, to keep this
 * compact panel to what the plan calls out (count + Align/Distribute/Delete).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfGroupInspector(vm: PdfStudioViewModel, s: PdfStudioState) {
    val count = s.selectedIds.size
    Text(
        androidx.compose.ui.res.pluralStringResource(R.plurals.pdf_elements_selected, count, count),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(GallerySpacing.Lg))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        var showAlign by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { showAlign = true }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_toolbar_align))
            }
            DropdownMenu(expanded = showAlign, onDismissRequest = { showAlign = false }) {
                alignEntries().forEach { (align, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = {
                            showAlign = false
                            vm.alignGroupSelection(align)
                        },
                    )
                }
            }
        }
        var showDistribute by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { showDistribute = true }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.DistributeHorizontal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_distribute))
            }
            DropdownMenu(expanded = showDistribute, onDismissRequest = { showDistribute = false }) {
                val canDistribute = count >= 3
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_distribute_horizontal)) },
                    enabled = canDistribute,
                    onClick = {
                        showDistribute = false
                        vm.distributeGroupSelection(PdfSnapGuides.Orientation.Horizontal)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_distribute_vertical)) },
                    enabled = canDistribute,
                    onClick = {
                        showDistribute = false
                        vm.distributeGroupSelection(PdfSnapGuides.Orientation.Vertical)
                    },
                )
                if (!canDistribute)
                    Text(
                        stringResource(R.string.pdf_distribute_needs_three),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
            }
        }
        // Fix-round item 7: same validated errorContainer/onErrorContainer role pair as
        // PdfMultiSelectBar's own Delete action, not a plain TextButton with no error affordance.
        FilledTonalButton(
            onClick = vm::deleteGroupSelection,
            enabled = !s.editorLocked,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Text(stringResource(R.string.pdf_delete))
        }
    }
}

/** Left/Center/Right text alignment choice; extracted so Compose Driver can render it alone. */
@Composable
internal fun PdfTextAlignChoice(selected: PdfTextAlign, enabled: Boolean, onSelect: (PdfTextAlign) -> Unit) {
    com.librestatic.lightforge.core.designsystem.GalleryExpressiveChoiceGroup(
        labels =
            listOf(
                stringResource(R.string.pdf_align_left),
                stringResource(R.string.pdf_align_center),
                stringResource(R.string.pdf_align_right),
            ),
        selectedIndex = selected.ordinal,
        onSelect = { index -> onSelect(PdfTextAlign.entries[index]) },
        icons = listOf(GalleryIcons.FormatAlignLeft, GalleryIcons.FormatAlignCenter, GalleryIcons.FormatAlignRight),
        enabled = listOf(enabled, enabled, enabled),
        minimumItemWidth = 96.dp,
        wrap = true,
    )
}
