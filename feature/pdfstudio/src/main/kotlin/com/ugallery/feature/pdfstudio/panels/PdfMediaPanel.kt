package com.ugallery.feature.pdfstudio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryExpressiveChoiceGroup
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryStateContent

private enum class PdfMediaChip {
    All,
    Photos,
    Documents,
    InProject,
}

/**
 * Media tab (Phase F item 3): chips All / Photos / Documents / In this project, a thumbnail grid
 * and tap-to-insert into the current page. "In this project" never touches [PdfMediaSource]: it
 * lists the open project's own image assets and re-adds the chosen one by hash, with no copy (see
 * [PdfStudioViewModel.insertOwnAsset]). Every other chip queries [mediaSource]; callers must gate
 * showing this tab on it being non-null.
 */
@Composable
internal fun PdfMediaPanel(vm: PdfStudioViewModel, s: PdfStudioState, mediaSource: PdfMediaSource?) {
    val project = s.project ?: return
    var chip by rememberSaveable { mutableStateOf(PdfMediaChip.All) }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pdf_media), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val labels =
            listOf(
                stringResource(R.string.pdf_media_all),
                stringResource(R.string.pdf_media_photos),
                stringResource(R.string.pdf_media_documents),
                stringResource(R.string.pdf_media_in_project),
            )
        GalleryExpressiveChoiceGroup(
            labels = labels,
            selectedIndex = chip.ordinal,
            onSelect = { chip = PdfMediaChip.entries[it] },
            minimumItemWidth = 84.dp,
        )
        Spacer(Modifier.height(8.dp))
        if (chip == PdfMediaChip.InProject) {
            val ownItems = project.assets.filter { it.mime.startsWith("image/") }
            if (ownItems.isEmpty())
                GalleryStateContent(
                    title = stringResource(R.string.pdf_media_empty_title),
                    body = stringResource(R.string.pdf_media_empty_body),
                    illustrationDescription = stringResource(R.string.pdf_media_empty_title),
                )
            else
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ownItems, key = { it.hash }) { asset ->
                        var bitmap by remember(asset.hash) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(asset.hash) {
                            bitmap =
                                runCatching {
                                        vm.repository.imageBitmap(
                                            vm.repository.file(asset.hash),
                                            asset.orientation,
                                            256,
                                        )
                                    }
                                    .getOrNull()
                        }
                        val label = stringResource(R.string.pdf_media_item_label, stringResource(R.string.pdf_media_in_project))
                        val addLabel = stringResource(R.string.pdf_media_add_to_page)
                        PdfMediaThumbnail(
                            bitmap = bitmap,
                            contentDescription = label,
                            onClick = { vm.insertOwnAsset(asset.hash) },
                            addActionLabel = addLabel,
                        )
                    }
                }
        } else {
            val scope =
                when (chip) {
                    PdfMediaChip.Photos -> PdfMediaScope.Photos
                    PdfMediaChip.Documents -> PdfMediaScope.Documents
                    else -> PdfMediaScope.All
                }
            if (mediaSource == null) {
                GalleryStateContent(
                    title = stringResource(R.string.pdf_media_empty_title),
                    body = stringResource(R.string.pdf_media_empty_body),
                    illustrationDescription = stringResource(R.string.pdf_media_empty_title),
                )
            } else {
                val access by mediaSource.access.collectAsState()
                if (access.state == PdfMediaAccessState.Denied) {
                    GalleryStateContent(
                        title = access.message ?: stringResource(R.string.pdf_media_empty_title),
                        body = access.message ?: stringResource(R.string.pdf_media_empty_body),
                        illustrationDescription = stringResource(R.string.pdf_media_empty_title),
                        action =
                            access.actionLabel?.let { label ->
                                { TextButton(onClick = { access.onAction?.invoke() }) { Text(label) } }
                            },
                    )
                } else {
                    if (access.state == PdfMediaAccessState.Partial && access.message != null)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(access.message!!, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            access.actionLabel?.let { label ->
                                TextButton(onClick = { access.onAction?.invoke() }) { Text(label) }
                            }
                        }
                    val items by
                        remember(mediaSource, scope) { mediaSource.items(PdfMediaFilter(scope)) }
                            .collectAsState(initial = emptyList())
                    if (items.isEmpty())
                        GalleryStateContent(
                            title = stringResource(R.string.pdf_media_empty_title),
                            body = stringResource(R.string.pdf_media_empty_body),
                            illustrationDescription = stringResource(R.string.pdf_media_empty_title),
                        )
                    else
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 96.dp),
                            modifier = Modifier.heightIn(max = 360.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items, key = { it.key }) { item ->
                                var bitmap by remember(item.key) { mutableStateOf<Bitmap?>(null) }
                                LaunchedEffect(item.key) {
                                    bitmap = runCatching { mediaSource.thumbnail(item, 256) }.getOrNull()
                                }
                                val addLabel = stringResource(R.string.pdf_media_add_to_page)
                                val label = stringResource(R.string.pdf_media_item_label, item.displayName)
                                PdfMediaThumbnail(
                                    bitmap = bitmap,
                                    contentDescription = label,
                                    onClick = { vm.insertMedia(item.uri) },
                                    addActionLabel = addLabel,
                                    isDocument = item.isDocument,
                                    dragUri = item.uri,
                                )
                            }
                        }
                }
            }
        }
    }
}

/** One grid cell: 96dp target (well above the 48dp minimum), tap inserts, long-press drags onto
 * the canvas or a page-strip thumbnail (Compose's own `dragAndDropSource`, per the plan's "simpler
 * and robust" allowance), and a TalkBack custom action duplicates the tap for users who can't
 * drag. [dragUri] is null for "In this project" items, which are re-inserted by hash instead of by
 * URI (see [PdfStudioViewModel.insertOwnAsset]) and so are not drag sources (tap/custom action
 * only) in this first cut. */
@Composable
private fun PdfMediaThumbnail(
    bitmap: Bitmap?,
    contentDescription: String,
    onClick: () -> Unit,
    addActionLabel: String,
    isDocument: Boolean = false,
    dragUri: android.net.Uri? = null,
) {
    var modifier =
        Modifier.size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
    if (dragUri != null)
        modifier =
            modifier.dragAndDropSource { _ ->
                DragAndDropTransferData(
                    android.content.ClipData.newUri(null, "pdf-media", dragUri),
                    dragUri,
                    android.view.View.DRAG_FLAG_GLOBAL,
                )
            }
    Box(
        modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            onClick(label = addActionLabel) {
                onClick()
                true
            }
            customActions = listOf(CustomAccessibilityAction(addActionLabel) { onClick(); true })
        },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else
            Icon(
                if (isDocument) GalleryIcons.PictureAsPdf else GalleryIcons.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
