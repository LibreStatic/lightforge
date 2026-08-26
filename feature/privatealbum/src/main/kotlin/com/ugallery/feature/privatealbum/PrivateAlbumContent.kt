package com.ugallery.feature.privatealbum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import com.ugallery.core.security.PrivateAlbumCrypto
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.videoDurationDescription
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateAlbumContent(
    repository: PrivateAlbumRepository,
    onBack: () -> Unit,
    onUnlockRequest: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onExport: (mediaId: Long, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    isUnlocked: Boolean,
    onUnlocked: () -> Unit,
    onAddRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val exportedMessage = stringResource(R.string.private_exported)
    val genericErrorMessage = stringResource(R.string.private_error)
    val mediaList by repository.allMedia.collectAsState(initial = emptyList())
    var showError by remember { mutableStateOf<String?>(null) }
    var showSetupWarning by remember { mutableStateOf(false) }
    var setupComplete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val setup = repository.isSetup()
        setupComplete = setup
        if (!setup) showSetupWarning = true
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            GalleryTopAppBar(
                title = if (isUnlocked) stringResource(R.string.private_title_count, mediaList.size)
                    else stringResource(R.string.private_locked),
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.private_back),
            )
        },
    ) { padding ->
        if (!isUnlocked) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(GalleryIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.private_locked), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.private_unlock_body), style = MaterialTheme.typography.bodyMedium)
                    GalleryExpressiveButton(
                        onClick = {
                            onUnlockRequest(
                                onUnlocked,
                                { showError = it },
                            )
                        },
                        enabled = setupComplete,
                        modifier = Modifier.padding(top = 16.dp),
                    ) { Text(stringResource(R.string.private_unlock)) }
                }
            }
        } else if (mediaList.isEmpty()) {
            PrivateAlbumEmptyState(
                onAddRequest = onAddRequest,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(mediaList, key = { it.id }) { item ->
                    val isVideo = item.mediaKind == "video"
                    val typeDescription = if (isVideo) {
                        videoDurationDescription(item.durationMillis)
                    } else {
                        stringResource(R.string.private_photo)
                    }
                    Card(
                        modifier = Modifier.padding(2.dp).semantics {
                            contentDescription = "${item.originalDisplayName}, $typeDescription"
                        },
                        onClick = {
                            onExport(
                                item.id,
                                { showError = exportedMessage },
                                { showError = it },
                            )
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.35f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Icon(
                                imageVector = if (isVideo) GalleryIcons.Video else GalleryIcons.Image,
                                contentDescription = null,
                                modifier = Modifier.align(Alignment.Center).size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isVideo) {
                                VideoDurationBadge(
                                    durationMillis = item.durationMillis,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                item.originalDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(if (isVideo) R.string.private_video else R.string.private_photo),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSetupWarning) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.private_setup_title)) },
            text = {
                Text(stringResource(R.string.private_setup_body))
            },
            confirmButton = {
                GalleryExpressiveButton(onClick = {
                    showSetupWarning = false
                    scope.launch {
                        runCatching { repository.setup(PrivateAlbumCrypto.getOrCreateMasterKey()) }
                            .onSuccess { setupComplete = true }
                            .onFailure { showError = it.message ?: genericErrorMessage }
                    }
                }) { Text(stringResource(R.string.private_understand)) }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.private_cancel)) }
            },
        )
    }

    showError?.let { error ->
        AlertDialog(
            onDismissRequest = { showError = null },
            title = { Text(stringResource(R.string.private_error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { showError = null }) { Text(stringResource(R.string.private_ok)) }
            },
        )
    }
}

@Composable
internal fun PrivateAlbumEmptyState(
    onAddRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GalleryStateContent(
        title = stringResource(R.string.private_empty_title),
        body = stringResource(R.string.private_empty_body),
        illustrationDescription = stringResource(R.string.private_empty_illustration),
        modifier = modifier,
        illustration = {
            Icon(
                GalleryIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        },
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GalleryExpressiveButton(onClick = onAddRequest) {
                    Icon(GalleryIcons.Plus, contentDescription = null)
                    Text(
                        stringResource(R.string.private_choose_media),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    stringResource(R.string.private_originals_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
    )
}
