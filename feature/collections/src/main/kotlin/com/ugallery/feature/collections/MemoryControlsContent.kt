package com.ugallery.feature.collections

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.ugallery.core.data.*
import com.ugallery.core.database.*
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private enum class MemoryControlPage {
    Home,
    Dates,
    People,
    ReviewDate,
    ReviewPerson,
}

/**
 * These rules affect Memories only. Originals, albums and local analysis settings are untouched.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MemoryControlsContent(
    repository: MemoryExclusionRepository,
    peopleRepository: GallerySmartAlbumRepository,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    onAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(MemoryControlPage.Home) }
    var draft by
        rememberSaveable(stateSaver = MemoryControlsDraft.Saver) {
            mutableStateOf(MemoryControlsDraft())
        }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var personVersion by rememberSaveable { mutableStateOf<String?>(null) }
    // Only an exact immutable receipt from a newly created rule can be undone.
    var undoId by rememberSaveable { mutableStateOf<String?>(null) }
    var undoPerson by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = remember(page) { LazyListState() }
    val dates =
        memoryValue(remember(repository) { repository.dates() }, retry) {
            error = R.string.memory_controls_error
        }
    val people =
        memoryValue(remember(repository) { repository.people() }, retry) {
            error = R.string.memory_controls_error
        }
    val loaded = dates.loaded && people.loaded
    val count = dates.value.orEmpty().size + people.value.orEmpty().size
    val date = dates.value?.firstOrNull { it.ruleId == selected }
    val person = people.value?.firstOrNull { it.ruleId == selected }
    val atLimit = count >= MemoryExclusionRepository.MaximumRules
    fun home() {
        page = MemoryControlPage.Home
        selected = null
        confirming = false
        personId = null
        personVersion = null
    }
    fun back() {
        if (busy) return
        if (page == MemoryControlPage.Home) onBack()
        else if (
            page == MemoryControlPage.Dates && (draft.start.isNotEmpty() || draft.end.isNotEmpty())
        )
            discard = true
        else home()
    }
    fun mutate(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: MemoryRuleLimitReached) {
                error = R.string.memory_controls_limit
            } catch (_: MemoryPersonUnavailable) {
                error = R.string.memory_controls_person_changed
            } catch (_: Exception) {
                error = R.string.memory_controls_error
            } finally {
                busy = false
            }
        }
    }
    BackHandler { back() }
    if (discard)
        MemoryControlConfirm(
            R.string.memory_controls_discard,
            stringResource(R.string.memory_controls_discard_body),
            { discard = false },
        ) {
            discard = false
            draft = MemoryControlsDraft()
            home()
        }
    if (confirming) {
        val removing =
            page == MemoryControlPage.ReviewDate || page == MemoryControlPage.ReviewPerson
        val summary =
            when (page) {
                MemoryControlPage.Dates -> "${draft.start} – ${draft.end}\n${draft.zone}"
                MemoryControlPage.People ->
                    personId
                        ?.let { id ->
                            val identity =
                                memoryValue(
                                    remember(peopleRepository, id, personVersion) {
                                        peopleRepository.person(id, personVersion.orEmpty())
                                    },
                                    retry,
                                ) {
                                    error = R.string.memory_controls_error
                                }
                            identity.value?.displayName
                                ?: stringResource(R.string.memory_controls_unnamed)
                        }
                        .orEmpty()
                MemoryControlPage.ReviewDate -> date?.let { dateSummary(it) }.orEmpty()
                MemoryControlPage.ReviewPerson ->
                    person?.displayName ?: stringResource(R.string.memory_controls_unnamed)
                else -> ""
            }
        MemoryControlConfirm(
            if (removing) R.string.memory_controls_include else R.string.memory_controls_hide,
            summary +
                "\n\n" +
                stringResource(
                    if (removing) R.string.memory_controls_include_body
                    else R.string.memory_controls_confirm_body
                ),
            { confirming = false },
        ) {
            confirming = false
            mutate {
                when (page) {
                    MemoryControlPage.Dates -> {
                        val range = draft.range() ?: return@mutate
                        val receipt = repository.addDate(range)
                        undoId = if (receipt.created) receipt.rule.ruleId else null
                        undoPerson = false
                        notice =
                            if (receipt.created) R.string.memory_controls_added
                            else R.string.memory_controls_duplicate
                        draft = MemoryControlsDraft()
                        home()
                    }
                    MemoryControlPage.People -> {
                        val id = personId ?: return@mutate
                        val version = personVersion ?: return@mutate
                        val receipt = repository.addPerson(id, version)
                        undoId = if (receipt.created) receipt.rule.ruleId else null
                        undoPerson = true
                        notice =
                            if (receipt.created) R.string.memory_controls_added
                            else R.string.memory_controls_duplicate
                        home()
                    }
                    MemoryControlPage.ReviewDate,
                    MemoryControlPage.ReviewPerson -> {
                        val id = selected ?: return@mutate
                        val removed =
                            if (page == MemoryControlPage.ReviewPerson) repository.removePerson(id)
                            else repository.removeDate(id)
                        notice =
                            if (removed) R.string.memory_controls_included
                            else R.string.memory_controls_gone
                        if (undoId == id) undoId = null
                        home()
                    }
                    else -> Unit
                }
            }
        }
    }
    Scaffold(
        modifier =
            modifier.testTag("memory-controls-screen").semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextButton(
                    { back() },
                    enabled = !busy,
                    modifier = Modifier.testTag("memory-controls-back"),
                ) {
                    Text(stringResource(R.string.memory_controls_back))
                }
                Text(
                    stringResource(
                        when (page) {
                            MemoryControlPage.Dates -> R.string.memory_controls_add_dates
                            MemoryControlPage.People -> R.string.memory_controls_add_person
                            MemoryControlPage.ReviewDate,
                            MemoryControlPage.ReviewPerson -> R.string.memory_controls_review
                            else -> R.string.memory_controls_title
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("memory-controls-list"),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (busy || !loaded)
                    item {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().testTag("memory-controls-loading")
                        )
                    }
                error?.let { message ->
                    item {
                        MemoryControlPanel(error = true) {
                            Text(stringResource(message), Modifier.testTag("memory-controls-error"))
                            TextButton(
                                {
                                    error = null
                                    retry++
                                },
                                enabled = !busy,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = LocalContentColor.current
                                    ),
                            ) {
                                Text(stringResource(R.string.memory_controls_retry))
                            }
                        }
                    }
                }
                notice?.let { message ->
                    item {
                        MemoryControlPanel {
                            Text(
                                stringResource(message),
                                Modifier.testTag("memory-controls-notice"),
                            )
                            if (undoId != null)
                                TextButton(
                                    {
                                        val id = undoId ?: return@TextButton
                                        val isPerson = undoPerson
                                        mutate {
                                            val removed =
                                                if (isPerson) repository.removePerson(id)
                                                else repository.removeDate(id)
                                            undoId = null
                                            notice =
                                                if (removed) R.string.memory_controls_included
                                                else R.string.memory_controls_gone
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.testTag("memory-controls-undo"),
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor = LocalContentColor.current
                                        ),
                                ) {
                                    Text(stringResource(R.string.memory_controls_undo))
                                }
                        }
                    }
                }
                // Keep the receipt actionable after process/saved-state restoration even without a
                // transient notice.
                if (notice == null && undoId != null)
                    item {
                        TextButton(
                            {
                                val id = undoId ?: return@TextButton
                                val isPerson = undoPerson
                                mutate {
                                    val removed =
                                        if (isPerson) repository.removePerson(id)
                                        else repository.removeDate(id)
                                    undoId = null
                                    notice =
                                        if (removed) R.string.memory_controls_included
                                        else R.string.memory_controls_gone
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("memory-controls-undo"),
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = LocalContentColor.current
                                ),
                        ) {
                            Text(stringResource(R.string.memory_controls_undo))
                        }
                    }
                when (page) {
                    MemoryControlPage.Home -> {
                        item {
                            MemoryControlPanel {
                                Text(stringResource(R.string.memory_controls_scope))
                                Text(
                                    stringResource(R.string.memory_controls_originals),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        item {
                            Text(
                                stringResource(
                                    R.string.memory_controls_count,
                                    count,
                                    MemoryExclusionRepository.MaximumRules,
                                ),
                                Modifier.testTag("memory-controls-count"),
                            )
                        }
                        if (atLimit) item { Text(stringResource(R.string.memory_controls_limit)) }
                        item {
                            Button(
                                {
                                    draft = MemoryControlsDraft()
                                    page = MemoryControlPage.Dates
                                    notice = null
                                },
                                enabled = loaded && !busy && !atLimit,
                                modifier =
                                    Modifier.fillMaxWidth().testTag("memory-controls-add-dates"),
                            ) {
                                Text(stringResource(R.string.memory_controls_add_dates))
                            }
                        }
                        item {
                            OutlinedButton(
                                {
                                    page = MemoryControlPage.People
                                    notice = null
                                },
                                enabled = loaded && !busy && !atLimit,
                                modifier =
                                    Modifier.fillMaxWidth().testTag("memory-controls-add-person"),
                            ) {
                                Text(stringResource(R.string.memory_controls_add_person))
                            }
                        }
                        if (loaded && count == 0)
                            item {
                                Text(
                                    stringResource(R.string.memory_controls_empty),
                                    Modifier.testTag("memory-controls-empty"),
                                )
                            }
                        if (dates.value.orEmpty().isNotEmpty())
                            item {
                                Text(
                                    stringResource(R.string.memory_controls_dates),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        items(dates.value.orEmpty(), key = { "date-${it.ruleId}" }) { row ->
                            MemoryControlRule(
                                dateSummary(row),
                                "memory-controls-date-${row.ruleId}",
                                !busy,
                            ) {
                                selected = row.ruleId
                                page = MemoryControlPage.ReviewDate
                                notice = null
                            }
                        }
                        if (people.value.orEmpty().isNotEmpty())
                            item {
                                Text(
                                    stringResource(R.string.memory_controls_people),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        items(people.value.orEmpty(), key = { "person-${it.ruleId}" }) { row ->
                            MemoryControlRule(
                                row.displayName ?: stringResource(R.string.memory_controls_unnamed),
                                "memory-controls-person-${row.ruleId}",
                                !busy,
                            ) {
                                selected = row.ruleId
                                page = MemoryControlPage.ReviewPerson
                                notice = null
                            }
                        }
                    }
                    MemoryControlPage.Dates -> {
                        item { Text(stringResource(R.string.memory_controls_date_hint)) }
                        item {
                            OutlinedTextField(
                                draft.start,
                                { value -> draft = draft.copy(start = value.take(10)) },
                                label = { Text(stringResource(R.string.memory_controls_start)) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("memory-controls-start"),
                            )
                        }
                        item {
                            OutlinedTextField(
                                draft.end,
                                { value -> draft = draft.copy(end = value.take(10)) },
                                label = { Text(stringResource(R.string.memory_controls_end)) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("memory-controls-end"),
                            )
                        }
                        item {
                            OutlinedTextField(
                                draft.zone,
                                { value -> draft = draft.copy(zone = value.take(100)) },
                                label = { Text(stringResource(R.string.memory_controls_zone)) },
                                supportingText = {
                                    Text(stringResource(R.string.memory_controls_zone_hint))
                                },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("memory-controls-zone"),
                            )
                        }
                        if (
                            draft.range() == null &&
                                (draft.start.isNotEmpty() || draft.end.isNotEmpty())
                        )
                            item {
                                Text(
                                    stringResource(R.string.memory_controls_invalid),
                                    Modifier.testTag("memory-controls-invalid"),
                                )
                            }
                        item {
                            Button(
                                { confirming = true },
                                enabled = loaded && !busy && draft.range() != null,
                                modifier = Modifier.fillMaxWidth().testTag("memory-controls-save"),
                            ) {
                                Text(stringResource(R.string.memory_controls_review))
                            }
                        }
                    }
                    MemoryControlPage.People ->
                        item {
                            MemoryControlPeople(
                                peopleRepository,
                                thumbnailLoader,
                                !busy,
                                onAnalysis,
                            ) { option ->
                                personId = option.clusterId
                                personVersion = option.algorithmVersion
                                confirming = true
                            }
                        }
                    MemoryControlPage.ReviewDate -> {
                        if (loaded && date == null)
                            item { Text(stringResource(R.string.memory_controls_gone)) }
                        date?.let { row ->
                            item {
                                MemoryControlPanel {
                                    Text(dateSummary(row))
                                    Text(stringResource(R.string.memory_controls_date_review))
                                }
                            }
                            item { Text(stringResource(R.string.memory_controls_scope)) }
                            item {
                                OutlinedButton(
                                    { confirming = true },
                                    enabled = !busy,
                                    modifier =
                                        Modifier.fillMaxWidth().testTag("memory-controls-include"),
                                ) {
                                    Text(stringResource(R.string.memory_controls_include))
                                }
                            }
                        }
                    }
                    MemoryControlPage.ReviewPerson -> {
                        if (loaded && person == null)
                            item { Text(stringResource(R.string.memory_controls_gone)) }
                        person?.let { row ->
                            item {
                                MemoryControlPersonReview(
                                    row,
                                    repository,
                                    peopleRepository,
                                    retry,
                                ) {
                                    error = R.string.memory_controls_error
                                }
                            }
                            item { Text(stringResource(R.string.memory_controls_scope)) }
                            item {
                                OutlinedButton(
                                    { confirming = true },
                                    enabled = !busy,
                                    modifier =
                                        Modifier.fillMaxWidth().testTag("memory-controls-include"),
                                ) {
                                    Text(stringResource(R.string.memory_controls_include))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dateSummary(row: MemoryDateExclusionEntity) =
    "${LocalDate.ofEpochDay(row.startDay)} – ${LocalDate.ofEpochDay(row.endDay)}\n${row.zoneId}"

@Composable
private fun MemoryControlPanel(
    error: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        contentColor =
            if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun MemoryControlRule(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.memory_controls_review),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private data class MemoryLoaded<T>(val loaded: Boolean = false, val value: T? = null)

@Composable
private fun <T> memoryValue(flow: Flow<T>, retry: Int, onError: () -> Unit): MemoryLoaded<T> {
    val report by rememberUpdatedState(onError)
    return produceState(MemoryLoaded<T>(), flow, retry) {
            value = MemoryLoaded()
            try {
                flow.collect { value = MemoryLoaded(true, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                report()
            }
        }
        .value
}

@Composable
private fun MemoryControlPersonReview(
    row: MemoryPersonExclusionEntity,
    repository: MemoryExclusionRepository,
    peopleRepository: GallerySmartAlbumRepository,
    retry: Int,
    onError: () -> Unit,
) {
    val person =
        memoryValue(
            remember(peopleRepository, row.ruleId) {
                peopleRepository.person(row.clusterId, row.algorithmVersion)
            },
            retry,
            onError,
        )
    val known =
        memoryValue(
            remember(repository, row.ruleId) { repository.knownSourceCount(row.ruleId) },
            retry,
            onError,
        )
    MemoryControlPanel {
        Text(
            person.value?.displayName
                ?: row.displayName
                ?: stringResource(R.string.memory_controls_unnamed),
            style = MaterialTheme.typography.titleLarge,
        )
        if (!person.loaded || !known.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (person.loaded && person.value == null)
            Text(
                stringResource(R.string.memory_controls_missing),
                Modifier.testTag("memory-controls-missing-person"),
            )
        known.value?.let {
            Text(
                stringResource(R.string.memory_controls_known, it),
                Modifier.testTag("memory-controls-known"),
            )
        }
        Text(
            stringResource(R.string.memory_controls_identity, row.clusterId, row.algorithmVersion),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.memory_controls_analysis_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MemoryControlPeople(
    repository: GallerySmartAlbumRepository,
    loader: ThumbnailLoader?,
    enabled: Boolean,
    onAnalysis: () -> Unit,
    onSelect: (SmartAlbumPersonOption) -> Unit,
) {
    val people = remember(repository) { repository.people() }.collectAsLazyPagingItems()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.memory_controls_analysis_hint))
        OutlinedButton(
            onAnalysis,
            enabled = enabled,
            modifier = Modifier.testTag("memory-controls-analysis"),
        ) {
            Text(stringResource(R.string.memory_controls_analysis))
        }
        when (people.loadState.refresh) {
            is LoadState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is LoadState.Error -> {
                Text(stringResource(R.string.memory_controls_error))
                TextButton({ people.retry() }) {
                    Text(stringResource(R.string.memory_controls_retry))
                }
            }
            else ->
                if (people.itemCount == 0)
                    Text(
                        stringResource(R.string.memory_controls_no_people),
                        Modifier.testTag("memory-controls-no-people"),
                    )
        }
        // Independent bounded viewport avoids composing an entire local people library.
        LazyColumn(
            Modifier.fillMaxWidth()
                .heightIn(min = 120.dp, max = 480.dp)
                .testTag("memory-controls-people-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                people.itemCount,
                key =
                    people.itemKey {
                        "${it.clusterId.length}:${it.clusterId}${it.algorithmVersion}"
                    },
            ) { index ->
                people[index]?.let { person ->
                    val cover =
                        memoryValue(
                            remember(repository, person.clusterId, person.algorithmVersion) {
                                repository.personCover(person.clusterId, person.algorithmVersion)
                            },
                            0,
                        ) {}
                    OutlinedCard(
                        { onSelect(person) },
                        enabled = enabled,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("memory-controls-pick-${person.clusterId}"),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MemoryControlImage(cover.value, loader)
                            Text(
                                person.displayName
                                    ?: stringResource(R.string.memory_controls_unnamed),
                                Modifier.weight(1f).padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
            if (people.loadState.append is LoadState.Loading)
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (people.loadState.append is LoadState.Error)
                item {
                    TextButton({ people.retry() }) {
                        Text(stringResource(R.string.memory_controls_retry))
                    }
                }
        }
    }
}

@Composable
private fun MemoryControlImage(photo: MediaItemEntity?, loader: ThumbnailLoader?) {
    val request =
        remember(photo?.volumeName, photo?.mediaStoreId, photo?.generationModified) {
            photo?.let {
                ThumbnailRequest(
                    MediaKey(it.volumeName, it.mediaStoreId),
                    it.generationModified,
                    256,
                    256,
                )
            }
        }
    key(request, loader) {
        val bitmap by
            produceState<Bitmap?>(null, request, loader) {
                try {
                    value = request?.let { loader?.load(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        Surface(
            Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } ?: Icon(GalleryIcons.Image, null)
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MemoryControlConfirm(
    title: Int,
    body: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onConfirm, modifier = Modifier.testTag("memory-controls-confirm")) {
                Text(stringResource(title))
            }
        },
        dismissButton = {
            TextButton(onCancel, modifier = Modifier.testTag("memory-controls-cancel")) {
                Text(stringResource(R.string.memory_controls_cancel))
            }
        },
    )
}
