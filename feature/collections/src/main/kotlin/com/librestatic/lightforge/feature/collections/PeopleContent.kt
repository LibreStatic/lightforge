package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.galleryWindowClass
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot

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

/** The one analysis state the hub renders; actions are derived from it, never guessed. */
internal enum class PeopleAnalysisPhase { Running, Paused, Complete, Ready }

internal fun PeopleUiState.analysisPhase(): PeopleAnalysisPhase = when {
    paused || analysisStage == PeopleAnalysisStage.Paused -> PeopleAnalysisPhase.Paused
    running || waiting -> PeopleAnalysisPhase.Running
    analysisStage == PeopleAnalysisStage.Complete -> PeopleAnalysisPhase.Complete
    else -> PeopleAnalysisPhase.Ready
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun avatarShape(): Shape = MaterialShapes.Cookie9Sided.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun heroShape(): Shape = MaterialShapes.Cookie12Sided.toShape()

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
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val selected = state.selectedPerson
    Column(modifier.fillMaxSize()) {
        if (selected == null) {
            var menuOpen by remember { mutableStateOf(false) }
            GalleryTopAppBar(
                title = stringResource(R.string.people_title),
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.people_back),
                actions = {
                    if (state.consentGranted) Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("people_menu")) {
                            Icon(GalleryIcons.More, contentDescription = stringResource(R.string.people_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_analyze_all)) },
                                onClick = { menuOpen = false; onAnalyzeAll() },
                                enabled = state.analysisPhase() != PeopleAnalysisPhase.Running,
                                leadingIcon = { Icon(GalleryIcons.Repeat, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_delete_all)) },
                                onClick = { menuOpen = false; confirmDelete = true },
                                leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null) },
                                modifier = Modifier.testTag("people_delete_all"),
                            )
                        }
                    }
                },
            )
            PeopleHub(
                state = state,
                loader = thumbnailLoader,
                onShowPrivacy = { showPrivacy = true },
                onEnable = onEnable,
                onPause = onPause,
                onResume = onResume,
                onAnalyzeAll = onAnalyzeAll,
                onPersonClick = onPersonClick,
                onResetMe = onResetMe,
                onUnhidePerson = onUnhidePerson,
                modifier = Modifier.weight(1f),
            )
        } else {
            PersonDetailScreen(
                person = selected,
                members = state.selectedMembers,
                otherPeople = state.people.filter { it.clusterId != selected.clusterId },
                loader = thumbnailLoader,
                onBack = onBack,
                onRename = onRenamePerson,
                onHide = onHidePerson,
                onSetAsMe = onSetSelectedAsMe,
                onMemberClick = onMemberClick,
                onLoadMoreMembers = onLoadMoreMembers,
                onMerge = onMergePerson,
                onSplit = onSplitFaces,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        icon = { Icon(GalleryIcons.Trash, contentDescription = null) },
        title = { Text(stringResource(R.string.people_delete_title)) },
        text = { Text(stringResource(R.string.people_delete_body)) },
        confirmButton = {
            TextButton(
                onClick = { confirmDelete = false; onDeleteAll() },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("people_delete_confirm"),
            ) { Text(stringResource(R.string.people_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.people_cancel)) } },
    )
    if (showPrivacy) AlertDialog(
        onDismissRequest = { showPrivacy = false },
        icon = { Icon(GalleryIcons.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.people_privacy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.people_on_device_notice))
                Text(stringResource(R.string.people_privacy))
            }
        },
        confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.people_privacy_ok)) } },
    )
}

private val fullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

