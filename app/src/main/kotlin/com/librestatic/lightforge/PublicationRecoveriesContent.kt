package com.librestatic.lightforge

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PublicationRecoveriesContent(onBack: () -> Unit, modifier: Modifier = Modifier,
    onOpen: ((PublicationRecoveryTarget) -> Unit)? = null,
    onShare: ((PublicationRecoveryTarget) -> Unit)? = null,
    onTrackingRemoved: (family: String, id: String, sourceIdentity: String?) -> Unit = { _, _, _ -> }) {
    val context = LocalContext.current
    val resultLabel by rememberUpdatedState(stringResource(R.string.publication_recoveries_result))
    val scope = rememberCoroutineScope()
    val latestTracking by rememberUpdatedState(onTrackingRemoved)
    val model = remember { PublicationRecoveriesViewModel(context.applicationContext, scope) { family, id, source -> latestTracking(family, id, source) } }
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    DisposableEffect(model) { onDispose { model.dispose() } }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    // Loading a bitmap never changes busy; this effect cannot restart itself through a handoff guard.
    LaunchedEffect(state.selectedKey, state.revision, state.proof, state.busy) {
        preview = null
        val proof = state.proof
        if (!state.busy && proof != null) preview = model.loadPreview(proof)
    }
    DisposableEffect(preview) { val owned = preview; onDispose { owned?.recycle() } }
    fun handoff(share: Boolean) {
        model.handoff { target ->
            val callback = if (share) onShare else onOpen
            if (callback != null) callback(target)
            else {
                val intent = if (share) Intent(Intent.ACTION_SEND).setType(target.mimeType).putExtra(Intent.EXTRA_STREAM, target.uri)
                    else Intent(Intent.ACTION_VIEW).setDataAndType(target.uri, target.mimeType)
                intent.clipData = ClipData.newRawUri(resultLabel, target.uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, null).apply {
                    clipData = intent.clipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
    }
    BackHandler { if (!state.busy) onBack() }
    Surface(modifier.fillMaxSize().testTag("publication-recoveries-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(title = stringResource(R.string.publication_recoveries_title),
                onBack = { if (!state.busy) onBack() }, navigationContentDescription = stringResource(R.string.publication_recoveries_back))
            LazyColumn(Modifier.fillMaxSize().testTag("publication-recoveries-list"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("toolbar") {
                    OutlinedButton(onClick = model::refresh, enabled = !state.busy, modifier = Modifier.testTag("publication-recoveries-refresh")) {
                        Text(stringResource(R.string.publication_recoveries_refresh))
                    }
                }
                if (state.busy || !state.initialized) item("busy") {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("publication-recoveries-busy"))
                        Text(stringResource(R.string.publication_recoveries_loading))
                    }
                }
                state.message?.let { message -> item("message") {
                    val success = message == PublicationRecoveryMessage.Completed
                    Surface(color = if (success) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (success) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer) {
                        Text(stringResource(when (message) {
                            PublicationRecoveryMessage.LoadError -> R.string.publication_recoveries_error
                            PublicationRecoveryMessage.Changed -> R.string.publication_recoveries_changed
                            PublicationRecoveryMessage.Failed -> R.string.publication_recoveries_failed
                            PublicationRecoveryMessage.Completed -> R.string.publication_recoveries_completed
                        }), Modifier.padding(12.dp).testTag("publication-recoveries-message"))
                    }
                } }
                if (state.initialized && !state.busy && state.entries.isEmpty() && state.message == null) item("empty") {
                    Text(stringResource(R.string.publication_recoveries_empty), Modifier.testTag("publication-recoveries-empty"))
                }
                items(state.entries, key = { it.key }) { entry ->
                    val selected = entry.key == state.selectedKey
                    Surface(Modifier.fillMaxWidth().selectable(selected, enabled = !state.busy, role = Role.RadioButton,
                        onClick = { model.select(entry.key) }).testTag("publication-recovery-row-${entry.family}-${entry.id}"),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(recoveryKindLabel(entry.kind)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.publication_recoveries_id, entry.id))
                            Text(stringResource(recoveryPhaseLabel(if (entry.unreadable) "Unreadable" else entry.phase)))
                            Text(entry.name ?: stringResource(R.string.publication_recoveries_no_destination))
                        }
                    }
                if (selected) state.proof?.takeIf { it.entry.key == entry.key }?.let { proof ->
                    val permissions = proof.permissions
                    Column(Modifier.fillMaxWidth().testTag("publication-recoveries-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.publication_recoveries_review), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.publication_recoveries_id, proof.entry.id))
                        Text(proof.destinationName ?: stringResource(R.string.publication_recoveries_no_destination))
                        Text(stringResource(recoveryPhaseLabel(when {
                            permissions.unreadable -> "Unreadable"
                            permissions.busy -> "Busy"
                            permissions.backingFileMissing -> "MissingBacking"
                            else -> proof.status ?: proof.entry.phase
                        })))
                        proof.pendingHash?.let { Text(stringResource(R.string.publication_recoveries_hash, it)) }
                        proof.pendingSize?.let { Text(stringResource(R.string.publication_recoveries_bytes, it)) }
                        preview?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), stringResource(R.string.publication_recoveries_preview),
                                Modifier.fillMaxWidth().heightIn(max = 300.dp).testTag("publication-recoveries-preview"), contentScale = ContentScale.Fit)
                        }
                        if (publicationRecoveryAllowsHandoff(permissions)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { handoff(false) }, enabled = !state.busy, modifier = Modifier.testTag("publication-recoveries-open")) { Text(stringResource(R.string.publication_recoveries_open)) }
                            OutlinedButton(onClick = { handoff(true) }, enabled = !state.busy, modifier = Modifier.testTag("publication-recoveries-share")) { Text(stringResource(R.string.publication_recoveries_share)) }
                        }
                        PublicationRecoveryAction.entries.forEach { action ->
                            if (publicationRecoveryAllows(action, permissions)) OutlinedButton(onClick = { model.review(action) }, enabled = !state.busy,
                                modifier = Modifier.testTag("publication-recoveries-${recoveryActionTag(action)}")) { Text(stringResource(recoveryActionLabel(action))) }
                        }
                    }
                }
                }
            }
        }
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(onDismissRequest = model::dismiss, modifier = Modifier.testTag("publication-recoveries-confirmation"),
            title = { Text(stringResource(recoveryActionLabel(confirmation.action))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when (confirmation.action) {
                        PublicationRecoveryAction.Complete -> R.string.publication_recoveries_confirm_complete
                        PublicationRecoveryAction.RemovePending -> R.string.publication_recoveries_confirm_remove
                        PublicationRecoveryAction.Forget -> R.string.publication_recoveries_confirm_forget
                    }))
                    Text(stringResource(R.string.publication_recoveries_id, confirmation.proof.entry.id))
                    Text(confirmation.proof.destinationName ?: stringResource(R.string.publication_recoveries_no_destination))
                    confirmation.proof.pendingHash?.let { Text(stringResource(R.string.publication_recoveries_hash, it)) }
                    confirmation.proof.pendingSize?.let { Text(stringResource(R.string.publication_recoveries_bytes, it)) }
                }
            },
            confirmButton = { TextButton(onClick = model::confirm, enabled = !state.busy, modifier = Modifier.testTag("publication-recoveries-confirm")) { Text(stringResource(R.string.publication_recoveries_confirm)) } },
            dismissButton = { TextButton(onClick = model::dismiss, enabled = !state.busy, modifier = Modifier.testTag("publication-recoveries-dismiss")) { Text(stringResource(R.string.publication_recoveries_dismiss)) } })
    }
}

