package com.librestatic.lightforge.feature.petrecognition

import android.content.Context
import com.librestatic.lightforge.core.designsystem.GalleryInlineEmpty
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.librestatic.lightforge.core.ml.ModelDownloadWait
import com.librestatic.lightforge.core.ml.ModelDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GallerySelectionAction
import com.librestatic.lightforge.core.designsystem.GallerySelectionBar
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryDropdownMenu
import com.librestatic.lightforge.core.designsystem.GalleryMenuItem

private const val MegaByte = 1024 * 1024

private class PetLocalPackContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

/** No ViewModel/Room ownership: caller supplies the persisted pet namespace. Disposal cancels work. */
@Composable
fun PetIdentityContent(repository: PetIdentityRepository, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { PetModelStore(context) }
    val scope = rememberCoroutineScope()
    val summary by repository.summary.collectAsState(initial = PetSummary(0, false, null, 0, 0, 0, 0, null))
    var installed by remember { mutableStateOf(store.installed()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    // Analysis is background work (PetIdentityAnalysisWorker), so it outlives this screen and the process.
    val analysis by remember(context) { PetIdentityAnalysisRunner.progress(context) }.collectAsState(initial = PetAnalysisProgress())
    val identityProgress by remember(context, repository) { PetIdentityAnalysisRunner.identityProgress(context, repository) }.collectAsState(initial = PetIdentityProgress())
    // Downloads run in PetModelDownloadWorker, so they survive this screen and pause off Wi-Fi.
    val downloads by remember(context) { PetModelDownloadWorker.state(context) }.collectAsState(initial = emptyList())
    val download = downloads.firstOrNull { !it.state.isFinished }
    var downloadWait by remember { mutableStateOf<ModelDownloadWait?>(null) }
    // Background analysis never blocks browsing or edits: edits use revision CAS and analysis keeps the revision.
    val working = busy || download != null
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var removal by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf("groups") }
    var cursor by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var identities by remember { mutableStateOf(PetPage<PetIdentityCard>(emptyList(), null)) }
    var observations by remember { mutableStateOf(PetPage<PetObservationCard>(emptyList(), null)) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selectedGroups by remember { mutableStateOf(setOf<String>()) }
    var suggestions by remember { mutableStateOf(emptyList<PetSimilaritySuggestion>()) }
    var possible by remember { mutableStateOf(emptyList<PetGroupSuggestion>()) }
    var dismissedPossible by remember { mutableStateOf(setOf<String>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameAction by remember { mutableStateOf<String?>(null) }
    fun cancel() { signal?.cancel(); operation?.cancel() }
    fun back() { if (busy) leaving = true else onBack() }
    DisposableEffect(Unit) { onDispose { cancel() } }
    LaunchedEffect(analysis.running) { if (!analysis.running) refresh++ }
    LaunchedEffect(download?.state) {
        downloadWait = null
        // A queued download explains why it waits; the reason can change without a WorkInfo update.
        while (download?.state == WorkInfo.State.ENQUEUED) {
            downloadWait = ModelDownloads.currentWait(context)
            delay(5_000)
        }
    }
    LaunchedEffect(downloads) {
        if (downloads.any { it.state == WorkInfo.State.SUCCEEDED }) { installed = store.installed(); refresh++ }
        if (!installed && download == null && downloads.any { it.state == WorkInfo.State.FAILED }) error = true
    }
    BackHandler { back() }
    fun run(action: suspend (CancellationSignal) -> Unit) {
        if (busy) return
        val cancellation = CancellationSignal()
        signal = cancellation; busy = true; error = false; progress = 0f
        operation = scope.launch {
            try { action(cancellation) }
            catch (failure: Throwable) { if (!cancellation.isCanceled && failure !is CancellationException) error = true }
            finally { busy = false; signal = null; installed = store.installed(); refresh++ }
        }
    }
    fun edit(edit: PetEdit) {
        val expected = summary.revision
        run {
            check(repository.edit(expected, edit).applied)
            selected = emptySet(); selectedGroups = emptySet(); suggestions = emptyList()
        }
    }
    fun navigate(next: String, identity: String? = null) {
        section = next; group = identity; cursor = null; selected = emptySet(); selectedGroups = emptySet(); suggestions = emptyList()
    }
    LaunchedEffect(summary.revision, section, group, cursor, refresh) {
        try {
            if (section == "groups") identities = repository.identitiesPage(cursor, 40)
            else observations = repository.observationsPage(group, when(section) {
                "unassigned" -> PetObservationFilter.Unassigned
                "excluded" -> PetObservationFilter.Excluded
                else -> PetObservationFilter.All
            }, cursor, 40)
        } catch (failure: Throwable) { if (failure !is CancellationException) error = true }
    }
    // Suggested groups are computed off the main thread when the screen opens and as analysis advances.
    LaunchedEffect(summary.enabled, summary.revision, installed, refresh, analysis.running, identityProgress.processed / 100) {
        if (!summary.enabled || !installed) { possible = emptyList(); return@LaunchedEffect }
        try { possible = withContext(Dispatchers.Default) { PetGroupSuggestions.load(repository, PetModelCatalog.Fingerprint) } }
        catch (failure: Throwable) { if (failure !is CancellationException) possible = emptyList() }
    }
    val picker = rememberLauncherForActivityResult(PetLocalPackContract()) { uri ->
        if (uri != null) run { cancellation -> withContext(Dispatchers.IO) { store.importPack(uri, cancellation) { progress = it } } }
    }
    val selectedVisible = observations.items.filter { it.id in selected }
    val selectedSpecies = selectedVisible.map { it.species }.distinct().singleOrNull()
    val selectionIsUnassigned = selected.isNotEmpty() && selectedVisible.size == selected.size && selectedVisible.all { it.identityId == null && !it.excluded }
    val selectionCount = if (section == "groups") selectedGroups.size else selected.size
    val clearSelection = { selected = emptySet(); selectedGroups = emptySet(); suggestions = emptyList() }
    // Bulk actions live in the contextual selection bar, ordered by everyday use; the rest overflow.
    val selectionActions = if (section == "groups") {
        listOf(
            GallerySelectionAction(stringResource(R.string.pet_merge), GalleryIcons.Link, { edit(PetEdit.Merge(selectedGroups.first(), selectedGroups.drop(1).toSet())) }, enabled = !working && selectedGroups.size >= 2, testTag = "pet-merge"),
        )
    } else buildList {
        add(GallerySelectionAction(stringResource(R.string.pet_create), GalleryIcons.Plus, { name = ""; nameAction = "create" }, enabled = !working && selectionIsUnassigned && selectedSpecies != null && selectedSpecies != PetSpecies.Uncertain, testTag = "pet-create-group"))
        if (section == "excluded") add(GallerySelectionAction(stringResource(R.string.pet_restore), GalleryIcons.RestoreFromTrash, { edit(PetEdit.Restore(selected)) }, enabled = !working && selected.isNotEmpty(), testTag = "pet-restore"))
        else add(GallerySelectionAction(stringResource(R.string.pet_exclude), GalleryIcons.Archive, { edit(PetEdit.Exclude(selected)) }, enabled = !working && selected.isNotEmpty(), testTag = "pet-exclude"))
        add(GallerySelectionAction(stringResource(R.string.pet_suggest), GalleryIcons.AutoAwesome, {
            val observation = selectedVisible.single()
            run { cancellation ->
                cancellation.throwIfCanceled()
                val embedding = requireNotNull(repository.observationEmbedding(observation.id, observation.modelFingerprint))
                check(repository.isCurrent(observation.source))
                suggestions = PetSimilarityRanking.suggest(repository, observation, embedding)
                cancellation.throwIfCanceled()
            }
        }, enabled = !working && selectionIsUnassigned && selected.size == 1 && selectedSpecies != PetSpecies.Uncertain, testTag = "pet-suggest"))
        if (group != null) add(GallerySelectionAction(stringResource(R.string.pet_split), GalleryIcons.SwapVert, { name = ""; nameAction = "split" }, enabled = !working && selected.isNotEmpty(), testTag = "pet-split"))
        add(GallerySelectionAction(stringResource(R.string.pet_set_cat), GalleryIcons.Pet, { edit(PetEdit.SetSpecies(selected, PetSpecies.Cat)) }, enabled = !working && selectionIsUnassigned, inline = false, testTag = "pet-species-cat"))
        add(GallerySelectionAction(stringResource(R.string.pet_set_dog), GalleryIcons.Pet, { edit(PetEdit.SetSpecies(selected, PetSpecies.Dog)) }, enabled = !working && selectionIsUnassigned, inline = false, testTag = "pet-species-dog"))
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
    var moreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
    GalleryTopAppBar(
        title = stringResource(R.string.pet_title),
        onBack = ::back,
        navigationContentDescription = stringResource(R.string.pet_back),
        actions = {
            if (summary.enabled) {
                IconButton(enabled = !working, onClick = { cursor = null; refresh++; clearSelection() }, modifier = Modifier.testTag("pet-refresh")) {
                    Icon(GalleryIcons.Refresh, contentDescription = stringResource(R.string.pet_refresh))
                }
                IconButton(enabled = !working && summary.undoToken != null, onClick = {
                    val expected = summary.revision; val token = summary.undoToken ?: return@IconButton
                    run { check(repository.undo(expected, token).applied); selected = emptySet(); selectedGroups = emptySet() }
                }, modifier = Modifier.testTag("pet-undo")) {
                    Icon(GalleryIcons.Undo, contentDescription = stringResource(R.string.pet_undo))
                }
                Box {
                    IconButton(onClick = { moreOpen = true }, modifier = Modifier.testTag("pet-more")) {
                        Icon(GalleryIcons.More, contentDescription = stringResource(R.string.pet_more))
                    }
                    GalleryDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                        GalleryMenuItem(
                            text = stringResource(R.string.pet_remove),
                            onClick = { moreOpen = false; removal = true },
                            enabled = !working,
                            destructive = true,
                            testTag = "pet-remove",
                        )
                    }
                }
            }
        },
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
    LazyColumn(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("pet-identity-screen"), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (selectionCount > 0) 160.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.pet_explanation)) }
        if (error || analysis.failed) item { Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) { Text(stringResource(R.string.pet_error), Modifier.padding(12.dp).testTag("pet-error")) } }
        if (busy || analysis.running) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analysis.running && identityProgress.total > 0) GalleryProgressIndicator(progress = { identityProgress.processed.toFloat() / identityProgress.total }, modifier = Modifier.fillMaxWidth().testTag("pet-progress"))
                else if (analysis.running) GalleryIndeterminateProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("pet-progress"))
                else GalleryProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("pet-progress"))
                if (analysis.running) {
                    if (identityProgress.total > 0) Text(pluralStringResource(R.plurals.pet_progress_of, identityProgress.total.toInt(), identityProgress.processed, identityProgress.total))
                    Text(
                        stringResource(
                            R.string.pet_progress,
                            pluralStringResource(R.plurals.pet_progress_photos, analysis.analyzed.toInt(), analysis.analyzed),
                            pluralStringResource(R.plurals.pet_progress_animals, analysis.detected.toInt(), analysis.detected),
                        ),
                    )
                }
                OutlinedButton(onClick = { if (analysis.running) PetIdentityAnalysisRunner.cancel(context) else cancel() }, modifier = Modifier.testTag("pet-cancel")) { Text(stringResource(R.string.pet_cancel_analysis)) }
            }
        }
        if (analysis.skipped > 0) item {
            Text(
                pluralStringResource(R.plurals.pet_skipped, analysis.skipped.toInt(), analysis.skipped),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("pet-skipped"),
            )
        }
        if (!installed) {
            if (download != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pet-download-status")) {
                    val bytes = download.progress.getLong(PetModelDownloadWorker.KeyDownloadedBytes, 0L)
                    val wait = downloadWait
                    if (download.state == WorkInfo.State.RUNNING) {
                        GalleryProgressIndicator(progress = { bytes.toFloat() / PetModelStore.PackageBytes }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.pet_download_progress, bytes / MegaByte, PetModelStore.PackageBytes / MegaByte))
                    } else Text(
                        when (wait) {
                            ModelDownloadWait.Network -> stringResource(R.string.pet_download_waiting_network)
                            ModelDownloadWait.WiFi -> stringResource(R.string.pet_download_waiting_wifi)
                            ModelDownloadWait.Battery -> stringResource(R.string.pet_download_waiting_battery)
                            null -> stringResource(R.string.pet_download_queued)
                        },
                        modifier = Modifier.testTag("pet-download-wait"),
                    )
                    OutlinedButton(onClick = { PetModelDownloadWorker.cancel(context) }, modifier = Modifier.testTag("pet-download-cancel")) { Text(stringResource(R.string.pet_download_cancel)) }
                }
            }
            else item {
                PetActionRow(R.string.pet_download, R.string.pet_download_summary, !working, "pet-download") { error = false; scope.launch { PetModelDownloadWorker.enqueue(context) } }
            }
            item { PetActionRow(R.string.pet_import, R.string.pet_import_summary, !working, "pet-import") { picker.launch(arrayOf("application/zip", "application/octet-stream")) } }
        } else item { Text(stringResource(R.string.pet_model_ready), Modifier.testTag("pet-model-ready")) }
        if (!summary.enabled) item {
            PetActionRow(R.string.pet_enable, R.string.pet_enable_summary, installed && !working, "pet-enable") { val revision = summary.revision; run { cancellation ->
                withContext(Dispatchers.IO) { store.openEngine(cancellation).use { } }
                check(repository.setAnalysisEnabled(revision, true, PetModelCatalog.Fingerprint))
            } }
        } else {
            item { PetActionRow(R.string.pet_analyze, R.string.pet_analyze_summary, !working && installed && !analysis.running, "pet-analyze") { error = false; PetIdentityAnalysisRunner.start(context) } }
            item {
                val sections = listOf(
                    Triple("groups", R.string.pet_groups, "pet-groups"),
                    Triple("unassigned", R.string.pet_unassigned, "pet-review"),
                    Triple("all", R.string.pet_all, "pet-all"),
                    Triple("excluded", R.string.pet_excluded, "pet-excluded"),
                )
                PrimaryScrollableTabRow(selectedTabIndex = sections.indexOfFirst { it.first == section }.coerceAtLeast(0), edgePadding = 0.dp) {
                    sections.forEach { (key, label, tag) ->
                        Tab(selected = key == section, enabled = !working, onClick = { navigate(key) }, text = { Text(stringResource(label)) }, modifier = Modifier.testTag(tag))
                    }
                }
            }
            if (section == "groups") {
                val shownPossible = possible.filter { it.key !in dismissedPossible }
                if (shownPossible.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.pet_suggestions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pet-possible-title"))
                            Text(stringResource(R.string.pet_suggestions_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(shownPossible, key = { "possible-${it.key}" }) { suggestion ->
                        PetPossibleCard(suggestion, repository, enabled = !working, onCreate = { edit(PetEdit.CreateIdentity(suggestion.species, null, suggestion.observationIds.toSet())) }, onDismiss = { dismissedPossible = dismissedPossible + suggestion.key })
                    }
                }
                if (identities.items.isEmpty() && shownPossible.isEmpty()) item {
                    PetGroupsEmpty(identityProgress, analysis, summary.unassignedCount, onReview = { navigate("unassigned") })
                }
                items(identities.items, key = { it.id }) { identity ->
                    Card(Modifier.fillMaxWidth().testTag("pet-group-${identity.id}")) {
                        Column(Modifier.padding(12.dp)) {
                            Row { Checkbox(checked = identity.id in selectedGroups, enabled = !working, onCheckedChange = { checked -> selectedGroups = if (checked && selectedGroups.size < 2000) selectedGroups + identity.id else selectedGroups - identity.id }); Text(identity.name ?: speciesName(identity.species)) }
                            identity.representative?.let { PetObservationImage(it, repository) }
                            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), enabled = !working, onClick = { navigate("all", identity.id) }, modifier = Modifier.testTag("pet-open-${identity.id}")) { Text(stringResource(R.string.pet_open_group)) }
                            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), enabled = !working, onClick = { selectedGroups = setOf(identity.id); name = identity.name.orEmpty(); nameAction = "rename" }) { Text(stringResource(R.string.pet_rename)) }
                        }
                    }
                }
            } else {
                if (observations.items.isEmpty()) item { GalleryInlineEmpty(stringResource(R.string.pet_no_items)) }
                items(observations.items, key = { it.id }) { observation ->
                    Card(Modifier.fillMaxWidth().testTag("pet-observation-${observation.id}")) {
                        Column(Modifier.padding(12.dp)) {
                            Row { Checkbox(checked = observation.id in selected, enabled = !working, onCheckedChange = { checked -> selected = if (checked && selected.size < 2000) selected + observation.id else selected - observation.id; suggestions = emptyList() }, modifier = Modifier.testTag("pet-select-${observation.id}")); Text(speciesName(observation.species)) }
                            PetObservationImage(observation, repository)
                        }
                    }
                }
                items(suggestions, key = { "suggestion-${it.identityId}" }) { suggestion ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Text(identities.items.firstOrNull { it.id == suggestion.identityId }?.name ?: suggestion.identityId)
                        Text(stringResource(R.string.pet_similarity, suggestion.cosineSimilarity))
                        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), enabled = !working, onClick = { edit(PetEdit.AcceptSuggestion(suggestion.identityId, selected)) }, modifier = Modifier.testTag("pet-accept-${suggestion.identityId}")) { Text(stringResource(R.string.pet_accept)) }
                    } }
                }
            }
            val next = if (section == "groups") identities.nextId else observations.nextId
            item { TextButton(enabled = !working && next != null, onClick = { cursor = next; selected = emptySet(); selectedGroups = emptySet(); suggestions = emptyList() }, modifier = Modifier.testTag("pet-next")) { Text(stringResource(R.string.pet_next)) } }
        }
    }
    if (summary.enabled && selectionCount > 0) GallerySelectionBar(
        count = selectionCount.toLong(),
        onClear = clearSelection,
        actions = selectionActions,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }
    }
    }
    if (removal) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { removal = false }, title = { Text(stringResource(R.string.pet_remove)) }, text = { Text(stringResource(R.string.pet_remove_detail)) }, confirmButton = {
        TextButton(onClick = { removal = false; val revision = summary.revision; run { check(repository.setAnalysisEnabled(revision, false, null)); withContext(Dispatchers.IO) { store.delete() }; navigate("groups") } }, modifier = Modifier.testTag("pet-remove-confirm")) { Text(stringResource(R.string.pet_confirm)) }
    }, dismissButton = { TextButton(onClick = { removal = false }) { Text(stringResource(R.string.pet_cancel)) } })
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, text = { Text(stringResource(R.string.pet_leave)) }, confirmButton = { TextButton(onClick = { leaving = false; cancel(); onBack() }) { Text(stringResource(R.string.pet_back)) } }, dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.pet_cancel)) } })
    if (nameAction != null) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { nameAction = null }, title = { Text(stringResource(R.string.pet_name)) }, text = { OutlinedTextField(value = name, onValueChange = { if (it.length <= 80) name = it }, singleLine = true, modifier = Modifier.testTag("pet-name")) }, confirmButton = {
        TextButton(onClick = {
            val action = nameAction; nameAction = null
            when (action) {
                "create" -> selectedSpecies?.takeIf { it != PetSpecies.Uncertain }?.let { edit(PetEdit.CreateIdentity(it, name.trim().ifBlank { null }, selected)) }
                "rename" -> selectedGroups.singleOrNull()?.let { edit(PetEdit.Rename(it, name.trim().ifBlank { null })) }
                "split" -> group?.let { edit(PetEdit.Split(it, selected, name.trim().ifBlank { null })) }
            }
        }, modifier = Modifier.testTag("pet-name-confirm")) { Text(stringResource(R.string.pet_confirm)) }
    }, dismissButton = { TextButton(onClick = { nameAction = null }) { Text(stringResource(R.string.pet_cancel)) } })
}

