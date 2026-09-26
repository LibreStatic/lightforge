package com.ugallery.feature.collections

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryIndeterminateProgressIndicator
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryTopAppBar

private const val PeopleThumbSize = 256

data class PersonCardUi(
    val clusterId: String,
    val displayName: String?,
    val memberCount: Int,
    val coverKey: MediaKey?,
)

data class PersonMemberCardUi(
    val key: MediaKey,
    val faceOrdinal: Int,
)

data class LocalMeUiState(
    val referenceCount: Int = 0,
    val ready: Boolean = false,
    val matchCount: Int = 0,
    val matches: List<PersonMemberCardUi> = emptyList(),
)

data class PeopleUiState(
    val consentGranted: Boolean = false,
    val paused: Boolean = false,
    val running: Boolean = false,
    val waiting: Boolean = false,
    val analysisStage: PeopleAnalysisStage = PeopleAnalysisStage.Idle,
    val completedItems: Long = 0,
    val people: List<PersonCardUi> = emptyList(),
    val selectedPerson: PersonCardUi? = null,
    val selectedMembers: List<PersonMemberCardUi> = emptyList(),
    val me: LocalMeUiState? = null,
    val hiddenPeople: List<PersonCardUi> = emptyList(),
)

enum class PeopleAnalysisStage { Idle, FaceDetection, FaceEmbeddings, PersonClustering, Complete, Paused }

