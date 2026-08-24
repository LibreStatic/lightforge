package com.ugallery.feature.collections

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentMemberEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryTopAppBar
import java.text.DateFormat
import java.util.Date

private const val ThumbnailSize = 256
private const val StorySlotCount = 4
private val StorySlideHeight = 280.dp
private val TileHeight = 100.dp
private val TileSpacing = 8.dp

data class MomentMemberUi(
    val member: MomentMemberEntity,
    val key: MediaKey,
)

@Composable
fun MomentContent(
    moment: MomentEntity,
    members: List<MomentMemberUi>,
    thumbnailLoader: ThumbnailLoader?,
    dateLabel: String,
    stateLabel: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onSetCover: (Int) -> Unit,
    onReorder: (List<MediaKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var storyIndex by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(moment.title ?: "") }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        Modifier.fillMaxSize().widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(TileSpacing),
      ) {
        item {
            GalleryTopAppBar(
                title = moment.title ?: stringResource(R.string.moment_untitled),
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.moment_cancel),
                actions = { TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.moment_edit)) } },
            )
        }
        item { HorizontalScrollRow(dateLabel, stateLabel) }
        if (renaming) {
            item { RenameDialog(editTitle, { editTitle = it }, { onRename(editTitle); renaming = false }) { renaming = false } }
        }
        if (members.isNotEmpty()) {
            val current = members[storyIndex]
            item { StorySlide(current.key, thumbnailLoader, current.member.ordinal + 1, members.size) }
            item { StoryProgress(current.member.ordinal + 1, members.size) }
        }
        item { StoryNav(storyIndex, members.size, onMove = { delta ->
            val target = (storyIndex + delta).coerceIn(0, members.lastIndex)
            if (target != storyIndex) {
                val reordered = members.map { it.key }.toMutableList()
                val moved = reordered.removeAt(storyIndex)
                reordered.add(target, moved)
                onReorder(reordered)
                storyIndex = target
            }
        }) {
            storyIndex = if (storyIndex >= members.lastIndex - StorySlotCount + 1) 0
                else (storyIndex + StorySlotCount).coerceAtMost(members.lastIndex)
        } }
        itemsIndexed(
            members.chunked(3),
            key = { index, row -> "member-row:$index:${row.firstOrNull()?.key}" },
        ) { _, rowMembers ->
            ReorderRow(rowMembers, storyIndex, thumbnailLoader) { index ->
                if (index == storyIndex) onSetCover(index) else storyIndex = index
            }
        }
        item { BottomActions(onDelete, onSave) }
      }
    }
}

@Composable
private fun HorizontalScrollRow(dateLabel: String, stateLabel: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(dateLabel, style = MaterialTheme.typography.bodyLarge)
        Text(stateLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenameDialog(value: String, onValueChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.moment_edit_title)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.moment_cancel)) }
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.moment_save_title)) }
        }
    }
}

@Composable
private fun StorySlide(key: MediaKey, loader: ThumbnailLoader?, current: Int, total: Int) {
    Column(Modifier.fillMaxWidth().aspectRatio(4f / 3f), horizontalAlignment = Alignment.CenterHorizontally) {
        MediaThumbnail(key, loader, Modifier.fillMaxWidth().weight(1f))
        Text(stringResource(R.string.moment_story_position, current, total), Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StoryProgress(current: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { index ->
            Box(Modifier.height(4.dp).weight(1f).background(
                if (index < current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .35f),
            ))
        }
    }
}

@Composable
private fun StoryNav(index: Int, size: Int, onMove: (Int) -> Unit, onAdvance: () -> Unit) {
    val atEnd = index >= size - 1
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { onMove(-1) }, enabled = index > 0, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.moment_move_previous), maxLines = 2)
        }
        TextButton(onClick = onAdvance, enabled = !atEnd || index > 0, modifier = Modifier.weight(1f)) {
            Text(stringResource(if (atEnd) android.R.string.ok else android.R.string.search_go), maxLines = 2)
        }
        TextButton(onClick = { onMove(1) }, enabled = index < size - 1, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.moment_move_next), maxLines = 2)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReorderRow(rowMembers: List<MomentMemberUi>, currentIndex: Int, loader: ThumbnailLoader?, onClick: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(TileSpacing)) {
          rowMembers.forEach { member ->
            val ordinal = member.member.ordinal
            val isCurrent = ordinal == currentIndex
            val border = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(Modifier.weight(1f)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = { onClick(ordinal) }, onLongClick = { onClick(ordinal) })
                .padding(2.dp)
                .background(border, MaterialTheme.shapes.small),
            ) { MediaThumbnail(member.key, loader, Modifier.fillMaxSize()) }
          }
          repeat(3 - rowMembers.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
        }
}

@Composable
private fun BottomActions(onDelete: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = onDelete) { Text(stringResource(R.string.moment_delete)) }
        TextButton(onClick = onSave) { Text(stringResource(R.string.moment_save)) }
    }
}

@Composable
private fun MediaThumbnail(key: MediaKey, loader: ThumbnailLoader?, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        if (loader == null) return@LaunchedEffect
        bitmap = runCatching {
            loader.load(ThumbnailRequest(key, 0, ThumbnailSize, ThumbnailSize)).asImageBitmap()
        }.getOrNull()
    }
    Image(bitmap ?: defaultPlaceholderImage(), contentDescription = null, modifier = modifier.clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
}

private val placeholderBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
private fun defaultPlaceholderImage(): ImageBitmap = placeholderBitmap.asImageBitmap()

fun formatMomentDateRange(startMillis: Long, endMillis: Long): String =
    DateFormat.getDateInstance().format(Date(startMillis)) + " · " + DateFormat.getDateInstance().format(Date(endMillis))

fun MomentContentPreviewData(): List<MomentMemberUi> = emptyList()
