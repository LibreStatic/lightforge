package com.librestatic.lightforge.feature.videoeditor

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.editing.video.MemoryVideoExporter
import com.librestatic.lightforge.core.editing.video.MemoryVideoPhase
import com.librestatic.lightforge.core.editing.video.MemoryVideoProgress
import com.librestatic.lightforge.core.editing.video.MemoryVideoRequest
import com.librestatic.lightforge.core.editing.video.MemoryVideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

/**
 * A local, non-destructive video draft. The caller supplies a visibility-checked, ordered snapshot.
 */
@UnstableApi
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MemoryVideoContent(
    title: String,
    sources: List<MemoryVideoSource>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = sources.map { "${it.uri}@${it.expectedGeneration}@${it.expectedGenerationAdded}" }
    var order by
        rememberSaveable(identity, stateSaver = listSaver(save = { it }, restore = { it })) {
            mutableStateOf(sources.indices.toList())
        }
    var savedIdentity by
        rememberSaveable(stateSaver = listSaver(save = { it }, restore = { it })) {
            mutableStateOf(identity)
        }
    var seconds by rememberSaveable { mutableIntStateOf(3) }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var music by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf(false) }
    var cancelled by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<MemoryVideoProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val busy = progress != null
    val sameSources = savedIdentity == identity
    val valid = sameSources && order.size in 1..120 && order.toSet() == sources.indices.toSet()
    val index = current.coerceIn(0, (order.size - 1).coerceAtLeast(0))
    val selected = if (sameSources) order.getOrNull(index)?.let(sources::getOrNull) else null
    LaunchedEffect(identity) {
        if (!sameSources) {
            playing = false
            job?.cancelAndJoin()
            order = sources.indices.toList()
            current = 0
            music = null
            result = null
            savedIdentity = identity
        }
    }
    fun back() {
        if (busy) confirmExit = true else onBack()
    }
    BackHandler { back() }
    val audioPicker =
        rememberLauncherForActivityResult(MemoryVideoLocalAudioContract()) { uri ->
            if (uri != null) {
                // Temporary grants suffice for this draft; persist if the provider supports it.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                music = uri.toString()
                playing = false
            }
        }
    LaunchedEffect(playing, seconds, order) {
        while (playing && order.isNotEmpty()) {
            delay(seconds * 1_000L)
            current = (current + 1) % order.size
        }
    }
    val previewPlayer = remember { arrayOfNulls<MediaPlayer>(1) }
    val previewPosition by rememberUpdatedState(index * seconds * 1_000L)
    LaunchedEffect(index, seconds, playing) {
        if (playing)
            runCatching {
                previewPlayer[0]?.let { player ->
                    if (player.duration > 0)
                        player.seekTo((previewPosition % player.duration).toInt())
                }
            }
    }
    DisposableEffect(playing, music) {
        val player = if (playing && music != null) MediaPlayer() else null
        if (player != null) {
            try {
                player.setDataSource(context, Uri.parse(music))
                player.isLooping = true
                player.setOnPreparedListener {
                    if (it.duration > 0) it.seekTo((previewPosition % it.duration).toInt())
                    it.start()
                }
                player.setOnErrorListener { _, _, _ ->
                    playing = false
                    failure = true
                    true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                playing = false
                failure = true
            }
        }
        previewPlayer[0] = player
        onDispose {
            previewPlayer[0] = null
            player?.release()
        }
    }
    Surface(
        modifier.fillMaxSize().testTag("memory-video-screen").semantics {
            testTagsAsResourceId = true
        },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                GalleryTopAppBar(
                    title = stringResource(R.string.memory_video_title),
                    onBack = ::back,
                    navigationContentDescription = stringResource(R.string.memory_video_back),
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        MemoryVideoImage(selected, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    }
                    Text(
                        stringResource(
                            R.string.memory_video_position,
                            if (order.isEmpty()) 0 else index + 1,
                            order.size,
                        ),
                        Modifier.testTag("memory-video-position"),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                playing = false
                                current = (index - 1).coerceAtLeast(0)
                            },
                            enabled = !busy && index > 0,
                            modifier = Modifier.testTag("memory-video-previous"),
                        ) {
                            Text(stringResource(R.string.memory_video_previous))
                        }
                        Button(
                            onClick = { playing = !playing },
                            enabled = !busy && valid,
                            modifier = Modifier.testTag("memory-video-play"),
                        ) {
                            Text(
                                stringResource(
                                    if (playing) R.string.memory_video_pause
                                    else R.string.memory_video_play
                                )
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                playing = false
                                current = (index + 1).coerceAtMost(order.lastIndex)
                            },
                            enabled = !busy && index < order.lastIndex,
                            modifier = Modifier.testTag("memory-video-next"),
                        ) {
                            Text(stringResource(R.string.memory_video_next))
                        }
                    }
                    Text(
                        stringResource(R.string.memory_video_order),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                playing = false
                                order =
                                    order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                current = index - 1
                            },
                            enabled = !busy && index > 0,
                            modifier = Modifier.testTag("memory-video-earlier"),
                        ) {
                            Text(stringResource(R.string.memory_video_earlier))
                        }
                        OutlinedButton(
                            onClick = {
                                playing = false
                                order =
                                    order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                current = index + 1
                            },
                            enabled = !busy && index < order.lastIndex,
                            modifier = Modifier.testTag("memory-video-later"),
                        ) {
                            Text(stringResource(R.string.memory_video_later))
                        }
                        TextButton(
                            onClick = {
                                playing = false
                                order = sources.indices.toList()
                                current = 0
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.memory_video_reset_order))
                        }
                    }
                    Text(
                        stringResource(R.string.memory_video_duration),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoryVideoRequest.SupportedSecondsPerPhoto.forEach { value ->
                            FilterChip(
                                colors = editorFilterChipColors(),
                                selected = seconds == value,
                                onClick = { seconds = value },
                                enabled = !busy,
                                label = {
                                    Text(stringResource(R.string.memory_video_seconds, value))
                                },
                                modifier = Modifier.testTag("memory-video-seconds-$value"),
                            )
                        }
                    }
                    Text(stringResource(R.string.memory_video_summary, order.size * seconds))
                    Text(
                        stringResource(R.string.memory_video_music),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            if (music == null) R.string.memory_video_no_music
                            else R.string.memory_video_music_selected
                        )
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                playing = false
                                audioPicker.launch(arrayOf("audio/*"))
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("memory-video-pick-music"),
                        ) {
                            Text(stringResource(R.string.memory_video_choose_music))
                        }
                        if (music != null)
                            TextButton(
                                onClick = {
                                    music = null
                                    playing = false
                                },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.memory_video_remove_music))
                            }
                    }
                    Text(
                        stringResource(R.string.memory_video_local_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!valid) Text(stringResource(R.string.memory_video_limit))
                    if (failure)
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                stringResource(R.string.memory_video_failure),
                                Modifier.padding(12.dp).testTag("memory-video-error"),
                            )
                        }
                    if (cancelled)
                        Text(
                            stringResource(R.string.memory_video_cancelled),
                            Modifier.testTag("memory-video-cancelled"),
                        )
                    progress?.let { state ->
                        Text(
                            stringResource(
                                when (state.phase) {
                                    MemoryVideoPhase.Preparing -> R.string.memory_video_preparing
                                    MemoryVideoPhase.Encoding -> R.string.memory_video_encoding
                                    MemoryVideoPhase.Publishing -> R.string.memory_video_publishing
                                }
                            ),
                            Modifier.testTag("memory-video-progress"),
                        )
                        state.percent?.let {
                            GalleryProgressIndicator(
                                progress = { it / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } ?: GalleryIndeterminateProgressIndicator(modifier = Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = { job?.cancel() },
                            Modifier.testTag("memory-video-cancel"),
                        ) {
                            Text(stringResource(R.string.memory_video_cancel))
                        }
                    }
                    if (!busy)
                        Button(
                            onClick = {
                                playing = false
                                failure = false
                                cancelled = false
                                result = null
                                progress = MemoryVideoProgress(MemoryVideoPhase.Preparing, 0)
                                val request =
                                    MemoryVideoRequest(
                                        order.map { sources[it] },
                                        seconds,
                                        music?.let(Uri::parse),
                                    )
                                job =
                                    scope.launch {
                                        try {
                                            val published =
                                                MemoryVideoExporter(context.applicationContext)
                                                    .export(request) { update ->
                                                        scope.launch {
                                                            if (job?.isActive == true)
                                                                progress = update
                                                        }
                                                    }
                                            result = published.toString()
                                        } catch (_: TimeoutCancellationException) {
                                            failure = true
                                        } catch (cancel: CancellationException) {
                                            cancelled = true
                                            throw cancel
                                        } catch (_: Exception) {
                                            failure = true
                                        } finally {
                                            progress = null
                                            job = null
                                        }
                                    }
                            },
                            enabled = valid,
                            modifier = Modifier.fillMaxWidth().testTag("memory-video-export"),
                        ) {
                            Text(stringResource(R.string.memory_video_export))
                        }
                    result
                        ?.takeIf { sameSources }
                        ?.let { value ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.memory_video_saved),
                                        Modifier.testTag("memory-video-saved"),
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            colors =
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = LocalContentColor.current
                                                ),
                                            onClick = {
                                                runCatching {
                                                        context.startActivity(
                                                            Intent(Intent.ACTION_VIEW)
                                                                .setDataAndType(
                                                                    Uri.parse(value),
                                                                    "video/mp4",
                                                                )
                                                                .addFlags(
                                                                    Intent
                                                                        .FLAG_GRANT_READ_URI_PERMISSION
                                                                )
                                                        )
                                                    }
                                                    .onFailure { failure = true }
                                            },
                                        ) {
                                            Text(stringResource(R.string.memory_video_open))
                                        }
                                        TextButton(
                                            colors =
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = LocalContentColor.current
                                                ),
                                            onClick = {
                                                runCatching {
                                                        context.startActivity(
                                                            Intent.createChooser(
                                                                Intent(Intent.ACTION_SEND)
                                                                    .setType("video/mp4")
                                                                    .putExtra(
                                                                        Intent.EXTRA_STREAM,
                                                                        Uri.parse(value),
                                                                    )
                                                                    .addFlags(
                                                                        Intent
                                                                            .FLAG_GRANT_READ_URI_PERMISSION
                                                                    ),
                                                                null,
                                                            )
                                                        )
                                                    }
                                                    .onFailure { failure = true }
                                            },
                                        ) {
                                            Text(stringResource(R.string.memory_video_share))
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
    if (confirmExit)
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.memory_video_cancel_title)) },
            text = { Text(stringResource(R.string.memory_video_cancel_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        job?.cancel()
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.memory_video_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text(stringResource(R.string.memory_video_continue))
                }
            },
        )
}

@Composable
private fun MemoryVideoImage(source: MemoryVideoSource?, modifier: Modifier) {
    val resolver = LocalContext.current.contentResolver
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(source) {
        bitmap =
            withContext(Dispatchers.IO) {
                source?.let {
                    runCatching {
                            ImageDecoder.decodeBitmap(
                                ImageDecoder.createSource(resolver, it.uri)
                            ) { decoder, info, _ ->
                                val scale =
                                    minOf(960f / info.size.width, 540f / info.size.height, 1f)
                                decoder.setTargetSize(
                                    (info.size.width * scale).toInt().coerceAtLeast(1),
                                    (info.size.height * scale).toInt().coerceAtLeast(1),
                                )
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        }
                        .getOrNull()
                }
            }
    }
    // Bitmap memory is bounded to the displayed preview; Compose may retain a submitted bitmap for
    // a frame.
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                stringResource(R.string.memory_video_preview),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(stringResource(R.string.memory_video_unavailable), Modifier.padding(16.dp))
    }
}

/** Keeps the document picker on providers advertising local content. */
internal class MemoryVideoLocalAudioContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