@Composable
private fun PetActionRow(label: Int, summary: Int, enabled: Boolean, tag: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).testTag(tag),
        supportingContent = { Text(stringResource(summary)) },
    ) { Text(stringResource(label)) }
}

@Composable
private fun PetPossibleCard(suggestion: PetGroupSuggestion, repository: PetIdentityRepository, enabled: Boolean, onCreate: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("pet-possible-${suggestion.key}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.pet_suggestion_summary, speciesName(suggestion.species), pluralStringResource(R.plurals.pet_suggestion_photos, suggestion.observationIds.size, suggestion.observationIds.size)),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestion.covers.forEach { PetObservationImage(it, repository, Modifier.weight(1f).height(96.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), enabled = enabled, onClick = onCreate, modifier = Modifier.testTag("pet-possible-create")) { Text(stringResource(R.string.pet_suggestion_create)) }
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), onClick = onDismiss, modifier = Modifier.testTag("pet-possible-dismiss")) { Text(stringResource(R.string.pet_suggestion_dismiss)) }
            }
        }
    }
}

/** Says why the Groups tab is empty: still analyzing, waiting, ungrouped photos to review, or nothing found yet. */
@Composable
private fun PetGroupsEmpty(progress: PetIdentityProgress, analysis: PetAnalysisProgress, unassigned: Long, onReview: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("pet-groups-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            analysis.running && progress.total > 0 -> {
                GalleryInlineEmpty(pluralStringResource(R.plurals.pet_progress_of, progress.total.toInt(), progress.processed, progress.total))
                GalleryProgressIndicator(progress = { progress.processed.toFloat() / progress.total })
            }
            analysis.queued && !progress.complete -> GalleryInlineEmpty(stringResource(R.string.pet_analysis_waiting))
            unassigned > 0 -> {
                GalleryInlineEmpty(pluralStringResource(R.plurals.pet_empty_unassigned, unassigned.toInt(), unassigned))
                FilledTonalButton(onClick = onReview, modifier = Modifier.testTag("pet-empty-review")) { Text(stringResource(R.string.pet_empty_review)) }
            }
            else -> GalleryInlineEmpty(stringResource(R.string.pet_empty_none))
        }
    }
}