@Composable
private fun PeopleHub(
    state: PeopleUiState,
    loader: ThumbnailLoader?,
    onShowPrivacy: () -> Unit,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyzeAll: () -> Unit,
    onPersonClick: (String) -> Unit,
    onResetMe: () -> Unit,
    onUnhidePerson: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.consentGranted) {
        PeopleConsent(onEnable = onEnable, onShowPrivacy = onShowPrivacy, modifier = modifier)
        return
    }
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    // Large text turns the avatar grid into a list so names never collapse to a letter a line.
    val largeText = LocalDensity.current.fontScale > 1.5f
    LazyVerticalGrid(
        columns = if (largeText) GridCells.Fixed(1) else GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxWidth().testTag("people_hub"),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = fullSpan) { PrivacyRow(onShowPrivacy) }
        item(span = fullSpan) {
            AnalysisCard(state, onPause = onPause, onResume = onResume, onAnalyzeAll = onAnalyzeAll)
        }
        val me = state.me
        if (me != null) item(span = fullSpan) { MeCard(me, loader, onResetMe) }
        else if (state.people.isNotEmpty()) item(span = fullSpan) { MePrompt() }
        if (state.people.isEmpty()) {
            item(span = fullSpan) {
                Text(
                    stringResource(
                        if (state.analysisStage == PeopleAnalysisStage.Complete) R.string.people_empty_complete
                        else R.string.people_empty,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp).testTag("people_empty"),
                )
            }
        } else {
            item(span = fullSpan) { SectionTitle(stringResource(R.string.people_found_title)) }
            items(state.people, key = { it.clusterId }) { person ->
                if (largeText) PersonRow(person, loader) { onPersonClick(person.clusterId) }
                else PersonAvatarTile(person, loader) { onPersonClick(person.clusterId) }
            }
        }
        if (state.hiddenPeople.isNotEmpty()) {
            item(span = fullSpan) {
                HiddenPeopleRow(
                    count = state.hiddenPeople.size,
                    expanded = hiddenExpanded,
                    onToggle = { hiddenExpanded = !hiddenExpanded },
                )
            }
            if (hiddenExpanded) items(state.hiddenPeople, key = { "hidden:${it.clusterId}" }, span = { fullSpan() }) { person ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("hidden_person_${person.clusterId}"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonThumbnail(person.coverKey, loader, Modifier.size(48.dp), avatarShape())
                    Text(
                        person.displayName ?: stringResource(R.string.people_default_name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onUnhidePerson(person.clusterId) }) { Text(stringResource(R.string.people_unhide)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun PrivacyRow(onShowPrivacy: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("people_privacy"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(GalleryIcons.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.people_privacy_short),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onShowPrivacy) {
                Icon(GalleryIcons.Info, contentDescription = stringResource(R.string.people_privacy_title))
            }
        }
    }
}

@Composable
private fun PeopleConsent(onEnable: () -> Unit, onShowPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A landscape phone is wide but short: shrink the hero and let the card scroll, so the
        // enable button is always reachable.
        val shortWindow = maxHeight < 480.dp
        val wide = galleryWindowClass(maxWidth) != GalleryWindowClass.Compact
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("people_consent")) {
        if (wide) {
            // Wide layout: a hero card with message and actions on the start side, the hero shape as the anchor.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = if (shortWindow) 12.dp else 24.dp),
                contentAlignment = Alignment.Center,
            ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.widthIn(max = 1_000.dp).fillMaxWidth(),
            ) {
            Row(
                Modifier.padding(if (shortWindow) 24.dp else 40.dp),
                horizontalArrangement = Arrangement.spacedBy(if (shortWindow) 32.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.people_enable_title),
                        style = if (shortWindow) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.people_enable_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PeoplePrivacyPoint(GalleryIcons.Lock, stringResource(R.string.people_on_device_notice))
                    PeoplePrivacyPoint(GalleryIcons.Info, stringResource(R.string.people_privacy))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GalleryExpressiveButton(
                            onClick = onEnable,
                            modifier = Modifier.widthIn(max = 360.dp).heightIn(min = 56.dp).testTag("people_enable"),
                        ) { Text(stringResource(R.string.people_enable)) }
                        TextButton(onClick = onShowPrivacy) {
                            Icon(GalleryIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.people_how_it_works), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = heroShape(),
                    modifier = Modifier.size(if (shortWindow) 160.dp else 260.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(GalleryIcons.User, contentDescription = null, modifier = Modifier.size(if (shortWindow) 72.dp else 112.dp))
                    }
                }
            }
            }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = heroShape(),
                    modifier = Modifier.size(144.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(GalleryIcons.User, contentDescription = null, modifier = Modifier.size(64.dp))
                    }
                }
                Text(
                    stringResource(R.string.people_enable_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.people_enable_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.people_on_device_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                GalleryExpressiveButton(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 56.dp).testTag("people_enable"),
                ) { Text(stringResource(R.string.people_enable)) }
                TextButton(onClick = onShowPrivacy) {
                    Icon(GalleryIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.people_how_it_works), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun PeoplePrivacyPoint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AnalysisCard(
    state: PeopleUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyzeAll: () -> Unit,
) {
    val phase = state.analysisPhase()
    val complete = phase == PeopleAnalysisPhase.Complete
    Surface(
        color = if (complete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (complete) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().testTag("people_status")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when (phase) {
                                PeopleAnalysisPhase.Complete -> GalleryIcons.Check
                                PeopleAnalysisPhase.Paused -> GalleryIcons.Pause
                                PeopleAnalysisPhase.Running, PeopleAnalysisPhase.Ready -> GalleryIcons.User
                            },
                            contentDescription = null,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(peopleStatusText(state), style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.people_progress, state.completedItems.toInt(), state.completedItems),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            GalleryProgressSlot(phase == PeopleAnalysisPhase.Running)
            when (phase) {
                PeopleAnalysisPhase.Running -> FilledTonalButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(GalleryIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.people_pause), modifier = Modifier.padding(start = 8.dp))
                }
                PeopleAnalysisPhase.Paused -> FilledTonalButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(GalleryIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.people_resume), modifier = Modifier.padding(start = 8.dp))
                }
                // Complete: nothing to pause. Re-running stays in the overflow; a fresh library
                // (never analyzed) gets the explicit start action here.
                PeopleAnalysisPhase.Complete -> Unit
                PeopleAnalysisPhase.Ready -> FilledTonalButton(onClick = onAnalyzeAll, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.people_analyze_all))
                }
            }
        }
    }
}

