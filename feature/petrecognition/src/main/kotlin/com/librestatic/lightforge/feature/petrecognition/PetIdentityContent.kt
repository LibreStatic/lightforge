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
import androidx.compose.ui.Modifier
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
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

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
    // Analysis runs in PetIdentityAnalysisRunner so leaving this screen does not cancel it.
    val analysis by PetIdentityAnalysisRunner.progress.collectAsState()
    // Downloads run in PetModelDownloadWorker, so they survive this screen and pause off Wi-Fi.
    val downloads by remember(context) { PetModelDownloadWorker.state(context) }.collectAsState(initial = emptyList())
    val download = downloads.firstOrNull { !it.state.isFinished }
    var downloadWait by remember { mutableStateOf<ModelDownloadWait?>(null) }
    val working = busy || analysis.running || download != null
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
    val picker = rememberLauncherForActivityResult(PetLocalPackContract()) { uri ->
        if (uri != null) run { cancellation -> withContext(Dispatchers.IO) { store.importPack(uri, cancellation) { progress = it } } }
    }
    val selectedVisible = observations.items.filter { it.id in selected }
    val selectedSpecies = selectedVisible.map { it.species }.distinct().singleOrNull()
    val selectionIsUnassigned = selected.isNotEmpty() && selectedVisible.size == selected.size && selectedVisible.all { it.identityId == null && !it.excluded }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
    LazyColumn(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("pet-identity-screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = ::back, modifier = Modifier.testTag("pet-back")) { Text(stringResource(R.string.pet_back)) } }
        item { Text(stringResource(R.string.pet_title), style = MaterialTheme.typography.headlineMedium) }
        item { Text(stringResource(R.string.pet_explanation)) }
        if (error || analysis.failed) item { Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) { Text(stringResource(R.string.pet_error), Modifier.padding(12.dp).testTag("pet-error")) } }
        if (busy || analysis.running) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analysis.running) GalleryIndeterminateProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("pet-progress"))
                else GalleryProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("pet-progress"))
                if (analysis.running) Text(
                    stringResource(
                        R.string.pet_progress,
                        pluralStringResource(R.plurals.pet_progress_photos, analysis.analyzed.toInt(), analysis.analyzed),
                        pluralStringResource(R.plurals.pet_progress_animals, analysis.detected.toInt(), analysis.detected),
                    ),
                )
                OutlinedButton(onClick = { if (analysis.running) PetIdentityAnalysisRunner.cancel() else cancel() }, modifier = Modifier.testTag("pet-cancel")) { Text(stringResource(R.string.pet_cancel_analysis)) }
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
            else item { Button(enabled = !working, onClick = { error = false; scope.launch { PetModelDownloadWorker.enqueue(context) } }, modifier = Modifier.testTag("pet-download")) { Text(stringResource(R.string.pet_download)) } }
            item { OutlinedButton(enabled = !working, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.testTag("pet-import")) { Text(stringResource(R.string.pet_import)) } }
        } else item { Text(stringResource(R.string.pet_model_ready), Modifier.testTag("pet-model-ready")) }
        if (!summary.enabled) item {
            Button(enabled = installed && !working, onClick = { val revision = summary.revision; run { cancellation ->
                withContext(Dispatchers.IO) { store.openEngine(cancellation).use { } }
                check(repository.setAnalysisEnabled(revision, true, PetModelCatalog.Fingerprint))
            } }, modifier = Modifier.testTag("pet-enable")) { Text(stringResource(R.string.pet_enable)) }
        } else {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !working && installed, onClick = { error = false; PetIdentityAnalysisRunner.start(context, repository) }, modifier = Modifier.testTag("pet-analyze")) { Text(stringResource(R.string.pet_analyze)) }
            } }
            item { OutlinedButton(enabled = !working, onClick = { removal = true }, modifier = Modifier.testTag("pet-remove")) { Text(stringResource(R.string.pet_remove)) } }
            item { Column {
                Row { TextButton(enabled = !working, onClick = { navigate("groups") }) { Text(stringResource(R.string.pet_groups)) }; TextButton(enabled = !working, onClick = { navigate("unassigned") }, modifier = Modifier.testTag("pet-review")) { Text(stringResource(R.string.pet_unassigned)) } }
                Row { TextButton(enabled = !working, onClick = { navigate("all") }) { Text(stringResource(R.string.pet_all)) }; TextButton(enabled = !working, onClick = { navigate("excluded") }, modifier = Modifier.testTag("pet-excluded")) { Text(stringResource(R.string.pet_excluded)) } }
            } }
            item { TextButton(enabled = !working, onClick = { cursor = null; refresh++; selected = emptySet(); selectedGroups = emptySet() }, modifier = Modifier.testTag("pet-refresh")) { Text(stringResource(R.string.pet_refresh)) } }
            if (section == "groups") {
                if (identities.items.isEmpty()) item { GalleryInlineEmpty(stringResource(R.string.pet_no_items)) }
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
                item { Button(enabled = !working && selectedGroups.size >= 2, onClick = { edit(PetEdit.Merge(selectedGroups.first(), selectedGroups.drop(1).toSet())) }, modifier = Modifier.testTag("pet-merge")) { Text(stringResource(R.string.pet_merge)) } }
            } else {
                item { Text(pluralStringResource(R.plurals.pet_selected, selected.size, selected.size)) }
                if (observations.items.isEmpty()) item { GalleryInlineEmpty(stringResource(R.string.pet_no_items)) }
                items(observations.items, key = { it.id }) { observation ->
                    Card(Modifier.fillMaxWidth().testTag("pet-observation-${observation.id}")) {
                        Column(Modifier.padding(12.dp)) {
                            Row { Checkbox(checked = observation.id in selected, enabled = !working, onCheckedChange = { checked -> selected = if (checked && selected.size < 2000) selected + observation.id else selected - observation.id; suggestions = emptyList() }, modifier = Modifier.testTag("pet-select-${observation.id}")); Text(speciesName(observation.species)) }
                            PetObservationImage(observation, repository)
                        }
                    }
                }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !working && selectionIsUnassigned && selectedSpecies != null && selectedSpecies != PetSpecies.Uncertain, onClick = { name = ""; nameAction = "create" }, modifier = Modifier.testTag("pet-create-group")) { Text(stringResource(R.string.pet_create)) }
                    OutlinedButton(enabled = !working && selectionIsUnassigned, onClick = { edit(PetEdit.SetSpecies(selected, PetSpecies.Cat)) }, modifier = Modifier.testTag("pet-species-cat")) { Text(stringResource(R.string.pet_set_cat)) }
                    OutlinedButton(enabled = !working && selectionIsUnassigned, onClick = { edit(PetEdit.SetSpecies(selected, PetSpecies.Dog)) }, modifier = Modifier.testTag("pet-species-dog")) { Text(stringResource(R.string.pet_set_dog)) }
                    OutlinedButton(enabled = !working && group != null && selected.isNotEmpty(), onClick = { name = ""; nameAction = "split" }, modifier = Modifier.testTag("pet-split")) { Text(stringResource(R.string.pet_split)) }
                    OutlinedButton(enabled = !working && selected.isNotEmpty() && section != "excluded", onClick = { edit(PetEdit.Exclude(selected)) }, modifier = Modifier.testTag("pet-exclude")) { Text(stringResource(R.string.pet_exclude)) }
                    OutlinedButton(enabled = !working && selected.isNotEmpty() && section == "excluded", onClick = { edit(PetEdit.Restore(selected)) }, modifier = Modifier.testTag("pet-restore")) { Text(stringResource(R.string.pet_restore)) }
                    OutlinedButton(enabled = !working && selectionIsUnassigned && selected.size == 1 && selectedSpecies != PetSpecies.Uncertain, onClick = {
                        val observation = selectedVisible.single()
                        run { cancellation ->
                            cancellation.throwIfCanceled()
                            val embedding = requireNotNull(repository.observationEmbedding(observation.id, observation.modelFingerprint))
                            check(repository.isCurrent(observation.source))
                            suggestions = PetSimilarityRanking.suggest(repository, observation, embedding)
                            cancellation.throwIfCanceled()
                        }
                    }, modifier = Modifier.testTag("pet-suggest")) { Text(stringResource(R.string.pet_suggest)) }
                } }
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
            item { OutlinedButton(enabled = !working && summary.undoToken != null, onClick = { val expected = summary.revision; val token = summary.undoToken ?: return@OutlinedButton; run { check(repository.undo(expected, token).applied); selected = emptySet(); selectedGroups = emptySet() } }, modifier = Modifier.testTag("pet-undo")) { Text(stringResource(R.string.pet_undo)) } }
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

@Composable private fun speciesName(species: PetSpecies): String = stringResource(when (species) { PetSpecies.Cat -> R.string.pet_cat; PetSpecies.Dog -> R.string.pet_dog; PetSpecies.Uncertain -> R.string.pet_uncertain })

@Composable private fun PetObservationImage(observation: PetObservationCard, repository: PetIdentityRepository) {
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
    image?.let { Image(it.asImageBitmap(), contentDescription = speciesName(observation.species), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(180.dp)) }
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
