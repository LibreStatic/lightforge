package com.librestatic.lightforge.feature.places

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlacesContent(
    controller: OfflinePlacesController,
    source: PlacesSource,
    onBack: () -> Unit,
    onPhotoClick: (MediaKey) -> Unit,
    thumbnail: (suspend (MediaKey) -> Bitmap?)? = null,
    modifier: Modifier = Modifier,
    locationAccessGranted: Boolean = true,
    onRequestLocationAccess: () -> Unit = {},
    locationIndexing: Boolean = false,
    locationIndexed: Int = 0,
    locationFailed: Int = 0,
    onRefreshLocations: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs by controller.packs.collectAsState()
    val tasks by controller.tasks.collectAsState()
    val storageFailure by controller.storageFailure.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var latitude by rememberSaveable { mutableDoubleStateOf(0.0) }
    var longitude by rememberSaveable { mutableDoubleStateOf(0.0) }
    var zoom by rememberSaveable { mutableDoubleStateOf(3.0) }
    var cameraPack by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var bounds by remember { mutableStateOf(PlaceBounds.World) }
    var years by remember { mutableStateOf<List<Int>>(emptyList()) }
    var page by remember { mutableStateOf(PlacesPhotoPage(emptyList(), 0)) }
    var revision by remember { mutableLongStateOf(0L) }
    var failure by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var mapFailure by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<OfflineMapPackage?>(null) }
    var review by remember { mutableStateOf<Pair<OfflineMapTask, OfflineMapInspection>?>(null) }
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    var regranting by rememberSaveable { mutableStateOf<String?>(null) }
    var worldDialog by rememberSaveable { mutableStateOf(false) }
    var worldConfirmed by rememberSaveable { mutableStateOf(false) }
    var facts by remember { mutableStateOf(controller.deviceFacts()) }
    val selected = packs.firstOrNull { it.id == selectedId } ?: packs.firstOrNull()
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                failure = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = true
            }
        }
    }
    val defaultMapName = stringResource(R.string.places_local_map_name)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null)
                action {
                    val name =
                        context.contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?.take(120) ?: defaultMapName
                    if (regranting != null) controller.regrant(requireNotNull(regranting), uri)
                    else controller.importPackage(uri, name, replacing)
                    replacing = null
                    regranting = null
                }
        }
    BackHandler(onBack = onBack)
    LaunchedEffect(source) { source.revision.collect { revision = it } }
    LaunchedEffect(source, revision, locationAccessGranted) {
        try {
            years = if (locationAccessGranted) source.years() else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure = true
        }
    }
    LaunchedEffect(selected?.id) {
        if (selected != null && cameraPack != selected.id) {
            latitude = (selected.bounds.south + selected.bounds.north) / 2
            longitude =
                if (selected.bounds.west <= selected.bounds.east)
                    (selected.bounds.west + selected.bounds.east) / 2
                else ((selected.bounds.west + selected.bounds.east + 360) / 2 + 540) % 360 - 180
            zoom = selected.minZoom.coerceAtLeast(10).coerceAtMost(selected.maxZoom).toDouble()
            bounds = selected.bounds
            cameraPack = selected.id
        }
        ready = false
        mapFailure = false
    }
    LaunchedEffect(source, bounds, year, revision, locationAccessGranted) {
        try {
            page =
                if (locationAccessGranted) source.query(bounds, year, 2000)
                else PlacesPhotoPage(emptyList(), 0)
            failure = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            page = PlacesPhotoPage(emptyList(), 0)
            failure = true
        }
    }
    val groups =
        remember(page, zoom, locationAccessGranted) {
            if (locationAccessGranted) groupPlacePhotos(page.photos, zoom) else emptyList()
        }
    val group = groups.firstOrNull { it.id == groupId }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.3f
    Surface(
        modifier.fillMaxSize().testTag("places-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
        GalleryTopAppBar(
            title = stringResource(R.string.places_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.places_back),
            modifier = Modifier.testTag("places-top-bar"),
            // The shell scaffold already placed this route below the status bar.
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("places-list"),
            contentPadding = readableListPadding(maxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.places_body)) }
            if (!locationAccessGranted)
                item {
                    Text(stringResource(R.string.places_location_body))
                    Button(
                        onClick = onRequestLocationAccess,
                        modifier = Modifier.testTag("places-location-access"),
                    ) {
                        Text(stringResource(R.string.places_location_allow))
                    }
                }
            if (locationAccessGranted)
                item {
                    Text(
                        stringResource(
                            R.string.places_location_index_status,
                            locationIndexed,
                            locationFailed,
                        ),
                        modifier = Modifier.testTag("places-location-index-status"),
                    )
                    Button(
                        onClick = onRefreshLocations,
                        enabled = !locationIndexing,
                        modifier = Modifier.testTag("places-location-refresh"),
                    ) {
                        Text(
                            stringResource(
                                if (locationIndexing) R.string.places_location_indexing
                                else R.string.places_location_read
                            )
                        )
                    }
                }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = year == null,
                        onClick = { year = null },
                        label = { Text(stringResource(R.string.places_all_years)) },
                        modifier = Modifier.testTag("places-year-all"),
                    )
                    (if (locationAccessGranted) years else emptyList()).forEach { y ->
                        FilterChip(
                            selected = year == y,
                            onClick = { year = y },
                            label = { Text(y.toString()) },
                            modifier = Modifier.testTag("places-year-$y"),
                        )
                    }
                }
            }
            item {
                if (selected == null)
                    Text(
                        stringResource(R.string.places_no_map),
                        modifier = Modifier.testTag("places-no-map"),
                    )
                else if (!facts.rendererCompatible)
                    Text(
                        stringResource(R.string.places_renderer_unavailable),
                        modifier = Modifier.testTag("places-renderer-unavailable"),
                    )
                else if (cameraPack == selected.id) {
                    key(selected.id, dark) {
                        OfflinePlacesMap(
                            selected,
                            controller.file(selected),
                            groups,
                            OfflineMapCamera(latitude, longitude, zoom),
                            dark,
                            onCamera = { camera, view ->
                                latitude = camera.latitude
                                longitude = camera.longitude
                                zoom = camera.zoom
                                bounds = view
                            },
                            onGroup = { groupId = it },
                            onReady = { ready = true },
                            onFailure = { mapFailure = true },
                            modifier = Modifier.fillMaxWidth().height(360.dp).testTag("places-map"),
                        )
                    }
                    Text(
                        stringResource(
                            if (mapFailure) R.string.places_error
                            else if (ready) R.string.places_ready else R.string.places_loading
                        ),
                        modifier =
                            Modifier.testTag(if (ready) "places-map-ready" else "places-map-state"),
                    )
                    Text(
                        remember(selected.attribution) {
                            android.text.Html.fromHtml(
                                    selected.attribution,
                                    android.text.Html.FROM_HTML_MODE_LEGACY,
                                )
                                .toString()
                                .trim()
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (locationAccessGranted && page.totalMatching > page.photos.size)
                item {
                    Text(
                        stringResource(
                            R.string.places_overflow,
                            pluralStringResource(
                                R.plurals.places_overflow_shown,
                                page.photos.size,
                                page.photos.size,
                            ),
                            page.totalMatching,
                        ),
                        modifier = Modifier.testTag("places-overflow"),
                    )
                }
            if (groups.isEmpty())
                item {
                    Text(
                        stringResource(R.string.places_no_photos),
                        modifier = Modifier.testTag("places-no-photos"),
                    )
                }
            items(groups, key = { "group-${it.id}" }) { item ->
                OutlinedButton(
                    onClick = { groupId = item.id },
                    modifier = Modifier.fillMaxWidth().testTag("places-group-${item.id}"),
                ) {
                    Text(
                        pluralStringResource(
                            R.plurals.places_group,
                            item.photos.size,
                            item.photos.size,
                            item.latitude,
                            item.longitude,
                        )
                    )
                }
            }
            if (group != null)
                items(group.photos, key = { "photo-${it.key}" }) { photo ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onPhotoClick(photo.key) }
                            .testTag(
                                "places-photo-${photo.key.volumeName}-${photo.key.mediaStoreId}"
                            ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (thumbnail != null) {
                            var bitmap by
                                remember(photo.key, photo.generation) {
                                    mutableStateOf<Bitmap?>(null)
                                }
                            LaunchedEffect(photo.key, photo.generation) {
                                bitmap = thumbnail(photo.key)
                            }
                            bitmap?.let {
                                Image(
                                    it.asImageBitmap(),
                                    null,
                                    Modifier.size(80.dp),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        TextButton(onClick = { onPhotoClick(photo.key) }) {
                            Text(stringResource(R.string.places_open_photo))
                        }
                    }
                }
            item {
                HorizontalDivider()
                Text(
                    stringResource(R.string.places_packages),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.places_formats))
                Button(
                    onClick = {
                        replacing = null
                        regranting = null
                        picker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.testTag("places-import"),
                ) {
                    Text(stringResource(R.string.places_import))
                }
            }
            items(packs, key = { "pack-${it.id}" }) { pack ->
                Card(Modifier.fillMaxWidth().testTag("places-pack-${pack.id}")) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.places_details,
                                pack.format.name,
                                pack.bytes,
                                pack.minZoom,
                                pack.maxZoom,
                            )
                        )
                        Text(pack.sha256, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { selectedId = pack.id },
                                modifier = Modifier.testTag("places-select-${pack.id}"),
                            ) {
                                Text(stringResource(R.string.places_select))
                            }
                            TextButton(
                                onClick = {
                                    replacing = pack.id
                                    picker.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier.testTag("places-replace-${pack.id}"),
                            ) {
                                Text(stringResource(R.string.places_replace))
                            }
                            TextButton(
                                onClick = { remove = pack },
                                modifier = Modifier.testTag("places-remove-${pack.id}"),
                            ) {
                                Text(stringResource(R.string.places_remove))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.places_world),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.places_world_body))
                Text(
                    stringResource(
                        R.string.places_capacity,
                        Formatter.formatShortFileSize(context, facts.freeBytes),
                        Formatter.formatShortFileSize(context, facts.totalRamBytes),
                    )
                )
                val waiting =
                    OfflineMapEligibility.waiting(facts, OfflineMapCatalog.World.bytes, true)
                if (waiting != null) Text(stringResource(statusResource(waiting)))
                OutlinedButton(
                    onClick = {
                        facts = controller.deviceFacts()
                        worldConfirmed = false
                        worldDialog = true
                    },
                    modifier = Modifier.testTag("places-world"),
                ) {
                    Text(stringResource(R.string.places_world))
                }
            }
            if (failure || storageFailure)
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            stringResource(R.string.places_error),
                            modifier = Modifier.padding(12.dp).testTag("places-error"),
                        )
                    }
                }
            item {
                Text(
                    stringResource(R.string.places_tasks),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(tasks.reversed(), key = { "task-${it.id}" }) { job ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(job.name)
                        Text(
                            stringResource(statusResource(job.status)),
                            modifier = Modifier.testTag("places-task-${job.id}"),
                        )
                        Text(
                            "${Formatter.formatShortFileSize(context, job.copied)} / " +
                                Formatter.formatShortFileSize(context, job.total)
                        )
                        if (job.total > 0)
                            GalleryProgressIndicator(
                                progress = {
                                    (job.copied.toDouble() / job.total).toFloat().coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.status == OfflineMapTaskStatus.ReadyForReview)
                                TextButton(
                                    onClick = {
                                        action { review = job to controller.review(job.id) }
                                    },
                                    modifier = Modifier.testTag("places-review-${job.id}"),
                                ) {
                                    Text(stringResource(R.string.places_review))
                                }
                            if (
                                job.status in
                                    setOf(
                                        OfflineMapTaskStatus.Queued,
                                        OfflineMapTaskStatus.Copying,
                                        OfflineMapTaskStatus.Downloading,
                                        OfflineMapTaskStatus.Verifying,
                                    )
                            )
                                TextButton(onClick = { controller.pause(job.id) }) {
                                    Text(stringResource(R.string.places_pause))
                                }
                            if (
                                job.status !in
                                    setOf(
                                        OfflineMapTaskStatus.Installed,
                                        OfflineMapTaskStatus.Cancelled,
                                        OfflineMapTaskStatus.ReadyForReview,
                                        OfflineMapTaskStatus.Copying,
                                        OfflineMapTaskStatus.Downloading,
                                        OfflineMapTaskStatus.Verifying,
                                        OfflineMapTaskStatus.Queued,
                                        OfflineMapTaskStatus.WaitingPermission,
                                    )
                            )
                                TextButton(onClick = { controller.resume(job.id) }) {
                                    Text(stringResource(R.string.places_resume))
                                }
                            if (job.status == OfflineMapTaskStatus.WaitingPermission)
                                TextButton(
                                    onClick = {
                                        regranting = job.id
                                        picker.launch(arrayOf("*/*"))
                                    }
                                ) {
                                    Text(stringResource(R.string.places_regrant))
                                }
                            if (
                                job.status !in
                                    setOf(
                                        OfflineMapTaskStatus.Installed,
                                        OfflineMapTaskStatus.Cancelled,
                                    )
                            )
                                TextButton(
                                    onClick = { controller.cancel(job.id) },
                                    modifier = Modifier.testTag("places-cancel-${job.id}"),
                                ) {
                                    Text(stringResource(R.string.places_cancel))
                                }
                        }
                    }
                }
            }
        }
        }
        }
    }
    if (remove != null)
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(stringResource(R.string.places_remove)) },
            text = { Text(stringResource(R.string.places_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = requireNotNull(remove).id
                        remove = null
                        action { controller.removePackage(id) }
                    }
                ) {
                    Text(stringResource(R.string.places_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { remove = null }) {
                    Text(stringResource(R.string.places_back))
                }
            },
        )
    if (review != null) {
        val (job, info) = requireNotNull(review)
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text(stringResource(R.string.places_review)) },
            text = {
                Column {
                    Text(job.name)
                    Text(
                        stringResource(
                            R.string.places_details,
                            info.format.name,
                            job.total,
                            info.minZoom,
                            info.maxZoom,
                        )
                    )
                    Text(
                        stringResource(
                            R.string.places_bounds,
                            info.bounds.west,
                            info.bounds.south,
                            info.bounds.east,
                            info.bounds.north,
                        )
                    )
                    Text(
                        remember(info.attribution) {
                            android.text.Html.fromHtml(
                                    info.attribution,
                                    android.text.Html.FROM_HTML_MODE_LEGACY,
                                )
                                .toString()
                                .trim()
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.confirmImport(job.id)
                        review = null
                    },
                    modifier = Modifier.testTag("places-install"),
                ) {
                    Text(stringResource(R.string.places_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { review = null }) {
                    Text(stringResource(R.string.places_back))
                }
            },
        )
    }
    if (worldDialog)
        AlertDialog(
            onDismissRequest = { worldDialog = false },
            title = { Text(stringResource(R.string.places_world)) },
            text = {
                Column {
                    Text(stringResource(R.string.places_world_body))
                    Row(
                        Modifier.toggleable(
                            value = worldConfirmed,
                            role = Role.Checkbox,
                            onValueChange = { worldConfirmed = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = worldConfirmed,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("places-world-consent"),
                        )
                        Text(stringResource(R.string.places_world_confirm))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = worldConfirmed,
                    onClick = {
                        worldDialog = false
                        action { controller.downloadWorld(true) }
                    },
                    modifier = Modifier.testTag("places-world-download"),
                ) {
                    Text(stringResource(R.string.places_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { worldDialog = false }) {
                    Text(stringResource(R.string.places_back))
                }
            },
        )
}

internal fun statusResource(status: OfflineMapTaskStatus): Int =
    when (status) {
        OfflineMapTaskStatus.Queued -> R.string.places_status_queued
        OfflineMapTaskStatus.Copying -> R.string.places_status_copying
        OfflineMapTaskStatus.Downloading -> R.string.places_status_downloading
        OfflineMapTaskStatus.Verifying -> R.string.places_status_verifying
        OfflineMapTaskStatus.ReadyForReview -> R.string.places_status_readyforreview
        OfflineMapTaskStatus.Installed -> R.string.places_status_installed
        OfflineMapTaskStatus.Paused -> R.string.places_status_paused
        OfflineMapTaskStatus.WaitingWifi -> R.string.places_status_waitingwifi
        OfflineMapTaskStatus.WaitingCharging -> R.string.places_status_waitingcharging
        OfflineMapTaskStatus.WaitingStorage -> R.string.places_status_waitingstorage
        OfflineMapTaskStatus.WaitingHardware -> R.string.places_status_waitinghardware
        OfflineMapTaskStatus.WaitingPermission -> R.string.places_status_waitingpermission
        OfflineMapTaskStatus.Failed -> R.string.places_status_failed
        OfflineMapTaskStatus.Cancelled -> R.string.places_status_cancelled
    }