@Composable
fun PeopleContent(
    state: PeopleUiState,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyzeAll: () -> Unit,
    onDeleteAll: () -> Unit,
    onPersonClick: (String) -> Unit,
    onRenamePerson: (String, String?) -> Unit,
    onHidePerson: (String) -> Unit,
    onSetSelectedAsMe: (String) -> Unit,
    onResetMe: () -> Unit,
    modifier: Modifier = Modifier,
    onMemberClick: (MediaKey) -> Unit = {},
    onLoadMoreMembers: () -> Unit = {},
    onMergePerson: (sourceClusterId: String, targetClusterId: String) -> Unit = { _, _ -> },
    onSplitFaces: (clusterId: String, faces: List<PersonMemberCardUi>) -> Unit = { _, _ -> },
    onUnhidePerson: (String) -> Unit = {},
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var splitting by rememberSaveable(state.selectedPerson?.clusterId) { mutableStateOf(false) }
    var splitSelection by remember(state.selectedPerson?.clusterId) { mutableStateOf(setOf<PersonMemberCardUi>()) }
    val fullSpan: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = {
        GridItemSpan(maxLineSpan)
    }
    Column(modifier.fillMaxSize()) {
        GalleryTopAppBar(
            title = stringResource(R.string.people_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.people_back),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        item(span = fullSpan) { Text(stringResource(R.string.people_noncommercial_notice), style = MaterialTheme.typography.bodyMedium) }
        item(span = fullSpan) { Text(stringResource(R.string.people_privacy), color = MaterialTheme.colorScheme.primary) }
        if (!state.consentGranted) {
            item(span = fullSpan) { GalleryExpressiveButton(onClick = onEnable, modifier = Modifier.testTag("people_enable")) {
                Text(stringResource(R.string.people_enable))
            } }
        } else {
            if (state.running || state.waiting) item(span = fullSpan) { GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth()) }
            item(span = fullSpan) { Text(peopleStatusText(state), style = MaterialTheme.typography.titleMedium) }
            item(span = fullSpan) { Text(stringResource(R.string.people_progress, state.completedItems)) }
            item(span = fullSpan) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryExpressiveButton(onClick = if (state.paused) onResume else onPause) {
                    Text(stringResource(if (state.paused) R.string.people_resume else R.string.people_pause))
                }
                OutlinedButton(
                    onClick = onAnalyzeAll,
                    enabled = !state.running && !state.waiting,
                ) { Text(stringResource(R.string.people_analyze_all)) }
            } }
            item(span = fullSpan) { TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.people_delete_all), color = MaterialTheme.colorScheme.error)
            } }
        }
        item(span = fullSpan) { MeSection(state.me, thumbnailLoader, onResetMe) }
        val selected = state.selectedPerson
        if (selected == null) {
            if (state.people.isEmpty()) item(span = fullSpan) {
                Text(
                    stringResource(
                        if (state.analysisStage == PeopleAnalysisStage.Complete) R.string.people_empty_complete
                        else R.string.people_empty,
                    ),
                    modifier = Modifier.testTag("people_empty"),
                )
            } else items(state.people, key = { it.clusterId }) { person ->
                PersonCard(person, thumbnailLoader) { onPersonClick(person.clusterId) }
            }
            if (state.hiddenPeople.isNotEmpty()) {
                item(span = fullSpan) {
                    Text(
                        stringResource(R.string.people_hidden_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                items(state.hiddenPeople, key = { "hidden:${it.clusterId}" }, span = { fullSpan() }) { person ->
                    Row(
                        Modifier.fillMaxWidth().testTag("hidden_person_${person.clusterId}"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        PersonThumbnail(person.coverKey, thumbnailLoader, Modifier.size(56.dp))
                        Text(
                            person.displayName ?: stringResource(R.string.people_default_name),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onUnhidePerson(person.clusterId) }) { Text(stringResource(R.string.people_unhide)) }
                    }
                }
            }
        } else {
            item(span = fullSpan) {
                PersonDetail(
                    selected, thumbnailLoader, onRenamePerson, onHidePerson, onSetSelectedAsMe,
                    canMerge = state.people.any { it.clusterId != selected.clusterId },
                    onMerge = { merging = true },
                    splitting = splitting,
                    splitCount = splitSelection.size,
                    // A split must leave at least one face with this person.
                    canSplit = splitSelection.isNotEmpty() && splitSelection.size < state.selectedMembers.size,
                    onStartSplit = { splitting = true; splitSelection = emptySet() },
                    onConfirmSplit = {
                        onSplitFaces(selected.clusterId, splitSelection.toList())
                        splitting = false
                        splitSelection = emptySet()
                    },
                    onCancelSplit = { splitting = false; splitSelection = emptySet() },
                )
            }
            itemsIndexed(state.selectedMembers, key = { _, it -> "${it.key}:${it.faceOrdinal}" }) { index, member ->
                if (index == state.selectedMembers.lastIndex) LaunchedEffect(state.selectedMembers.size) { onLoadMoreMembers() }
                PersonMemberTile(
                    member,
                    thumbnailLoader,
                    selectable = splitting,
                    selected = member in splitSelection,
                    onClick = {
                        if (splitting) splitSelection = if (member in splitSelection) splitSelection - member else splitSelection + member
                        else onMemberClick(member.key)
                    },
                )
            }
        }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.people_delete_title)) },
        text = { Text(stringResource(R.string.people_delete_body)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDeleteAll() }) {
                Text(stringResource(R.string.people_delete_confirm))
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.people_cancel)) } },
    )
    val mergeSource = state.selectedPerson
    if (merging && mergeSource != null) AlertDialog(
        onDismissRequest = { merging = false },
        title = { Text(stringResource(R.string.people_merge_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.people_merge_body))
                state.people.filter { it.clusterId != mergeSource.clusterId }.forEach { target ->
                    TextButton(
                        onClick = { merging = false; onMergePerson(mergeSource.clusterId, target.clusterId) },
                        modifier = Modifier.fillMaxWidth().testTag("merge_target_${target.clusterId}"),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            PersonThumbnail(target.coverKey, thumbnailLoader, Modifier.size(40.dp))
                            Text(
                                target.displayName ?: stringResource(R.string.people_default_name),
                                modifier = Modifier.weight(1f),
                            )
                            Text(stringResource(R.string.people_face_count, target.memberCount))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { merging = false }) { Text(stringResource(R.string.people_cancel)) } },
    )
}

@Composable
private fun MeSection(me: LocalMeUiState?, thumbnailLoader: ThumbnailLoader?, onResetMe: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("me_section")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.me_title), style = MaterialTheme.typography.titleLarge)
            if (me == null) {
                Text(stringResource(R.string.me_empty))
            } else {
                Text(stringResource(R.string.me_summary, me.referenceCount, me.matchCount))
                if (!me.ready) GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                if (me.matches.isNotEmpty()) ThumbnailStrip(me.matches.take(6), thumbnailLoader)
                TextButton(onClick = onResetMe) { Text(stringResource(R.string.me_reset)) }
            }
        }
    }
}

@Composable
private fun PeopleGrid(people: List<PersonCardUi>, loader: ThumbnailLoader?, onPersonClick: (String) -> Unit) {
    if (people.isEmpty()) {
        Text(stringResource(R.string.people_empty), modifier = Modifier.testTag("people_empty"))
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxWidth().height(520.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(people, key = { it.clusterId }) { person ->
            PersonCard(person, loader) { onPersonClick(person.clusterId) }
        }
    }
}

@Composable
private fun PersonCard(person: PersonCardUi, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val title = person.displayName ?: stringResource(R.string.people_default_name)
    Card(Modifier.fillMaxWidth().testTag("person_${person.clusterId}").clickable(onClick = onClick).semantics {
        contentDescription = "$title. ${person.memberCount}"
    }) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonThumbnail(person.coverKey, loader, Modifier.fillMaxWidth().height(120.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.people_face_count, person.memberCount))
        }
    }
}

