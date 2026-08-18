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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest

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
    val completedItems: Long = 0,
    val people: List<PersonCardUi> = emptyList(),
    val selectedPerson: PersonCardUi? = null,
    val selectedMembers: List<PersonMemberCardUi> = emptyList(),
    val me: LocalMeUiState? = null,
)

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
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.people_back)) }
            Text(
                stringResource(R.string.people_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(stringResource(R.string.people_noncommercial_notice), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.people_privacy), color = MaterialTheme.colorScheme.primary)
        if (!state.consentGranted) {
            Button(onClick = onEnable, modifier = Modifier.testTag("people_enable")) {
                Text(stringResource(R.string.people_enable))
            }
        } else {
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.people_progress, state.completedItems))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (state.paused) onResume else onPause) {
                    Text(stringResource(if (state.paused) R.string.people_resume else R.string.people_pause))
                }
                OutlinedButton(onClick = onAnalyzeAll) { Text(stringResource(R.string.people_analyze_all)) }
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.people_delete_all), color = MaterialTheme.colorScheme.error)
            }
        }
        MeSection(state.me, thumbnailLoader, onResetMe)
        val selected = state.selectedPerson
        if (selected == null) {
            PeopleGrid(state.people, thumbnailLoader, onPersonClick)
        } else {
            PersonDetail(selected, state.selectedMembers, thumbnailLoader, onRenamePerson, onHidePerson, onSetSelectedAsMe)
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
                if (!me.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
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
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics {
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
private fun PersonDetail(
    person: PersonCardUi,
    members: List<PersonMemberCardUi>,
    loader: ThumbnailLoader?,
    onRename: (String, String?) -> Unit,
    onHide: (String) -> Unit,
    onSetAsMe: (String) -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(person.clusterId) { mutableStateOf(person.displayName.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(person.displayName ?: stringResource(R.string.people_default_name), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSetAsMe(person.clusterId) }) { Text(stringResource(R.string.me_set_from_person)) }
            OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.people_rename)) }
            TextButton(onClick = { onHide(person.clusterId) }) { Text(stringResource(R.string.people_hide)) }
        }
        if (renaming) {
            androidx.compose.material3.TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.people_name_label)) },
            )
            Button(onClick = { onRename(person.clusterId, name); renaming = false }) {
                Text(stringResource(R.string.people_save_name))
            }
        }
        ThumbnailStrip(members, loader)
    }
}

@Composable
private fun ThumbnailStrip(items: List<PersonMemberCardUi>, loader: ThumbnailLoader?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(6).forEach { item -> PersonThumbnail(item.key, loader, Modifier.size(72.dp)) }
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
