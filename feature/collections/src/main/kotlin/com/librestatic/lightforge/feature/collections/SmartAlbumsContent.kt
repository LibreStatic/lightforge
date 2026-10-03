package com.librestatic.lightforge.feature.collections

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.data.*
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot

private enum class SmartPage {
    Home,
    Editor,
    Preview,
    Topics,
    People,
    Detail,
    Exclusions,
    Pending,
}

private fun MediaItemEntity.smartKey() = MediaKey(volumeName, mediaStoreId)

private fun MediaKey.tag() = "$volumeName:$mediaStoreId"

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SmartAlbumsContent(
    repository: GallerySmartAlbumRepository,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    onAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(SmartPage.Home) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by
        rememberSaveable(stateSaver = SmartAlbumDraft.Saver) { mutableStateOf(SmartAlbumDraft()) }
    var original by
        rememberSaveable(stateSaver = SmartAlbumDraft.Saver) { mutableStateOf(SmartAlbumDraft()) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var undo by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var album by remember { mutableStateOf<SmartAlbumEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(repository, selectedId, retry) {
        album = null
        loaded = false
        try {
            selectedId?.let {
                repository.observe(it).collect { row ->
                    album = row
                    loaded = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = R.string.smart_error
        }
    }
    fun home() {
        page = SmartPage.Home
        selectedId = null
        error = null
        undo = null
    }
    fun leaveEditor() {
        page = if (draft.albumId == null) SmartPage.Home else SmartPage.Detail
        error = null
    }
    fun back() {
        if (busy) return
        error = null
        when (page) {
            SmartPage.Home -> onBack()
            SmartPage.Editor -> if (draft != original) discard = true else leaveEditor()
            SmartPage.Preview,
            SmartPage.Topics,
            SmartPage.People -> page = SmartPage.Editor
            SmartPage.Pending -> page = SmartPage.Preview
            SmartPage.Exclusions -> page = SmartPage.Detail
            SmartPage.Detail -> home()
        }
    }
    fun edit(row: SmartAlbumEntity?) {
        draft = row?.let(SmartAlbumDraft::from) ?: SmartAlbumDraft()
        original = draft
        page = SmartPage.Editor
        error = null
    }
    fun mutate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: SmartAlbumChanged) {
                error = R.string.smart_changed
            } catch (_: Exception) {
                error = R.string.smart_error
            } finally {
                busy = false
            }
        }
    }
    BackHandler { back() }
    if (discard)
        SmartConfirm(
            R.string.smart_discard,
            R.string.smart_discard_hint,
            onCancel = { discard = false },
        ) {
            discard = false
            draft = original
            leaveEditor()
        }
    if (deleting)
        SmartConfirm(
            R.string.smart_delete,
            R.string.smart_delete_hint,
            onCancel = { deleting = false },
        ) {
            deleting = false
            val row = album ?: return@SmartConfirm
            mutate {
                repository.delete(row.albumId, row.revision)
                home()
            }
        }
    val title =
        when (page) {
            SmartPage.Home -> stringResource(R.string.smart_title)
            SmartPage.Editor ->
                stringResource(
                    if (draft.albumId == null) R.string.smart_create else R.string.smart_edit
                )
            SmartPage.Preview -> stringResource(R.string.smart_preview)
            SmartPage.Topics -> stringResource(R.string.smart_topic)
            SmartPage.People -> stringResource(R.string.smart_person)
            SmartPage.Exclusions,
            SmartPage.Pending -> stringResource(R.string.smart_excluded)
            SmartPage.Detail -> album?.name ?: stringResource(R.string.smart_title)
        }
    val stale = draft.albumId != null && loaded && album?.revision != draft.revision
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier =
            modifier.testTag("smart-albums-screen").semantics { testTagsAsResourceId = true },
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                // back() ignores presses while a change is being saved.
                GalleryTopAppBar(
                    title = title,
                    onBack = { back() },
                    navigationContentDescription = stringResource(R.string.smart_back),
                    modifier = Modifier.testTag("smart-top-bar").semantics { heading() },
                    // The shell scaffold already placed this route below the status bar.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                GalleryProgressSlot(busy)
                error?.let { id ->
                    Text(
                        stringResource(id),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(
                        onClick = {
                            error = null
                            retry++
                        }
                    ) {
                        Text(stringResource(R.string.smart_retry))
                    }
                }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val content =
                Modifier.widthIn(max = if (page == SmartPage.Editor) 720.dp else 1000.dp)
                    .fillMaxSize()
            key(page) {
                when (page) {
                    SmartPage.Home ->
                        SmartHome(
                            repository,
                            content,
                            onNew = {
                                selectedId = null
                                edit(null)
                            },
                            onOpen = {
                                selectedId = it
                                page = SmartPage.Detail
                            },
                            onAnalysis = onAnalysis,
                        )
                    SmartPage.Editor ->
                        SmartEditor(
                            draft,
                            stale,
                            busy,
                            repository,
                            retry,
                            content,
                            onError = { error = it },
                            onDraft = { change -> draft = change(draft) },
                            onTopic = { page = SmartPage.Topics },
                            onPerson = { page = SmartPage.People },
                            onPreview = { page = SmartPage.Preview },
                            onReload = { album?.let { edit(it) } },
                            onSave = {
                                val snapshot = draft
                                val rule = snapshot.rule() ?: return@SmartEditor
                                mutate {
                                    if (snapshot.albumId == null)
                                        selectedId =
                                            repository.create(
                                                snapshot.name,
                                                rule,
                                                snapshot.excluded,
                                            )
                                    else
                                        repository.update(
                                            snapshot.albumId,
                                            requireNotNull(snapshot.revision),
                                            snapshot.name,
                                            rule,
                                            snapshot.excluded,
                                        )
                                    page = SmartPage.Detail
                                    undo = null
                                }
                            },
                        )
                    SmartPage.Topics ->
                        SmartTopics(repository, draft.topic, content) {
                            draft = draft.copy(topic = it)
                            page = SmartPage.Editor
                        }
                    SmartPage.People ->
                        SmartPeople(repository, thumbnailLoader, draft.personId, content) { person
                            ->
                            draft =
                                draft.copy(
                                    personId = person?.clusterId,
                                    personVersion = person?.algorithmVersion,
                                )
                            page = SmartPage.Editor
                        }
                    SmartPage.Preview -> {
                        val rule = draft.rule()
                        if (rule != null && !stale)
                            SmartResults(
                                repository,
                                thumbnailLoader,
                                rule,
                                draft.excluded,
                                editing = if (draft.albumId != null) album else null,
                                id = null,
                                busy = busy,
                                retry = retry,
                                modifier = content,
                                onError = { error = it },
                                header = {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Column {
                                            Text(stringResource(R.string.smart_preview_hint))
                                            if (draft.albumId != null)
                                                Text(
                                                    stringResource(
                                                        R.string.smart_existing_exclusions
                                                    )
                                                )
                                            OutlinedButton(
                                                onClick = { page = SmartPage.Pending },
                                                modifier =
                                                    Modifier.fillMaxWidth().testTag("smart-pending"),
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.smart_pending_count,
                                                        draft.excluded.size,
                                                    )
                                                )
                                            }
                                        }
                                    }
                                },
                                onExclude = { key ->
                                    if (draft.excluded.size >= 200) error = R.string.smart_limit
                                    else draft = draft.exclude(key)
                                },
                            )
                        else Text(stringResource(R.string.smart_changed), Modifier.padding(16.dp))
                    }
                    SmartPage.Pending ->
                        SmartPending(repository, thumbnailLoader, draft.excluded, content) {
                            draft = draft.include(it)
                        }
                    SmartPage.Detail,
                    SmartPage.Exclusions -> {
                        val row = album
                        if (row == null)
                            Column(content.padding(16.dp)) {
                                if (!loaded && error == null) GalleryIndeterminateProgressIndicator()
                                else {
                                    Text(stringResource(R.string.smart_missing))
                                    TextButton(onClick = { retry++ }) {
                                        Text(stringResource(R.string.smart_retry))
                                    }
                                    TextButton(onClick = { home() }) {
                                        Text(stringResource(R.string.smart_title))
                                    }
                                }
                            }
                        else if (page == SmartPage.Exclusions)
                            SmartExclusions(repository, thumbnailLoader, row, busy, content) { key
                                ->
                                mutate { repository.includeAgain(row.albumId, row.revision, key) }
                            }
                        else
                            SmartResults(
                                repository,
                                thumbnailLoader,
                                row.rule(),
                                emptyList(),
                                editing = null,
                                id = row.albumId,
                                busy = busy,
                                retry = retry,
                                modifier = content,
                                onError = { error = it },
                                header = {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SmartSummary(row, repository, retry) { error = it }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedButton(
                                                    onClick = { edit(row) },
                                                    enabled = !busy,
                                                    modifier =
                                                        Modifier.weight(1f).testTag("smart-edit"),
                                                ) {
                                                    Text(stringResource(R.string.smart_edit))
                                                }
                                                OutlinedButton(
                                                    onClick = { page = SmartPage.Exclusions },
                                                    enabled = !busy,
                                                    modifier =
                                                        Modifier.weight(1f)
                                                            .testTag("smart-manage-exclusions"),
                                                ) {
                                                    Text(stringResource(R.string.smart_excluded))
                                                }
                                            }
                                            TextButton(
                                                onClick = { deleting = true },
                                                enabled = !busy,
                                                modifier =
                                                    Modifier.fillMaxWidth().testTag("smart-delete"),
                                            ) {
                                                Text(stringResource(R.string.smart_delete))
                                            }
                                            undo?.let { receipt ->
                                                Surface(
                                                    color =
                                                        MaterialTheme.colorScheme
                                                            .secondaryContainer,
                                                    contentColor =
                                                        MaterialTheme.colorScheme
                                                            .onSecondaryContainer,
                                                    shape = MaterialTheme.shapes.medium,
                                                ) {
                                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                                        Text(
                                                            stringResource(
                                                                R.string.smart_excluded_notice
                                                            ),
                                                            modifier =
                                                                Modifier.semantics {
                                                                    liveRegion =
                                                                        LiveRegionMode.Polite
                                                                },
                                                        )
                                                        TextButton(
                                                            onClick = {
                                                                mutate {
                                                                    val applied =
                                                                        repository.undoExclude(
                                                                            SmartAlbumExclusionReceipt(
                                                                                receipt[0],
                                                                                MediaKey(
                                                                                    receipt[1],
                                                                                    receipt[2]
                                                                                        .toLong(),
                                                                                ),
                                                                                receipt[3],
                                                                            )
                                                                        )
                                                                    undo = null
                                                                    if (!applied)
                                                                        error =
                                                                            R.string.smart_changed
                                                                }
                                                            },
                                                            enabled = !busy,
                                                            modifier =
                                                                Modifier.testTag("smart-undo"),
                                                            colors =
                                                                ButtonDefaults.textButtonColors(
                                                                    contentColor =
                                                                        LocalContentColor.current
                                                                ),
                                                        ) {
                                                            Text(
                                                                stringResource(R.string.smart_undo)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onExclude = { key ->
                                    mutate {
                                        val receipt =
                                            repository.exclude(row.albumId, row.revision, key)
                                        undo =
                                            arrayListOf(
                                                receipt.albumId,
                                                receipt.key.volumeName,
                                                receipt.key.mediaStoreId.toString(),
                                                receipt.token,
                                            )
                                    }
                                },
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartHome(
    repository: GallerySmartAlbumRepository,
    modifier: Modifier,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onAnalysis: () -> Unit,
) {
    val albums = remember(repository) { repository.albums() }.collectAsLazyPagingItems()
    LazyColumn(
        modifier.testTag("smart-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.smart_local))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().testTag("smart-create")) {
                Text(stringResource(R.string.smart_create))
            }
        }
        item { SmartPagingStatus(albums, R.string.smart_empty_albums) }
        items(albums.itemCount, key = albums.itemKey { it.albumId }) { index ->
            albums[index]?.let { row ->
                OutlinedCard(
                    onClick = { onOpen(row.albumId) },
                    modifier = Modifier.fillMaxWidth().testTag("smart-album-${row.albumId}"),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.smart_live),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            TextButton(onClick = onAnalysis, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.smart_analysis))
            }
        }
    }
}

@Composable
private fun SmartEditor(
    draft: SmartAlbumDraft,
    stale: Boolean,
    busy: Boolean,
    repository: GallerySmartAlbumRepository,
    retry: Int,
    modifier: Modifier,
    onError: (Int) -> Unit,
    onDraft: ((SmartAlbumDraft) -> SmartAlbumDraft) -> Unit,
    onTopic: () -> Unit,
    onPerson: () -> Unit,
    onPreview: () -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
) {
    val person = smartPerson(repository, draft.personId, draft.personVersion, retry, onError)
    LazyColumn(
        modifier.widthIn(max = 720.dp).testTag("smart-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.smart_rule_hint)) }
        if (stale)
            item {
                Text(stringResource(R.string.smart_changed))
                OutlinedButton(
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth().testTag("smart-reload"),
                ) {
                    Text(stringResource(R.string.smart_reload))
                }
            }
        item {
            OutlinedTextField(
                draft.name,
                { value -> onDraft { current -> current.copy(name = value.take(80)) } },
                enabled = !busy,
                label = { Text(stringResource(R.string.smart_name)) },
                isError = draft.name.isNotEmpty() && !draft.validName,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("smart-name"),
            )
        }
        item {
            OutlinedButton(
                onClick = onTopic,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("smart-topic"),
            ) {
                Text(stringResource(R.string.smart_topic_value, smartTopic(draft.topic)))
            }
        }
        item {
            OutlinedButton(
                onClick = onPerson,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("smart-person"),
            ) {
                Text(
                    stringResource(
                        R.string.smart_person_value,
                        if (draft.personId == null) stringResource(R.string.smart_any_person)
                        else
                            person?.displayName
                                ?: stringResource(
                                    if (person == null) R.string.smart_unavailable_person
                                    else R.string.smart_unnamed_person
                                ),
                    )
                )
            }
        }
        item {
            OutlinedTextField(
                draft.year,
                { value -> onDraft { current -> current.copy(year = value.take(4)) } },
                enabled = !busy,
                label = { Text(stringResource(R.string.smart_year)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (draft.validYear) R.string.smart_year_hint
                            else R.string.smart_year_error
                        )
                    )
                },
                isError = !draft.validYear,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("smart-year"),
            )
            Text(
                stringResource(R.string.smart_timezone, draft.zoneId),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            val favoriteLabel = stringResource(R.string.smart_favorites)
            Row(
                Modifier.fillMaxWidth().testTag("smart-favorites-row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.smart_favorites), Modifier.weight(1f))
                Switch(
                    draft.favorites,
                    { value -> onDraft { current -> current.copy(favorites = value) } },
                    enabled = !busy,
                    modifier =
                        Modifier.testTag("smart-favorites").semantics {
                            contentDescription = favoriteLabel
                        },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onPreview,
                enabled = !busy && draft.validYear && !stale,
                modifier = Modifier.fillMaxWidth().testTag("smart-preview"),
            ) {
                Text(stringResource(R.string.smart_preview))
            }
            Button(
                onClick = onSave,
                enabled = !busy && draft.validName && draft.validYear && !stale,
                modifier = Modifier.fillMaxWidth().testTag("smart-save"),
            ) {
                Text(stringResource(R.string.smart_save))
            }
            Text(stringResource(R.string.smart_live), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SmartTopics(
    repository: GallerySmartAlbumRepository,
    selected: String?,
    modifier: Modifier,
    onSelect: (String?) -> Unit,
) {
    val topics = remember(repository) { repository.topics() }.collectAsLazyPagingItems()
    LazyColumn(modifier.testTag("smart-list"), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.smart_analysis_hint))
            SmartOption(
                stringResource(R.string.smart_any_topic),
                selected == null,
                "smart-topic-any",
            ) {
                onSelect(null)
            }
        }
        item { SmartPagingStatus(topics, R.string.smart_empty_topics) }
        items(topics.itemCount, key = topics.itemKey { it }) { index ->
            topics[index]?.let { topic ->
                SmartOption(smartTopic(topic), selected == topic, "smart-topic-$topic") {
                    onSelect(topic)
                }
            }
        }
    }
}

@Composable
private fun SmartPeople(
    repository: GallerySmartAlbumRepository,
    loader: ThumbnailLoader?,
    selected: String?,
    modifier: Modifier,
    onSelect: (SmartAlbumPersonOption?) -> Unit,
) {
    val people = remember(repository) { repository.people() }.collectAsLazyPagingItems()
    LazyColumn(modifier.testTag("smart-list"), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.smart_analysis_hint))
            SmartOption(
                stringResource(R.string.smart_any_person),
                selected == null,
                "smart-person-any",
            ) {
                onSelect(null)
            }
        }
        item { SmartPagingStatus(people, R.string.smart_empty_people) }
        items(
            people.itemCount,
            key = people.itemKey { it.clusterId + ":" + it.algorithmVersion },
        ) { index ->
            people[index]?.let { person ->
                val cover =
                    smartValue(
                        remember(repository, person) {
                            repository.personCover(person.clusterId, person.algorithmVersion)
                        },
                        0,
                    ) {}
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(person) }
                        .padding(vertical = 8.dp)
                        .testTag("smart-person-${person.clusterId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmartImage(cover, loader, Modifier.size(72.dp))
                    Text(
                        person.displayName ?: stringResource(R.string.smart_unnamed_person),
                        Modifier.weight(1f).padding(12.dp),
                    )
                    RadioButton(selected == person.clusterId, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun SmartOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .heightIn(min = 48.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(8.dp))
    }
}

@Composable
private fun smartTopic(topic: String?): String =
    when (topic) {
        null -> stringResource(R.string.smart_any_topic)
        "beach" -> stringResource(R.string.smart_beach)
        "nature" -> stringResource(R.string.smart_nature)
        "city" -> stringResource(R.string.smart_city)
        "coffee" -> stringResource(R.string.smart_coffee)
        else -> topic
    }

@Composable
private fun smartPerson(
    repository: GallerySmartAlbumRepository,
    id: String?,
    version: String?,
    retry: Int,
    onError: (Int) -> Unit,
): SmartAlbumPersonOption? =
    if (id == null || version == null) null
    else
        smartValue(
            remember(repository, id, version) { repository.person(id, version) },
            retry,
            onError,
        )

@Composable
private fun <T> smartValue(flow: Flow<T>, retry: Int, onError: (Int) -> Unit): T? {
    val error by rememberUpdatedState(onError)
    return produceState<T?>(null, flow, retry) {
            value = null
            try {
                flow.collect { value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error(R.string.smart_error)
            }
        }
        .value
}

@Composable
private fun SmartSummary(
    row: SmartAlbumEntity,
    repository: GallerySmartAlbumRepository,
    retry: Int,
    onError: (Int) -> Unit,
) {
    val person =
        smartPerson(repository, row.personClusterId, row.personAlgorithmVersion, retry, onError)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.smart_live), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.smart_topic_value, smartTopic(row.topic)))
            Text(
                stringResource(
                    R.string.smart_person_value,
                    if (row.personClusterId == null) stringResource(R.string.smart_any_person)
                    else
                        person?.displayName
                            ?: stringResource(
                                if (person == null) R.string.smart_unavailable_person
                                else R.string.smart_unnamed_person
                            ),
                )
            )
            Text(
                stringResource(
                    R.string.smart_year_value,
                    row.year?.toString() ?: stringResource(R.string.smart_any_year),
                )
            )
            if (row.year != null)
                Text(
                    stringResource(R.string.smart_timezone, row.zoneId),
                    style = MaterialTheme.typography.bodySmall,
                )
            if (row.favoritesOnly) Text(stringResource(R.string.smart_favorites))
        }
    }
}

@Composable
private fun SmartResults(
    repository: GallerySmartAlbumRepository,
    loader: ThumbnailLoader?,
    rule: SmartAlbumRule,
    excluded: List<MediaKey>,
    editing: SmartAlbumEntity?,
    id: String?,
    busy: Boolean,
    retry: Int,
    modifier: Modifier,
    onError: (Int) -> Unit,
    header: LazyGridScope.() -> Unit,
    onExclude: (MediaKey) -> Unit,
) {
    val flow =
        remember(repository, rule, excluded, editing, id) {
            if (id == null) repository.preview(rule, excluded, editing) else repository.media(id)
        }
    val photos = flow.collectAsLazyPagingItems()
    val count =
        smartValue(
            remember(repository, rule, excluded, editing, id) {
                if (id == null) repository.previewCount(rule, excluded, editing)
                else repository.count(id)
            },
            retry,
            onError,
        )
    LazyVerticalGrid(
        GridCells.Adaptive(156.dp),
        modifier.testTag("smart-grid"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (count != null)
                    Text(
                        stringResource(R.string.smart_matches, count),
                        style = MaterialTheme.typography.titleMedium,
                        modifier =
                            Modifier.testTag("smart-count").semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                SmartPagingStatus(photos, R.string.smart_empty_results)
            }
        }
        items(photos.itemCount, key = photos.itemKey { it.smartKey().tag() }) { index ->
            photos[index]?.let { photo ->
                OutlinedCard(
                    Modifier.fillMaxWidth().testTag("smart-photo-${photo.smartKey().tag()}")
                ) {
                    SmartImage(photo, loader, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(
                        photo.displayName ?: stringResource(R.string.smart_photo),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val description =
                        stringResource(
                            R.string.smart_exclude_photo,
                            photo.displayName ?: stringResource(R.string.smart_photo),
                        )
                    TextButton(
                        onClick = { onExclude(photo.smartKey()) },
                        enabled = !busy,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("smart-exclude-${photo.smartKey().tag()}")
                                .semantics { contentDescription = description },
                    ) {
                        Text(stringResource(R.string.smart_exclude))
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartPending(
    repository: GallerySmartAlbumRepository,
    loader: ThumbnailLoader?,
    keys: List<MediaKey>,
    modifier: Modifier,
    onInclude: (MediaKey) -> Unit,
) {
    LazyColumn(
        modifier.testTag("smart-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.smart_pending_hint))
            if (keys.isEmpty()) Text(stringResource(R.string.smart_empty_exclusions))
        }
        items(keys, key = { it.tag() }) { key ->
            SmartExcludedRow(repository, loader, key, false, onInclude)
        }
    }
}

@Composable
private fun SmartExclusions(
    repository: GallerySmartAlbumRepository,
    loader: ThumbnailLoader?,
    row: SmartAlbumEntity,
    busy: Boolean,
    modifier: Modifier,
    onInclude: (MediaKey) -> Unit,
) {
    val exclusions =
        remember(repository, row.albumId) { repository.exclusions(row.albumId) }
            .collectAsLazyPagingItems()
    LazyColumn(
        modifier.testTag("smart-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.smart_restore_hint))
            SmartPagingStatus(exclusions, R.string.smart_empty_exclusions)
        }
        items(
            exclusions.itemCount,
            key = exclusions.itemKey { it.volumeName + ":" + it.mediaStoreId },
        ) { index ->
            exclusions[index]?.let { x ->
                SmartExcludedRow(
                    repository,
                    loader,
                    MediaKey(x.volumeName, x.mediaStoreId),
                    busy,
                    onInclude,
                )
            }
        }
    }
}

@Composable
private fun SmartExcludedRow(
    repository: GallerySmartAlbumRepository,
    loader: ThumbnailLoader?,
    key: MediaKey,
    busy: Boolean,
    onInclude: (MediaKey) -> Unit,
) {
    val photo = smartValue(remember(repository, key) { repository.source(key) }, 0) {}
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmartImage(photo, loader, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(photo?.displayName ?: stringResource(R.string.smart_unavailable_photo))
                TextButton(
                    onClick = { onInclude(key) },
                    enabled = !busy,
                    modifier = Modifier.testTag("smart-include-${key.tag()}"),
                ) {
                    Text(stringResource(R.string.smart_include))
                }
            }
        }
    }
}

@Composable
private fun <T : Any> SmartPagingStatus(items: LazyPagingItems<T>, empty: Int) {
    if (items.loadState.refresh is LoadState.Loading && items.itemCount == 0) GalleryIndeterminateProgressIndicator()
    else if (items.loadState.refresh is LoadState.NotLoading && items.itemCount == 0)
        Text(stringResource(empty))
    if (items.loadState.refresh is LoadState.Error || items.loadState.append is LoadState.Error)
        TextButton(onClick = { items.retry() }) { Text(stringResource(R.string.smart_retry)) }
    if (items.loadState.append is LoadState.Loading)
        GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun SmartImage(photo: MediaItemEntity?, loader: ThumbnailLoader?, modifier: Modifier) {
    val request =
        remember(photo?.smartKey(), photo?.generationModified) {
            photo?.let { ThumbnailRequest(it.smartKey(), it.generationModified, 384, 384) }
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
            modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        photo?.displayName,
                        Modifier.fillMaxSize()
                            .testTag("smart-image-loaded-${photo?.smartKey()?.tag()}"),
                        contentScale = ContentScale.Crop,
                    )
                }
                    ?: Icon(
                        GalleryIcons.Image,
                        stringResource(R.string.smart_photo),
                        Modifier.size(24.dp),
                    )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SmartConfirm(title: Int, hint: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(hint)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("smart-confirm")) {
                Text(stringResource(title))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.smart_cancel)) }
        },
    )
}