@Composable private fun speciesName(species: PetSpecies): String = stringResource(when (species) { PetSpecies.Cat -> R.string.pet_cat; PetSpecies.Dog -> R.string.pet_dog; PetSpecies.Uncertain -> R.string.pet_uncertain })

@Composable private fun PetObservationImage(observation: PetObservationCard, repository: PetIdentityRepository, modifier: Modifier = Modifier.fillMaxWidth().height(180.dp)) {
    val context = LocalContext.current
    val image by produceState<Bitmap?>(null, observation.id, observation.source.generationModified, observation.source.generationAdded) {
        value = withContext(Dispatchers.IO) {
            if (!repository.isCurrent(observation.source)) null else {
                val decoded = runCatching { decodePetThumbnail(context, observation, 512) }.getOrNull()
                if (decoded != null && !repository.isCurrent(observation.source)) { decoded.recycle(); null } else decoded
            }
        }
        awaitDispose { value?.recycle() }
    }
    image?.let { Image(it.asImageBitmap(), contentDescription = speciesName(observation.species), contentScale = ContentScale.Fit, modifier = modifier) }
}

private fun decodePetThumbnail(context: Context, observation: PetObservationCard, maxSide: Int): Bitmap {
    val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, observation.source.uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val side = maxOf(info.size.width, info.size.height)
        if (side > maxSide) decoder.setTargetSize(maxOf(1, info.size.width * maxSide / side), maxOf(1, info.size.height * maxSide / side))
    }
    val crop = try { PetRecognitionEngine.crop(full, observation.box) } catch (failure: Throwable) { full.recycle(); throw failure }
    if (crop !== full) full.recycle()
    return crop
}