private fun recoveryKindLabel(kind: PublicationRecoveryKind) = when (kind) {
    PublicationRecoveryKind.Collage -> R.string.publication_recoveries_kind_collage
    PublicationRecoveryKind.Gif -> R.string.publication_recoveries_kind_gif
    PublicationRecoveryKind.MotionFrame -> R.string.publication_recoveries_kind_frame
    PublicationRecoveryKind.MotionClip -> R.string.publication_recoveries_kind_clip
    PublicationRecoveryKind.MotionUnknown -> R.string.publication_recoveries_kind_motion_unknown
}
private fun recoveryPhaseLabel(phase: String?) = when (phase) {
    "Intent" -> R.string.publication_recoveries_phase_intent
    "Inserted" -> R.string.publication_recoveries_phase_inserted
    "Ready" -> R.string.publication_recoveries_phase_ready
    "Published" -> R.string.publication_recoveries_phase_published
    "Incomplete" -> R.string.publication_recoveries_phase_incomplete
    "Conflict" -> R.string.publication_recoveries_phase_conflict
    "Unreadable" -> R.string.publication_recoveries_phase_unreadable
    "MissingBacking" -> R.string.publication_recoveries_phase_missing
    "Busy" -> R.string.publication_recoveries_phase_busy
    else -> R.string.publication_recoveries_phase_unknown
}
private fun recoveryActionTag(action: PublicationRecoveryAction) = when (action) {
    PublicationRecoveryAction.Complete -> "complete"
    PublicationRecoveryAction.RemovePending -> "remove-pending"
    PublicationRecoveryAction.Forget -> "forget"
}
private fun recoveryActionLabel(action: PublicationRecoveryAction) = when (action) {
    PublicationRecoveryAction.Complete -> R.string.publication_recoveries_complete
    PublicationRecoveryAction.RemovePending -> R.string.publication_recoveries_remove
    PublicationRecoveryAction.Forget -> R.string.publication_recoveries_forget
}
