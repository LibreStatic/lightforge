package com.ugallery.feature.privatealbum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.videoDurationDescription
import com.ugallery.core.security.PrivateAlbumCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class PrivateIndexUiState {
    Loading,
    Ready,
    Unavailable,
    AuthenticationRequired,
}

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
    onPortableRequest: ((Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var mediaList by remember(repository) { mutableStateOf(emptyList<PrivateMediaEntity>()) }
    var indexState by remember(repository) { mutableStateOf(PrivateIndexUiState.Loading) }
    var retryIndex by remember(repository) { mutableStateOf(0) }
    var showError by remember { mutableStateOf<String?>(null) }
    var showSetupWarning by remember { mutableStateOf(false) }
    var showPortable by remember { mutableStateOf(false) }
    var viewerMediaId by remember(repository) { mutableStateOf<Long?>(null) }
    var showExportRecovery by remember(repository) { mutableStateOf(false) }
    var exportRecoveryFailed by remember(repository) { mutableStateOf(false) }
    var setupComplete by remember { mutableStateOf(false) }
    var showKeyProtection by remember { mutableStateOf(false) }
    var keyProtectionStatus by remember { mutableStateOf(PrivateKeyProtectionStatus.Legacy) }
    var keyProtectionPhase by remember { mutableStateOf(PrivateKeyProtectionPhase.Confirmation) }
    var protectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) {
            protectionJob?.cancel()
            if (keyProtectionPhase == PrivateKeyProtectionPhase.Working)
                keyProtectionPhase = PrivateKeyProtectionPhase.AuthenticationRequired
        }
    }

    fun protectKeys() {
        if (protectionJob?.isActive == true) return
        showKeyProtection = true
        keyProtectionPhase = PrivateKeyProtectionPhase.Working
        protectionJob = scope.launch {
            try {
                repository.keyProtection().begin()
                keyProtectionPhase = PrivateKeyProtectionPhase.AuthenticationRequired
                onUnlockRequest({
                    keyProtectionPhase = PrivateKeyProtectionPhase.Working
                    protectionJob = scope.launch {
                        try {
                            repository.keyProtection().advance()
                            keyProtectionStatus = PrivateKeyProtectionStatus.Protected
                            keyProtectionPhase = PrivateKeyProtectionPhase.Complete
                            setupComplete = true
                            retryIndex++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            keyProtectionPhase = if (PrivateAlbumCrypto.requiresAuthentication(failure))
                                PrivateKeyProtectionPhase.AuthenticationRequired else PrivateKeyProtectionPhase.Failed
                        }
                    }
                }, { keyProtectionPhase = PrivateKeyProtectionPhase.Failed })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                keyProtectionPhase = if (failure is PrivateDeviceCredentialRequiredException)
                    PrivateKeyProtectionPhase.CredentialsRequired else PrivateKeyProtectionPhase.Failed
            }
        }
    }

    LaunchedEffect(isUnlocked) { if (!isUnlocked) showError = null }

    fun indexUnavailable() {
        indexState = PrivateIndexUiState.Unavailable
        mediaList = emptyList()
        showPortable = false
        showSetupWarning = false
        setupComplete = false
        showError = null
    }

    val accessState by repository.accessState.collectAsState()
    LaunchedEffect(repository, retryIndex, isUnlocked, accessState) {
        if (repository.isSessionBacked && (accessState != PrivateIndexAccessState.Ready || !isUnlocked)) {
            mediaList = emptyList()
            showSetupWarning = false
            setupComplete = false
            if (accessState == PrivateIndexAccessState.Unavailable) indexUnavailable()
            else indexState = PrivateIndexUiState.AuthenticationRequired
            return@LaunchedEffect
        }
        indexState = PrivateIndexUiState.Loading
        try {
            val setup = repository.isSetup()
            setupComplete = setup
            keyProtectionStatus = repository.keyProtection().status()
            if (setup) repository.requireMasterKey()
            repository.allMedia.collect { items ->
                mediaList = items
                if (indexState == PrivateIndexUiState.Loading) {
                    setupComplete = setup
                    showSetupWarning = !setup
                    indexState = PrivateIndexUiState.Ready
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (PrivateAlbumCrypto.requiresAuthentication(failure)) {
                mediaList = emptyList()
                indexState = PrivateIndexUiState.AuthenticationRequired
            } else {
            // A missing key/corrupt index is not an empty vault. Never expose exception details,
            // offer setup/reset, or run portable recovery against an unavailable index.
            indexUnavailable()
            }
        }
    }

    val recoveryAvailable = isUnlocked && indexState == PrivateIndexUiState.Ready &&
        (!repository.isSessionBacked || accessState == PrivateIndexAccessState.Ready)
    val recoveryAvailableNow by rememberUpdatedState(recoveryAvailable)
    LaunchedEffect(recoveryAvailable) {
        if (!recoveryAvailable) { viewerMediaId = null; showExportRecovery = false; exportRecoveryFailed = false }
    }
    val selectedViewerId = viewerMediaId
    if (selectedViewerId != null && recoveryAvailable) {
        PrivateMediaViewerContent(
            repository = repository,
            mediaId = selectedViewerId,
            onBack = { viewerMediaId = null },
            onAuthenticationRequired = {
                viewerMediaId = null
                repository.revokeSession()
                mediaList = emptyList()
                indexState = PrivateIndexUiState.AuthenticationRequired
                onUnlockRequest({ onUnlocked(); retryIndex++ }, { showError = it })
            },
            onExport = {
                viewerMediaId = null
                onExport(selectedViewerId, {
                    if (recoveryAvailableNow) {
                        showError = null; exportRecoveryFailed = false; showExportRecovery = true
                    }
                }, {
                    if (recoveryAvailableNow) {
                        showError = null; exportRecoveryFailed = true; showExportRecovery = true
                    }
                })
            },
        )
        return
    }
    if (showExportRecovery && recoveryAvailable) {
        PrivateExportRecoveryContent(
            repository = repository,
            isUnlocked = recoveryAvailable,
            initialExportFailed = exportRecoveryFailed,
            onBack = { showExportRecovery = false },
            onAuthenticationRequired = {
                showExportRecovery = false
                mediaList = emptyList()
                indexState = PrivateIndexUiState.AuthenticationRequired
            },
        )
        return
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            Column {
            GalleryTopAppBar(
                title =
                    if (indexState == PrivateIndexUiState.Unavailable)
                        stringResource(R.string.private_index_unavailable_title)
                    else if (indexState == PrivateIndexUiState.Ready && isUnlocked)
                        stringResource(R.string.private_title_count, mediaList.size)
                    else stringResource(R.string.private_locked),
                onBack = onBack,
                navigationContentDescription = stringResource(R.string.private_back),
                actions = {
                    if (isUnlocked && indexState == PrivateIndexUiState.Ready && setupComplete && keyProtectionStatus != PrivateKeyProtectionStatus.Protected) {
                        IconButton(onClick = {
                            showKeyProtection = true
                            keyProtectionPhase = PrivateKeyProtectionPhase.Confirmation
                        }, modifier = Modifier.testTag("private-key-protection-open")) {
                            Icon(GalleryIcons.Lock, contentDescription = stringResource(R.string.private_key_protection_action))
                        }
                    }
                    if (isUnlocked && indexState == PrivateIndexUiState.Ready)
                        TextButton(
                            onClick = {
                                if (onPortableRequest != null)
                                    onPortableRequest(mediaList.isNotEmpty())
                                else showPortable = true
                            },
                            modifier = Modifier.then(Modifier.testTag("private-portable-open")),
                        ) {
                            Text(stringResource(R.string.private_portable_title))
                        }
                },
            )
            if (recoveryAvailable) {
                TextButton(
                    onClick = { showPortable = false; showError = null; exportRecoveryFailed = false; showExportRecovery = true },
                    modifier = Modifier.fillMaxWidth().testTag("private-export-recovery-open"),
                ) { Text(stringResource(R.string.private_export_recovery_title)) }
            }
            }
        },
    ) { padding ->
        if (indexState == PrivateIndexUiState.Unavailable) {
            GalleryStateContent(
                title = stringResource(R.string.private_index_unavailable_title),
                body = stringResource(R.string.private_index_unavailable_body),
                illustrationDescription = stringResource(R.string.private_index_unavailable_title),
                modifier =
                    Modifier.fillMaxSize().padding(padding).testTag("private-index-unavailable"),
                illustration = { Icon(GalleryIcons.Lock, contentDescription = null) },
                action = {
                    GalleryExpressiveButton(
                        onClick = {
                            if (repository.isSessionBacked) onUnlockRequest({ retryIndex++ }, { })
                            else { indexState = PrivateIndexUiState.Loading; retryIndex++ }
                        },
                        modifier = Modifier.testTag("private-index-retry"),
                    ) {
                        Text(stringResource(R.string.private_index_retry))
                    }
                },
            )
        } else if (indexState == PrivateIndexUiState.Loading) {
            Box(
                Modifier.fillMaxSize().padding(padding).testTag("private-index-loading"),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.private_index_loading))
            }
        } else if (!isUnlocked || indexState == PrivateIndexUiState.AuthenticationRequired) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        GalleryIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.private_locked),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.private_key_unlock_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    GalleryExpressiveButton(
                        onClick = { onUnlockRequest({ onUnlocked(); retryIndex++ }, { showError = it }) },
                        enabled = setupComplete || repository.isSessionBacked,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(stringResource(R.string.private_unlock))
                    }
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
                    val typeDescription =
                        if (isVideo) {
                            videoDurationDescription(item.durationMillis)
                        } else {
                            stringResource(R.string.private_photo)
                        }
                    Card(
                        modifier =
                            Modifier.padding(2.dp).semantics {
                                contentDescription = "${item.originalDisplayName}, $typeDescription"
                            },
                        onClick = { if (recoveryAvailableNow) viewerMediaId = item.id },
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .aspectRatio(1.35f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector =
                                    if (isVideo) GalleryIcons.Video else GalleryIcons.Image,
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
                                stringResource(
                                    if (isVideo) R.string.private_video else R.string.private_photo
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPortable && indexState == PrivateIndexUiState.Ready)
        PrivatePortableContent(
            repository,
            mediaList.isNotEmpty(),
            onClose = { showPortable = false },
            isUnlocked = isUnlocked,
        )

    if ((showSetupWarning || (showKeyProtection && isUnlocked)) && indexState != PrivateIndexUiState.Unavailable && (!repository.isSessionBacked || (isUnlocked && accessState == PrivateIndexAccessState.Ready))) {
        PrivateKeyProtectionContent(
            phase = keyProtectionPhase,
            onConfirm = ::protectKeys,
            onDismiss = {
                if (keyProtectionPhase == PrivateKeyProtectionPhase.Complete) {
                    showSetupWarning = false
                    showKeyProtection = false
                    retryIndex++
                } else {
                    protectionJob = scope.launch {
                        try {
                            repository.keyProtection().cancel()
                            showKeyProtection = false
                            if (!setupComplete) onBack()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { keyProtectionPhase = PrivateKeyProtectionPhase.Failed }
                    }
                }
            },
        )
    }

    showError?.let { error ->
        val errorContext = LocalContext.current
        // Without a screen lock the vault has no gate to offer, so the only useful next step
        // is sending the user to the system security settings to create one.
        val authUnavailable = error == stringResource(R.string.private_key_auth_unavailable)
        AlertDialog(
            onDismissRequest = { showError = null },
            title = { Text(stringResource(R.string.private_error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { showError = null }) {
                    Text(stringResource(R.string.private_ok))
                }
            },
            dismissButton = if (authUnavailable) ({
                TextButton(
                    onClick = {
                        showError = null
                        val security = android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            errorContext.startActivity(security)
                        } catch (_: android.content.ActivityNotFoundException) {
                            runCatching {
                                errorContext.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    modifier = Modifier.testTag("private-open-security-settings"),
                ) {
                    Text(stringResource(R.string.private_open_security_settings))
                }
            }) else null,
        )
    }
}

@Composable
internal fun PrivateAlbumEmptyState(onAddRequest: () -> Unit, modifier: Modifier = Modifier) {
    GalleryStateContent(
        title = stringResource(R.string.private_empty_title),
        body = stringResource(R.string.private_empty_body),
        illustrationDescription = stringResource(R.string.private_empty_illustration),
        modifier = modifier,
        illustration = {
            Icon(GalleryIcons.Lock, contentDescription = null, modifier = Modifier.size(36.dp))
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
