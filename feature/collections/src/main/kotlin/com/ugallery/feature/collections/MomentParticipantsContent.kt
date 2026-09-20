package com.ugallery.feature.collections

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.designsystem.GalleryIconUser
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

enum class MomentParticipantsMode { Automatic, Manual }

/** IDs and names come from current visible local identities, never from this editor. */
data class MomentParticipantsSnapshot(
    val momentId: String,
    val revision: Long,
    val mode: MomentParticipantsMode,
    val selectedIds: Set<String>,
    val people: List<PersonCardUi>,
    val automaticIds: Set<String>,
    val coverGenerations: Map<String, Long> = emptyMap(),
)

data class MomentParticipantsApplyRequest(
    val momentId: String,
    val expectedRevision: Long,
    val mode: MomentParticipantsMode,
    val selectedIds: Set<String>,
)

/** Root must CAS revision and revalidate every ID in the same transaction as the write. */
enum class MomentParticipantsApplyResult { Saved, Conflict, Unavailable, Failed }

internal data class MomentParticipantsDraft(
    val momentId: String,
    val revision: Long,
    val mode: MomentParticipantsMode,
    val selectedIds: Set<String>,
    val reviewRequired: Boolean = false,
) {
    fun reconcile(snapshot: MomentParticipantsSnapshot): MomentParticipantsDraft {
        if (momentId != snapshot.momentId) return from(snapshot)
        val available = snapshot.people.map { it.clusterId }.toSet()
        val changed = revision != snapshot.revision || !available.containsAll(selectedIds) ||
            (mode == MomentParticipantsMode.Automatic && selectedIds != snapshot.automaticIds)
        return if (changed && !reviewRequired) copy(reviewRequired = true) else this
    }

    fun toggle(id: String, snapshot: MomentParticipantsSnapshot): MomentParticipantsDraft {
        if (mode != MomentParticipantsMode.Manual || snapshot.people.none { it.clusterId == id }) return this
        return copy(selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id)
    }

    fun chooseMode(next: MomentParticipantsMode, snapshot: MomentParticipantsSnapshot) = copy(
        mode = next,
        selectedIds = if (next == MomentParticipantsMode.Automatic) snapshot.automaticIds.toSet() else selectedIds,
    )

    fun request(snapshot: MomentParticipantsSnapshot): MomentParticipantsApplyRequest? {
        val current = reconcile(snapshot)
        if (current.reviewRequired || momentId != snapshot.momentId) return null
        return MomentParticipantsApplyRequest(momentId, revision, mode,
            if (mode == MomentParticipantsMode.Manual) selectedIds.toSet() else emptySet())
    }

    companion object {
        fun from(snapshot: MomentParticipantsSnapshot): MomentParticipantsDraft = MomentParticipantsDraft(
            snapshot.momentId, snapshot.revision, snapshot.mode,
            (if (snapshot.mode == MomentParticipantsMode.Manual) snapshot.selectedIds else snapshot.automaticIds).toSet(),
        ).reconcile(snapshot)

        /** Only an explicit Review action may discard unavailable IDs from this local draft. */
        fun reviewed(snapshot: MomentParticipantsSnapshot): MomentParticipantsDraft {
            val available = snapshot.people.map { it.clusterId }.toSet()
            return from(snapshot).copy(selectedIds =
                (if (snapshot.mode == MomentParticipantsMode.Manual) snapshot.selectedIds else snapshot.automaticIds).intersect(available),
                reviewRequired = false)
        }

        val Saver = listSaver<MomentParticipantsDraft, Any>(
            save = { listOf(it.momentId, it.revision, it.mode.name, it.reviewRequired) + it.selectedIds.sorted() },
            restore = { MomentParticipantsDraft(it[0] as String, it[1] as Long,
                MomentParticipantsMode.valueOf(it[2] as String), it.drop(4).map { value -> value as String }.toSet(), it[3] as Boolean) },
        )
    }
}