@Composable
private fun MeCard(me: LocalMeUiState, loader: ThumbnailLoader?, onResetMe: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().testTag("me_section"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PersonThumbnail(me.matches.firstOrNull()?.key, loader, Modifier.size(56.dp), avatarShape())
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.me_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.me_summary,
                            pluralStringResource(R.plurals.me_summary_references, me.referenceCount, me.referenceCount),
                            pluralStringResource(R.plurals.me_summary_matches, me.matchCount, me.matchCount),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(
                    onClick = onResetMe,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) { Text(stringResource(R.string.me_reset)) }
            }
            GalleryProgressSlot(!me.ready)
            if (me.matches.size > 1) ThumbnailStrip(me.matches.drop(1), loader)
        }
    }
}

@Composable
private fun MePrompt() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("me_section"),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(GalleryIcons.User, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.people_choose_me_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.people_choose_me_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PersonAvatarTile(person: PersonCardUi, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val title = person.displayName ?: stringResource(R.string.people_default_name)
    val count = pluralStringResource(R.plurals.people_face_count, person.memberCount, person.memberCount)
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $count" }
            .testTag("person_${person.clusterId}")
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PersonThumbnail(person.coverKey, loader, Modifier.fillMaxWidth().aspectRatio(1f), avatarShape())
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonRow(person: PersonCardUi, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val title = person.displayName ?: stringResource(R.string.people_default_name)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick, role = Role.Button)
            .testTag("person_${person.clusterId}").padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PersonThumbnail(person.coverKey, loader, Modifier.size(56.dp), avatarShape())
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.people_face_count, person.memberCount, person.memberCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(GalleryIcons.ChevronForward, contentDescription = null)
    }
}

