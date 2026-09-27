package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryIcons

/**
 * Pages panel (Phase D item 1): a grid of page thumbnails with numbers, the current page
 * outlined and bold (same non-color cue as the Phase C strip), long-press drag to reorder, and an
 * explicit selection mode ("Select" → "n selected", Select all / close, check badges, a
 * contextual Duplicate / Rotate / Delete bar in the error role). [columns] lets the caller give it
 * 1–2 columns in the expanded side rail and 3 in the compact bottom sheet.
 */
@Composable
internal fun PdfPagesPanel(
    vm: PdfStudioViewModel,
    s: PdfStudioState,
    delete: () -> Unit,
    columns: Int = 3,
) {
    val p = s.project ?: return
    val selectionMode = s.pagesSelectionMode
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (selectionMode) {
            Text(
                androidx.compose.ui.res.pluralStringResource(
                    R.plurals.pdf_pages_selected,
                    s.selectedPages.size,
                    s.selectedPages.size,
                ),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            val selectAllLabel = stringResource(R.string.pdf_select_all)
            IconButton(
                onClick = vm::selectAllPages,
                modifier = Modifier.semantics2(selectAllLabel),
            ) {
                Icon(GalleryIcons.SelectAll, contentDescription = null)
            }
            val closeLabel = stringResource(R.string.pdf_close)
            IconButton(
                onClick = { vm.setPagesSelectionMode(false) },
                modifier = Modifier.semantics2(closeLabel),
            ) {
                Icon(GalleryIcons.Close, contentDescription = null)
            }
        } else {
            Text(
                stringResource(R.string.pdf_pages),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { vm.setPagesSelectionMode(true) }, enabled = !s.editorLocked) {
                Icon(GalleryIcons.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pdf_select_mode))
            }
        }
    }

    if (selectionMode) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::duplicateSelectedPages, enabled = !s.editorLocked) {
                Text(stringResource(R.string.pdf_duplicatepage))
            }
            OutlinedButton(onClick = vm::rotateSelectedPages, enabled = !s.editorLocked) {
                Text(stringResource(R.string.pdf_rotatepage))
            }
            // A validated Material role pair (errorContainer/onErrorContainer), matching the
            // canvas contextual toolbar's own Delete action.
            Button(
                onClick = delete,
                enabled = !s.editorLocked,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Text(stringResource(R.string.pdf_delete))
            }
        }
    }

    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        state = gridState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(p.pages, key = { it.id }) { page ->
            val index = p.pages.indexOf(page)
            val isCurrent = index == s.page
            val isSelected = page.id in s.selectedPages
            val thumbLabel =
                if (isCurrent) stringResource(R.string.pdf_page_thumb_selected, index + 1, p.pages.size)
                else stringResource(R.string.pdf_page_indicator, index + 1, p.pages.size)
            val moveBeforeLabel = stringResource(R.string.pdf_pagebefore)
            val moveAfterLabel = stringResource(R.string.pdf_pageafter)
            Column(
                Modifier.then(if (dragging == page.id) Modifier else Modifier.animateItem())
                    .graphicsLayer {
                        translationX = if (dragging == page.id) dragOffsetX else 0f
                        translationY = if (dragging == page.id) dragOffsetY else 0f
                        shadowElevation = if (dragging == page.id) 8f else 0f
                    }
                    .then(
                        if (isCurrent)
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        else Modifier
                    )
                    .clearAndSetSemantics {
                        contentDescription = thumbLabel
                        selected = isCurrent
                        role = Role.Button
                        onClick(label = null) {
                            if (selectionMode) vm.selectExportPage(page.id, page.id !in s.selectedPages)
                            else vm.selectPage(index)
                            true
                        }
                        if (!selectionMode) {
                            customActions =
                                buildList {
                                    if (index > 0)
                                        add(CustomAccessibilityAction(moveBeforeLabel) { vm.reorderPage(index, index - 1); true })
                                    if (index < p.pages.lastIndex)
                                        add(CustomAccessibilityAction(moveAfterLabel) { vm.reorderPage(index, index + 1); true })
                                }
                        }
                    }
                    .clickable(enabled = !s.editorLocked) {
                        if (selectionMode) vm.selectExportPage(page.id, page.id !in s.selectedPages)
                        else vm.selectPage(index)
                    }
                    .then(
                        if (selectionMode || s.editorLocked) Modifier
                        else
                            Modifier.pointerInput(page.id, p.pages.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = page.id
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        val visible = gridState.layoutInfo.visibleItemsInfo
                                        val self = visible.firstOrNull { it.key == page.id }
                                        val target =
                                            if (self != null) {
                                                val cx = self.offset.x + dragOffsetX + self.size.width / 2
                                                val cy = self.offset.y + dragOffsetY + self.size.height / 2
                                                visible
                                                    .filter { it.key is String }
                                                    .minByOrNull { item ->
                                                        val icx = item.offset.x + item.size.width / 2
                                                        val icy = item.offset.y + item.size.height / 2
                                                        (icx - cx) * (icx - cx) + (icy - cy) * (icy - cy)
                                                    }
                                                    ?.let { p.pages.indexOfFirst { pg -> pg.id == it.key } }
                                                    ?: index
                                            } else index
                                        dragging = null
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                        if (target != index) vm.reorderPage(index, target)
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                    },
                                ) { change, drag ->
                                    change.consume()
                                    dragOffsetX += drag.x
                                    dragOffsetY += drag.y
                                }
                            }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    PageThumbnail(page, vm, Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height.toFloat()))
                    if (selectionMode) {
                        val checkLabel =
                            if (isSelected) stringResource(R.string.pdf_page_check_selected)
                            else stringResource(R.string.pdf_page_check_unselected)
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(24.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(50),
                                )
                                .semantics2(checkLabel),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected)
                                Icon(
                                    GalleryIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                        }
                    }
                }
                Text(
                    "${index + 1}",
                    style =
                        if (isCurrent) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        else MaterialTheme.typography.labelSmall,
                    color =
                        if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "add-page") {
            val atLimit = p.pages.size >= 100
            val addLabel = stringResource(R.string.pdf_addpage)
            Column(
                Modifier.fillMaxWidth()
                    .aspectRatio(0.72f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(enabled = !s.editorLocked && !atLimit, onClick = vm::addPage)
                    .semantics2(addLabel),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(GalleryIcons.Plus, contentDescription = null, modifier = Modifier.align(Alignment.CenterHorizontally))
                if (atLimit)
                    Text(
                        stringResource(R.string.pdf_addpage_limit),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
            }
        }
    }

    val currentPage = p.pages.getOrNull(s.page)
    if (!selectionMode && currentPage?.source != null) {
        Text(
            stringResource(R.string.pdf_imported_page_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (!selectionMode) {
        FlowRow(Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { vm.setPagesSelectionMode(true) }, enabled = !s.editorLocked) {
                Text(stringResource(R.string.pdf_select_mode))
            }
            TextButton(onClick = vm::duplicatePage, enabled = !s.editorLocked && p.pages.size < 100) {
                Text(stringResource(R.string.pdf_duplicatepage))
            }
            TextButton(onClick = delete, enabled = !s.editorLocked) {
                Text(stringResource(R.string.pdf_removepage))
            }
        }
    }
}

/**
 * Adds a contentDescription without clearing whatever semantics the node already contributes
 * (notably a [Modifier.clickable]'s own click action/flag, which uiautomator's tap-by-label
 * scripts rely on) — unlike [androidx.compose.ui.semantics.clearAndSetSemantics], which replaces
 * the whole subtree.
 */
internal fun Modifier.semantics2(description: String): Modifier =
    this.then(Modifier.semantics { contentDescription = description })
