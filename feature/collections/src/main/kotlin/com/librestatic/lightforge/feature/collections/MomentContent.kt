package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.database.MomentEntity
import com.librestatic.lightforge.core.database.MomentMemberEntity
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.galleryWindowClass
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator

data class MomentMemberUi(
    val member: MomentMemberEntity,
    val key: MediaKey,
    val generationModified: Long = member.generationModifiedAtSelection,
)

/** Playback identity is independent from persistent ordinals, including when visibility changes. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    onMemoryControls: (() -> Unit)? = null,
    onMakeVideo: (() -> Unit)? = null,
    makeVideoLabel: String? = null,
    modifier: Modifier = Modifier,
    momentPlaceLabels: ((String) -> kotlinx.coroutines.flow.Flow<String?>)? = null,
    onParticipants: (() -> Unit)? = null,
) {
    // Old-route emissions must never display another moment's photos during navigation.
    val visible = members.filter { it.member.momentId == moment.momentId }
    var activeVolume by rememberSaveable(moment.momentId) { mutableStateOf<String?>(null) }
    var activeId by rememberSaveable(moment.momentId) { mutableStateOf<Long?>(null) }
    val index =
        visible
            .indexOfFirst { it.key.volumeName == activeVolume && it.key.mediaStoreId == activeId }
            .coerceAtLeast(0)
    val current = visible.getOrNull(index)
    fun select(member: MomentMemberUi) {
        activeVolume = member.key.volumeName
        activeId = member.key.mediaStoreId
    }
    // Commit a fallback after a removal, so a later reappearance does not jump playback backwards.
    LaunchedEffect(current?.key) { current?.let(::select) }
    var renaming by rememberSaveable(moment.momentId) { mutableStateOf(false) }
    var editTitle by rememberSaveable(moment.momentId) { mutableStateOf(moment.title.orEmpty()) }
    var deleting by rememberSaveable(moment.momentId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(renaming) { if (renaming) listState.animateScrollToItem(1) }
    val summaryBlock: @Composable () -> Unit = {
        Column {
            Text(dateLabel)
            Text(stateLabel)
        }
    }
    val participantsBlock: @Composable () -> Unit = {
        if (onParticipants != null) {
            TextButton(onClick = onParticipants, modifier = Modifier.testTag("moment-participants-edit")) {
                Text(stringResource(R.string.moment_participants_title))
            }
        }
    }
    val makeVideoBlock: @Composable () -> Unit = {
        if (onMakeVideo != null && makeVideoLabel != null) {
            Button(onClick = onMakeVideo, enabled = current != null, modifier = Modifier.fillMaxWidth().testTag("moment-make-video")) {
                Text(makeVideoLabel)
            }
        }
    }
    val memoryControlsBlock: @Composable () -> Unit = {
        if (onMemoryControls != null) {
            TextButton(onClick = onMemoryControls, modifier = Modifier.testTag("moment-memory-controls")) {
                Text(stringResource(R.string.memory_controls_title))
            }
        }
    }
    val slideBlock: @Composable () -> Unit = {
        if (current != null) {
            Column {
                MemoryImage(
                    current,
                    thumbnailLoader,
                    Modifier.fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .testTag("moment-slide"),
                )
                Text(
                    stringResource(
                        R.string.moment_story_position,
                        index + 1,
                        visible.size,
                    ),
                    Modifier.testTag("moment-position"),
                )
                GalleryProgressIndicator(
                    progress = { (index + 1f) / visible.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        } else {
            Text(
                stringResource(R.string.memory_empty),
                Modifier.testTag("moment-empty"),
            )
        }
    }
    val playbackBlock: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { visible.getOrNull(index - 1)?.let(::select) },
                enabled = current != null && index > 0,
                modifier = Modifier.testTag("moment-previous"),
            ) {
                Text(stringResource(R.string.memory_previous))
            }
            TextButton(
                onClick = { visible.getOrNull(index + 1)?.let(::select) },
                enabled = current != null && index < visible.lastIndex,
                modifier = Modifier.testTag("moment-next"),
            ) {
                Text(stringResource(R.string.memory_next))
            }
        }
    }
    val organizeBlock: @Composable () -> Unit = {
        if (current != null) {
            Column {
                Text(
                    stringResource(R.string.memory_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth()) {
                    for (delta in listOf(-1, 1)) TextButton(
                        onClick = {
                            val order = visible.map { it.key }.toMutableList()
                            order.add(index + delta, order.removeAt(index))
                            onReorder(order)
                        },
                        enabled =
                            if (delta < 0) index > 0 else index < visible.lastIndex,
                        modifier =
                            Modifier.weight(1f)
                                .testTag(
                                    if (delta < 0) "moment-move-earlier"
                                    else "moment-move-later"
                                ),
                    ) {
                        Text(
                            stringResource(
                                if (delta < 0) R.string.moment_move_previous
                                else R.string.moment_move_next
                            )
                        )
                    }
                }
                TextButton(
                    onClick = { onSetCover(current.member.ordinal) },
                    modifier = Modifier.testTag("moment-set-cover"),
                ) {
                    Text(stringResource(R.string.memory_set_cover))
                }
            }
        }
    }
    val wide = galleryWindowClass(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp) != GalleryWindowClass.Compact
    val tileColumns = if (wide) 4 else 3
    val renameBlock: @Composable () -> Unit = {
        Column {
            OutlinedTextField(
                editTitle,
                { editTitle = it.take(80) },
                Modifier.fillMaxWidth().testTag("moment-title"),
                label = { Text(stringResource(R.string.moment_edit_title)) },
                singleLine = true,
            )
            Row {
                TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.moment_cancel))
                }
                TextButton(
                    onClick = {
                        onRename(editTitle.trim())
                        renaming = false
                    },
                    enabled = editTitle.isNotBlank(),
                    modifier = Modifier.testTag("moment-title-save"),
                ) {
                    Text(stringResource(R.string.moment_save_title))
                }
            }
        }
    }
    val actionsBlock: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { deleting = true },
                modifier = Modifier.testTag("moment-delete"),
            ) {
                Text(stringResource(R.string.moment_delete))
            }
            TextButton(
                onClick = onSave,
                enabled = current != null,
                modifier = Modifier.testTag("moment-save"),
            ) {
                Text(stringResource(R.string.moment_save))
            }
        }
    }
    val gridItems: LazyListScope.() -> Unit = {
        itemsIndexed(visible.chunked(tileColumns), key = { row, _ -> "tiles:$row" }) { _, row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { member ->
                    val description =
                        stringResource(
                            R.string.memory_select_photo,
                            visible.indexOf(member) + 1,
                        )
                    Surface(
                        Modifier.weight(1f)
                            .aspectRatio(1f)
                            .testTag("moment-photo-${member.member.ordinal}")
                            .semantics { contentDescription = description }
                            .clickable { select(member) },
                        color =
                            if (member.key == current?.key)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor =
                            if (member.key == current?.key)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        MemoryImage(
                            member,
                            thumbnailLoader,
                            Modifier.padding(4.dp).fillMaxSize(),
                        )
                    }
                }
                repeat(tileColumns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val topBar: @Composable () -> Unit = {
        GalleryTopAppBar(
            title = momentDisplayTitle(moment, momentPlaceLabels),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.memory_back),
            actions = {
                TextButton(
                    onClick = {
                        editTitle = moment.title.orEmpty()
                        renaming = true
                    },
                    modifier = Modifier.testTag("moment-edit"),
                ) {
                    Text(stringResource(R.string.moment_edit))
                }
            },
        )
    }
    Surface(
        modifier.fillMaxSize().testTag("moment-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = if (wide) 1_200.dp else 720.dp).fillMaxSize()) {
                topBar()
                if (wide) {
                    // Two panes: player, details and actions on the start side, the ordered grid on the end.
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            summaryBlock()
                            if (renaming) renameBlock()
                            slideBlock()
                            playbackBlock()
                            participantsBlock()
                            makeVideoBlock()
                            memoryControlsBlock()
                            organizeBlock()
                            actionsBlock()
                        }
                        LazyColumn(
                            Modifier.weight(1f).fillMaxHeight().testTag("moment-list"),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = { gridItems() },
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f).testTag("moment-list"),
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item("summary") { summaryBlock() }
                        if (renaming) item("rename") { renameBlock() }
                        if (onParticipants != null) item("participants") { participantsBlock() }
                        if (onMakeVideo != null && makeVideoLabel != null) item("make-video") { makeVideoBlock() }
                        if (onMemoryControls != null) item("memory-controls") { memoryControlsBlock() }
                        item("slide") { slideBlock() }
                        item("playback") { playbackBlock() }
                        if (current != null) item("organize") { organizeBlock() }
                        gridItems()
                        item("actions") { actionsBlock() }
                    }
                }
            }
        }
    }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_body)) },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(R.string.moment_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("moment-delete-confirm"),
                ) {
                    Text(stringResource(R.string.moment_delete))
                }
            },
        )
}

@Composable
private fun MemoryImage(member: MomentMemberUi, loader: ThumbnailLoader?, modifier: Modifier) {
    key(member.key, member.generationModified, loader) {
        val bitmap by
            produceState<ImageBitmap?>(null, member.key, member.generationModified, loader) {
                if (loader != null)
                    try {
                        value =
                            loader
                                .load(
                                    ThumbnailRequest(
                                        member.key,
                                        member.generationModified,
                                        384,
                                        384,
                                    )
                                )
                                .asImageBitmap()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        value = null
                    }
            }
        Box(modifier, contentAlignment = Alignment.Center) {
            if (bitmap != null)
                Image(
                    bitmap!!,
                    contentDescription = null,
                    modifier =
                        Modifier.fillMaxSize()
                            .testTag(
                                "moment-image-loaded-${member.key.volumeName}:${member.key.mediaStoreId}-${member.generationModified}"
                            ),
                    contentScale = ContentScale.Crop,
                )
            else
                Icon(GalleryIcons.Image, contentDescription = null, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
fun MomentUnavailableContent(onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(
                title = stringResource(R.string.collections_moments),
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.memory_back),
            )
            Text(stringResource(R.string.memory_unavailable), Modifier.padding(24.dp))
        }
    }
}

fun formatMomentDateRange(startMillis: Long, endMillis: Long): String =
    DateFormat.getDateInstance().format(Date(startMillis)) +
        " · " +
        DateFormat.getDateInstance().format(Date(endMillis))

fun MomentContentPreviewData(): List<MomentMemberUi> = emptyList()
