package com.ugallery.feature.trash

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import java.util.Date

private const val TRASH_THUMBNAIL_PX = 96

/** [visibleItems] is the bounded Paging window, never the complete trash selection. */
@Composable
fun TrashContent(
    visibleItems: List<TimelineMedia>,
    totalCount: Long,
    onRestore: (TimelineMedia) -> Unit,
    onDeletePermanently: (TimelineMedia) -> Unit,
    onEmptyTrash: () -> Unit,
    thumbnailLoader: ThumbnailLoader? = null,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showHeader) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.headlineSmall)
                    if (totalCount > 0) {
                        GalleryExpressiveButton(onClick = onEmptyTrash) { Text(stringResource(R.string.trash_empty)) }
                    }
                }
            }
            if (totalCount == 0L) {
                GalleryStateContent(
                    title = stringResource(R.string.trash_empty_title),
                    body = stringResource(R.string.trash_empty_state),
                    illustrationDescription = stringResource(R.string.trash_empty_description),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleItems, key = { "${it.key.volumeName}:${it.key.mediaStoreId}" }) { media ->
                        TrashCard(
                            media = media,
                            thumbnailLoader = thumbnailLoader,
                            onRestore = { onRestore(media) },
                            onDeletePermanently = { onDeletePermanently(media) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashCard(
    media: TimelineMedia,
    thumbnailLoader: ThumbnailLoader?,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    val context = LocalContext.current
    val request = ThumbnailRequest(
        mediaKey = media.key,
        generationModified = media.generationModified,
        widthPx = TRASH_THUMBNAIL_PX,
        heightPx = TRASH_THUMBNAIL_PX,
    )
    val bitmap by produceState(
        initialValue = thumbnailLoader?.cached(request),
        key1 = request,
        key2 = thumbnailLoader,
    ) {
        if (value == null && thumbnailLoader != null) {
            value = runCatching { thumbnailLoader.load(request) }.getOrNull()
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val loaded = bitmap
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (loaded != null) {
                    Image(
                        bitmap = loaded.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = if (media.kind == com.ugallery.core.model.MediaKind.Video) "\u25B6" else "\uD83D\uDDBC",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = media.displayName ?: stringResource(R.string.trash_unnamed_item),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = buildList {
                    if (media.sizeBytes > 0) add(Formatter.formatFileSize(context, media.sizeBytes))
                    add(
                        media.dateExpiresMillis?.let {
                            stringResource(
                                R.string.trash_expires_on,
                                DateFormat.getMediumDateFormat(context).format(Date(it)),
                            )
                        } ?: stringResource(R.string.trash_expiry_unknown),
                    )
                }
                Text(
                    text = details.joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.trash_restore)) }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(modifier = Modifier.width(56.dp))
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onDeletePermanently,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.trash_delete_permanently))
                }
            }
        }
    }
}