@Composable
private fun peopleStatusText(state: PeopleUiState): String = when {
    state.paused || state.analysisStage == PeopleAnalysisStage.Paused -> stringResource(R.string.people_status_paused)
    state.analysisStage == PeopleAnalysisStage.Complete -> stringResource(R.string.people_status_complete)
    state.waiting && state.analysisStage == PeopleAnalysisStage.FaceDetection -> stringResource(R.string.people_status_preparing_faces)
    state.waiting && state.analysisStage == PeopleAnalysisStage.FaceEmbeddings -> stringResource(R.string.people_status_preparing_embeddings)
    state.waiting && state.analysisStage == PeopleAnalysisStage.PersonClustering -> stringResource(R.string.people_status_preparing_groups)
    state.analysisStage == PeopleAnalysisStage.FaceDetection -> stringResource(R.string.people_status_detecting_faces)
    state.analysisStage == PeopleAnalysisStage.FaceEmbeddings -> stringResource(R.string.people_status_building_embeddings)
    state.analysisStage == PeopleAnalysisStage.PersonClustering -> stringResource(R.string.people_status_grouping_people)
    else -> stringResource(R.string.people_status_ready)
}

@Composable
private fun PersonDetail(
    person: PersonCardUi,
    loader: ThumbnailLoader?,
    onRename: (String, String?) -> Unit,
    onHide: (String) -> Unit,
    onSetAsMe: (String) -> Unit,
    canMerge: Boolean,
    onMerge: () -> Unit,
    splitting: Boolean,
    splitCount: Int,
    canSplit: Boolean,
    onStartSplit: () -> Unit,
    onConfirmSplit: () -> Unit,
    onCancelSplit: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(person.clusterId) { mutableStateOf(person.displayName.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(person.displayName ?: stringResource(R.string.people_default_name), style = MaterialTheme.typography.titleLarge)
        if (splitting) {
            Text(stringResource(R.string.people_split_hint))
            GalleryExpressiveButton(onClick = onConfirmSplit, enabled = canSplit, modifier = Modifier.testTag("people_split_confirm")) {
                Text(pluralStringResource(R.plurals.people_split_confirm, splitCount, splitCount))
            }
            TextButton(onClick = onCancelSplit) { Text(stringResource(R.string.people_cancel)) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryExpressiveButton(onClick = { onSetAsMe(person.clusterId) }) { Text(stringResource(R.string.me_set_from_person)) }
                OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.people_rename)) }
                OutlinedButton(onClick = onMerge, enabled = canMerge, modifier = Modifier.testTag("people_merge")) {
                    Text(stringResource(R.string.people_merge))
                }
                OutlinedButton(onClick = onStartSplit, modifier = Modifier.testTag("people_split")) {
                    Text(stringResource(R.string.people_split))
                }
                TextButton(onClick = { onHide(person.clusterId) }) { Text(stringResource(R.string.people_hide)) }
            }
        }
        if (renaming) {
            androidx.compose.material3.TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.people_name_label)) },
            )
            GalleryExpressiveButton(onClick = { onRename(person.clusterId, name); renaming = false }) {
                Text(stringResource(R.string.people_save_name))
            }
        }
    }
}

@Composable
private fun PersonMemberTile(
    member: PersonMemberCardUi,
    loader: ThumbnailLoader?,
    selectable: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.people_open_photo)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)
            .testTag("person_member_${member.key.mediaStoreId}_${member.faceOrdinal}")
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        PersonThumbnail(member.key, loader, Modifier.fillMaxSize())
        if (selectable) androidx.compose.material3.Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd),
        )
    }
}

@Composable
private fun ThumbnailStrip(items: List<PersonMemberCardUi>, loader: ThumbnailLoader?) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.take(12), key = { "${it.key}:${it.faceOrdinal}" }) { item ->
            PersonThumbnail(item.key, loader, Modifier.size(72.dp))
        }
    }
}

@Composable
private fun PersonThumbnail(key: MediaKey?, loader: ThumbnailLoader?, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = if (key == null || loader == null) null else runCatching {
            loader.load(ThumbnailRequest(key, 0, PeopleThumbSize, PeopleThumbSize)).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant))
    else Image(bitmap!!, contentDescription = null, modifier = modifier.clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
}

private val peoplePlaceholderBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
private fun peoplePlaceholderImage(): ImageBitmap = peoplePlaceholderBitmap.asImageBitmap()