@Composable
private fun HiddenPeopleRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("people_hidden_toggle"),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.people_hidden_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(
                GalleryIcons.ChevronForward,
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PersonDetailScreen(
    person: PersonCardUi,
    members: List<PersonMemberCardUi>,
    otherPeople: List<PersonCardUi>,
    loader: ThumbnailLoader?,
    onBack: () -> Unit,
    onRename: (String, String?) -> Unit,
    onHide: (String) -> Unit,
    onSetAsMe: (String) -> Unit,
    onMemberClick: (MediaKey) -> Unit,
    onLoadMoreMembers: () -> Unit,
    onMerge: (String, String) -> Unit,
    onSplit: (String, List<PersonMemberCardUi>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = person.displayName ?: stringResource(R.string.people_default_name)
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable(person.clusterId) { mutableStateOf(false) }
    var merging by rememberSaveable(person.clusterId) { mutableStateOf(false) }
    var splitting by rememberSaveable(person.clusterId) { mutableStateOf(false) }
    var splitSelection by remember(person.clusterId) { mutableStateOf(setOf<PersonMemberCardUi>()) }
    Column(modifier.fillMaxWidth()) {
        if (splitting) {
            GalleryTopAppBar(
                title = pluralStringResource(R.plurals.people_split_selected, splitSelection.size, splitSelection.size),
                onBack = { splitting = false; splitSelection = emptySet() },
                navigationContentDescription = stringResource(R.string.people_cancel),
            )
        } else {
            GalleryTopAppBar(
                title = name,
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.people_title),
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("person_menu")) {
                            Icon(GalleryIcons.More, contentDescription = stringResource(R.string.people_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_split)) },
                                onClick = { menuOpen = false; splitting = true; splitSelection = emptySet() },
                                leadingIcon = { Icon(GalleryIcons.Layers, contentDescription = null) },
                                modifier = Modifier.testTag("people_split"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_hide)) },
                                onClick = { menuOpen = false; onHide(person.clusterId) },
                                leadingIcon = { Icon(GalleryIcons.Archive, contentDescription = null) },
                                modifier = Modifier.testTag("people_hide"),
                            )
                        }
                    }
                },
            )
        }
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize().testTag("person_detail"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (splitting) 112.dp else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (splitting) {
                    item(span = fullSpan) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.people_split_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                } else {
                    item(span = fullSpan) {
                        PersonHero(
                            person = person,
                            name = name,
                            loader = loader,
                            canMerge = otherPeople.isNotEmpty(),
                            onEditName = { renaming = true },
                            onSetAsMe = { onSetAsMe(person.clusterId) },
                            onMerge = { merging = true },
                        )
                    }
                }
                itemsIndexed(members, key = { _, it -> "${it.key}:${it.faceOrdinal}" }) { index, member ->
                    if (index == members.lastIndex) LaunchedEffect(members.size) { onLoadMoreMembers() }
                    PersonMemberTile(
                        member,
                        loader,
                        selectable = splitting,
                        selected = member in splitSelection,
                        onClick = {
                            if (splitting) splitSelection = if (member in splitSelection) splitSelection - member else splitSelection + member
                            else onMemberClick(member.key)
                        },
                    )
                }
            }
            if (splitting) {
                // A split must leave at least one face with this person.
                val canSplit = splitSelection.isNotEmpty() && splitSelection.size < members.size
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                ) {
                    GalleryExpressiveButton(
                        onClick = {
                            onSplit(person.clusterId, splitSelection.toList())
                            splitting = false
                            splitSelection = emptySet()
                        },
                        enabled = canSplit,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("people_split_confirm"),
                    ) {
                        Icon(GalleryIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            pluralStringResource(R.plurals.people_split_confirm, splitSelection.size, splitSelection.size),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
    if (renaming) RenameDialog(
        initial = person.displayName.orEmpty(),
        onDismiss = { renaming = false },
        onSave = { onRename(person.clusterId, it); renaming = false },
    )
    if (merging) MergeSheet(
        people = otherPeople,
        loader = loader,
        onDismiss = { merging = false },
        onMerge = { target -> merging = false; onMerge(person.clusterId, target) },
    )
}

@Composable
private fun PersonHero(
    person: PersonCardUi,
    name: String,
    loader: ThumbnailLoader?,
    canMerge: Boolean,
    onEditName: () -> Unit,
    onSetAsMe: () -> Unit,
    onMerge: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            PersonThumbnail(person.coverKey, loader, Modifier.size(152.dp), heroShape())
            Surface(
                onClick = onEditName,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("people_rename"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(GalleryIcons.Edit, contentDescription = stringResource(R.string.people_rename))
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            pluralStringResource(R.plurals.people_face_count, person.memberCount, person.memberCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            FilledTonalButton(onClick = onSetAsMe, modifier = Modifier.heightIn(min = 48.dp).testTag("people_set_me")) {
                Icon(GalleryIcons.User, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.me_set_from_person), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = onMerge,
                enabled = canMerge,
                modifier = Modifier.heightIn(min = 48.dp).testTag("people_merge"),
            ) {
                Icon(GalleryIcons.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.people_merge), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_rename)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.people_name_label)) },
                modifier = Modifier.testTag("people_name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, modifier = Modifier.testTag("people_save_name")) {
                Text(stringResource(R.string.people_save_name))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.people_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(
    people: List<PersonCardUi>,
    loader: ThumbnailLoader?,
    onDismiss: () -> Unit,
    onMerge: (String) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.people_merge_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.people_merge_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(people, key = { it.clusterId }) { person ->
                    val isSelected = person.clusterId == target
                    Surface(
                        onClick = { target = person.clusterId },
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().testTag("merge_target_${person.clusterId}")
                            .semantics { selected = isSelected },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PersonThumbnail(person.coverKey, loader, Modifier.size(48.dp), avatarShape())
                            Column(Modifier.weight(1f)) {
                                Text(person.displayName ?: stringResource(R.string.people_default_name), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    pluralStringResource(R.plurals.people_face_count, person.memberCount, person.memberCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (isSelected) Icon(GalleryIcons.CheckCircle, contentDescription = null)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.people_cancel))
                }
                Button(
                    onClick = { target?.let(onMerge) },
                    enabled = target != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("people_merge_confirm"),
                ) { Text(stringResource(R.string.people_merge_confirm)) }
            }
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
private fun PersonMemberTile(
    member: PersonMemberCardUi,
    loader: ThumbnailLoader?,
    selectable: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.people_open_photo)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small)
            .then(
                if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.small)
                else Modifier,
            )
            .testTag("person_member_${member.key.mediaStoreId}_${member.faceOrdinal}")
            .semantics {
                contentDescription = description
                if (selectable) this.selected = selected
            }
            .clickable(onClick = onClick),
    ) {
        PersonThumbnail(member.key, loader, Modifier.fillMaxSize(), MaterialTheme.shapes.small)
        if (selectable) Surface(
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp),
        ) {
            if (selected) Box(contentAlignment = Alignment.Center) {
                Icon(GalleryIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ThumbnailStrip(items: List<PersonMemberCardUi>, loader: ThumbnailLoader?) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.take(12), key = { "${it.key}:${it.faceOrdinal}" }) { item ->
            PersonThumbnail(item.key, loader, Modifier.size(64.dp), MaterialTheme.shapes.medium)
        }
    }
}

/** Media thumbnail with a neutral placeholder; also used by the cleanup review. */
@Composable
internal fun PersonThumbnail(
    key: MediaKey?,
    loader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = if (key == null || loader == null) null else runCatching {
            loader.load(ThumbnailRequest(key, 0, PeopleThumbSize, PeopleThumbSize)).asImageBitmap()
        }.getOrNull()
    }
    val image = bitmap
    if (image == null) Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    else Image(image, contentDescription = null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
}