/** Local draft only. Cancel performs no write; explicit empty Manual never means Automatic. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MomentParticipantsContent(
    snapshot: MomentParticipantsSnapshot?,
    onBack: () -> Unit,
    onApply: suspend (MomentParticipantsApplyRequest) -> MomentParticipantsApplyResult,
    onApplied: () -> Unit,
    onReload: () -> Unit,
    loadFailed: Boolean = false,
    thumbnailLoader: ThumbnailLoader? = null,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Column {
                GalleryTopAppBar(title = stringResource(R.string.moment_participants_title), onBack = onBack,
                    navigationContentDescription = stringResource(R.string.moment_participants_cancel))
                if (loadFailed) {
                    Text(stringResource(R.string.moment_participants_error), Modifier.padding(16.dp))
                    TextButton(onClick = onReload) { Text(stringResource(R.string.moment_participants_retry)) }
                } else CircularProgressIndicator(Modifier.padding(16.dp))
            }
        }
        BackHandler(onBack = onBack)
        return
    }
    var draft by rememberSaveable(snapshot.momentId, stateSaver = MomentParticipantsDraft.Saver) { mutableStateOf(MomentParticipantsDraft.from(snapshot)) }
    var query by rememberSaveable(snapshot.momentId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MomentParticipantsApplyResult?>(null) }
    val scope = rememberCoroutineScope()
    val current = draft.reconcile(snapshot)
    LaunchedEffect(snapshot) { draft = draft.reconcile(snapshot) }
    fun cancel() { if (!saving) onBack() }
    BackHandler { cancel() }
    val conflict = current.reviewRequired || result == MomentParticipantsApplyResult.Conflict || result == MomentParticipantsApplyResult.Unavailable
    val request = current.request(snapshot).takeUnless { conflict }
    Surface(modifier.fillMaxSize().testTag("moment-participants-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(title = stringResource(R.string.moment_participants_title), onBack = ::cancel,
                navigationContentDescription = stringResource(R.string.moment_participants_cancel))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("moment-participants-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.moment_participants_local_only))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(current.mode == MomentParticipantsMode.Automatic,
                            onClick = { draft = current.chooseMode(MomentParticipantsMode.Automatic, snapshot) }, enabled = !saving,
                            label = { Text(stringResource(R.string.moment_participants_automatic)) }, modifier = Modifier.testTag("moment-participants-auto"))
                        FilterChip(current.mode == MomentParticipantsMode.Manual,
                            onClick = { draft = current.chooseMode(MomentParticipantsMode.Manual, snapshot) }, enabled = !saving,
                            label = { Text(stringResource(R.string.moment_participants_manual)) }, modifier = Modifier.testTag("moment-participants-manual"))
                    }
                    Text(stringResource(if (current.mode == MomentParticipantsMode.Automatic) R.string.moment_participants_automatic_hint else R.string.moment_participants_manual_hint))
                }
                if (conflict) item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(if (result == MomentParticipantsApplyResult.Unavailable) R.string.moment_participants_unavailable else R.string.moment_participants_changed), Modifier.testTag("moment-participants-changed"))
                            TextButton(onClick = { draft = MomentParticipantsDraft.reviewed(snapshot); result = null; onReload() }, enabled = !saving,
                                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), modifier = Modifier.testTag("moment-participants-review")) {
                                Text(stringResource(R.string.moment_participants_review))
                            }
                        }
                    }
                }
                if (result == MomentParticipantsApplyResult.Failed || loadFailed) item {
                    Text(stringResource(R.string.moment_participants_error), Modifier.testTag("moment-participants-error"))
                    if (loadFailed) TextButton(onClick = onReload, enabled = !saving) { Text(stringResource(R.string.moment_participants_retry)) }
                }
                item {
                    Text(stringResource(R.string.moment_participants_count, current.selectedIds.size), Modifier.testTag("moment-participants-count"))
                    if (current.mode == MomentParticipantsMode.Manual) TextButton(onClick = { draft = current.copy(selectedIds = emptySet()) }, enabled = !saving,
                        modifier = Modifier.testTag("moment-participants-clear")) { Text(stringResource(R.string.moment_participants_clear)) }
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.moment_participants_search)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("moment-participants-search"))
                }
                val rows = snapshot.people.withIndex().filter { (_, person) ->
                    (query.isBlank() || person.displayName.orEmpty().contains(query.trim(), ignoreCase = true)) &&
                        (current.mode == MomentParticipantsMode.Manual || person.clusterId in snapshot.automaticIds)
                }
                if (rows.isEmpty()) item {
                    Text(stringResource(if (query.isNotBlank()) R.string.moment_participants_no_matches else R.string.moment_participants_empty),
                        Modifier.testTag("moment-participants-empty"))
                }
                itemsIndexed(rows, key = { _, person -> person.value.clusterId }) { _, indexed ->
                    val person = indexed.value
                    val name = person.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.moment_participants_unnamed, indexed.index + 1)
                    Row(Modifier.fillMaxWidth().testTag("moment-participant-${person.clusterId}").toggleable(
                        value = person.clusterId in current.selectedIds, enabled = !saving && current.mode == MomentParticipantsMode.Manual,
                        role = Role.Checkbox, onValueChange = { draft = current.toggle(person.clusterId, snapshot) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ParticipantThumbnail(person, snapshot.coverGenerations[person.clusterId], thumbnailLoader)
                        Column(Modifier.weight(1f)) { Text(name); Text(stringResource(R.string.people_face_count, person.memberCount)) }
                        Checkbox(person.clusterId in current.selectedIds, onCheckedChange = null, enabled = !saving && current.mode == MomentParticipantsMode.Manual)
                    }
                }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("moment-participants-saving"))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::cancel, enabled = !saving, modifier = Modifier.weight(1f).testTag("moment-participants-cancel")) {
                    Text(stringResource(R.string.moment_participants_cancel))
                }
                Button(onClick = {
                    val action = request ?: return@Button
                    saving = true; result = null
                    scope.launch {
                        try {
                            val outcome = try { onApply(action) } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { MomentParticipantsApplyResult.Failed }
                            ensureActive()
                            result = outcome
                            if (outcome == MomentParticipantsApplyResult.Saved) onApplied()
                        } finally { saving = false }
                    }
                }, enabled = !saving && !loadFailed && request != null, modifier = Modifier.weight(1f).testTag("moment-participants-apply")) {
                    Text(stringResource(if (result == MomentParticipantsApplyResult.Failed) R.string.moment_participants_retry else R.string.moment_participants_apply))
                }
            }
        }
    }
}

internal fun participantThumbnailRequest(person: PersonCardUi, generation: Long?): ThumbnailRequest? {
    val key = person.coverKey ?: return null
    val currentGeneration = generation?.takeIf { it >= 0 } ?: return null
    return ThumbnailRequest(key, currentGeneration, 128, 128)
}

@Composable
private fun ParticipantThumbnail(person: PersonCardUi, generation: Long?, loader: ThumbnailLoader?) {
    var bitmap by remember(person.clusterId, person.coverKey, generation, loader) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(person.clusterId, person.coverKey, generation, loader) {
        val request = participantThumbnailRequest(person, generation) ?: return@LaunchedEffect
        if (loader != null) try { bitmap = loader.load(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bitmap = null }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
    else Surface(Modifier.size(56.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small) {
        Box(contentAlignment = Alignment.Center) { Icon(GalleryIconUser, contentDescription = null) }
    }
}
