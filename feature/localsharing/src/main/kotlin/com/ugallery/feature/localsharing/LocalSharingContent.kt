package com.ugallery.feature.localsharing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LocalSharingContent(
    controller: LocalSharingController,
    onBack: () -> Unit,
    onOpenLocalTasks: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val peers by controller.peers.collectAsState()
    val transfers by controller.transfers.collectAsState()
    val receiver by controller.receiver.collectAsState()
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var peerId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var strip by remember { mutableStateOf(true) }
    var regrant by remember { mutableStateOf<String?>(null) }
    var reviewId by remember { mutableStateOf<String?>(null) }
    var importItems by remember { mutableStateOf<List<LocalSharingImportItem>>(emptyList()) }
    var choices by remember { mutableStateOf<Map<String, LocalSharingConflictChoice>>(emptyMap()) }
    fun launch(action: suspend () -> Unit) {
        scope.launch {
            working = true
            error = false
            try {
                action()
            } catch (e: Exception) {
                error = true
            } finally {
                working = false
            }
        }
    }
    val sources =
        rememberLauncherForActivityResult(PeerSourcesPicker()) { uris ->
            if (uris.isNotEmpty()) {
                val ids = uris.map(Uri::toString)
                val id = regrant
                regrant = null
                if (id != null) launch { controller.regrant(id, ids) } else selected = ids
            }
        }
    val qr =
        rememberLauncherForActivityResult(PeerQrPicker()) { uri ->
            if (uri != null)
                launch {
                    code = withContext(Dispatchers.IO) { readPeerQr(context, uri) }
                    controller.parseInvitation(code)
                }
        }
    LaunchedEffect(controller) { controller.reconcile() }
    LazyColumn(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.peer_back)) }
                Text(
                    stringResource(R.string.peer_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        item { Text(stringResource(R.string.peer_scope)) }
        if (error || receiver.failure != null)
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(stringResource(R.string.peer_error), Modifier.padding(12.dp))
                }
            }
        if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.peer_receive),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!receiver.active) {
                        OutlinedTextField(
                            host,
                            { host = it },
                            label = { Text(stringResource(R.string.peer_host)) },
                            modifier = Modifier.fillMaxWidth().testTag("peer-host"),
                        )
                        Button(
                            onClick = {
                                try {
                                    controller.receive(host)
                                } catch (e: Exception) {
                                    error = true
                                }
                            },
                            enabled = !working,
                            modifier = Modifier.testTag("peer-start-receive"),
                        ) {
                            Text(stringResource(R.string.peer_start))
                        }
                    } else {
                        Text(stringResource(R.string.peer_receiving))
                        receiver.invitation?.let { invite ->
                            val text = remember(invite) { controller.invitationText(invite) }
                            val image = remember(text) { peerQr(text) }
                            Image(
                                image.asImageBitmap(),
                                stringResource(R.string.peer_qr),
                                Modifier.size(220.dp),
                            )
                            Text("${invite.host}:${invite.port}")
                            Text(invite.pin, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.peer_expiry))
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(text)) },
                                modifier = Modifier.testTag("peer-copy-code"),
                            ) {
                                Text(stringResource(R.string.peer_copy))
                            }
                        }
                        OutlinedButton(
                            onClick = controller::stopReceive,
                            modifier = Modifier.testTag("peer-stop-receive"),
                        ) {
                            Text(stringResource(R.string.peer_stop))
                        }
                    }
                }
            }
        }
        items(receiver.pending, key = { "pending-${it.pin}" }) { request ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.peer_confirm_device, request.name))
                    Text(request.pin, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.peer_compare))
                    Row {
                        Button(
                            onClick = { launch { controller.approvePair(request.pin) } },
                            modifier = Modifier.testTag("peer-approve-${request.pin}"),
                        ) {
                            Text(stringResource(R.string.peer_approve))
                        }
                        TextButton(onClick = { controller.rejectPair(request.pin) }) {
                            Text(stringResource(R.string.peer_reject))
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.peer_pair),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        code,
                        { code = it },
                        label = { Text(stringResource(R.string.peer_code)) },
                        modifier = Modifier.fillMaxWidth().testTag("peer-code"),
                        maxLines = 3,
                    )
                    Row {
                        TextButton(onClick = { qr.launch(arrayOf("image/*")) }) {
                            Text(stringResource(R.string.peer_read_qr))
                        }
                        Button(
                            onClick = {
                                launch {
                                    pairing =
                                        !controller.pair(
                                            controller.parseInvitation(code),
                                            android.os.Build.MODEL.take(80),
                                        )
                                    if (!pairing) code = ""
                                }
                            },
                            enabled = code.isNotBlank() && !working,
                            modifier = Modifier.testTag("peer-pair"),
                        ) {
                            Text(
                                stringResource(
                                    if (pairing) R.string.peer_check_confirmation
                                    else R.string.peer_pair
                                )
                            )
                        }
                    }
                    if (pairing) Text(stringResource(R.string.peer_wait_pair))
                }
            }
        }
        items(peers, key = { "peer-${it.id}" }) { peer ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = peerId == peer.id,
                                enabled = !peer.revoked && peer.canSend,
                                role = Role.RadioButton,
                                onClick = { peerId = peer.id },
                            )
                            .testTag("peer-profile-${peer.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            peerId == peer.id,
                            null,
                            enabled = !peer.revoked && peer.canSend,
                        )
                        Text(peer.name, Modifier.weight(1f))
                    }
                    Text(peer.id, style = MaterialTheme.typography.bodySmall)
                    if (!peer.canSend) Text(stringResource(R.string.peer_pair_reverse))
                    if (peer.revoked) Text(stringResource(R.string.peer_revoked))
                    else
                        TextButton(onClick = { launch { controller.revoke(peer.id) } }) {
                            Text(stringResource(R.string.peer_revoke))
                        }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.peer_originals),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(
                        onClick = { sources.launch(arrayOf("image/*", "video/*")) },
                        modifier = Modifier.testTag("peer-select-sources"),
                    ) {
                        Text(stringResource(R.string.peer_select, selected.size))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            strip,
                            { strip = it },
                            modifier = Modifier.testTag("peer-strip-location"),
                        )
                        Text(stringResource(R.string.peer_strip))
                    }
                    Text(stringResource(R.string.peer_derivative))
                    Button(
                        onClick = {
                            launch {
                                controller.enqueue(peerId!!, selected, strip)
                                selected = emptyList()
                            }
                        },
                        enabled = selected.isNotEmpty() && peerId != null && !working,
                        modifier = Modifier.testTag("peer-prepare"),
                    ) {
                        Text(stringResource(R.string.peer_prepare))
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.peer_history), style = MaterialTheme.typography.titleLarge)
        }
        items(transfers, key = { "transfer-${it.id}" }) { t ->
            Card(Modifier.testTag("peer-task-${t.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${t.id.take(8)} · ${stringResource(peerStatusText(t.status))}")
                    Text(
                        stringResource(
                            R.string.peer_progress,
                            t.bytesDone,
                            t.manifest?.totalBytes ?: 0,
                        )
                    )
                    t.manifest?.let { Text(stringResource(R.string.peer_count, it.entries.size)) }
                    if (
                        t.status in
                            setOf(
                                LocalSharingStatus.AwaitingReview,
                                LocalSharingStatus.AwaitingReceiveConsent,
                                LocalSharingStatus.ReadyToImport,
                            )
                    )
                        Button(
                            onClick = {
                                launch {
                                    importItems =
                                        if (t.status == LocalSharingStatus.ReadyToImport)
                                            controller.reviewImport(t.id)
                                        else emptyList()
                                    choices =
                                        importItems
                                            .filter {
                                                it.disposition ==
                                                    LocalSharingImportDisposition.Conflict
                                            }
                                            .associate {
                                                it.sourceId to LocalSharingConflictChoice.KeepBoth
                                            }
                                    reviewId = t.id
                                }
                            },
                            modifier = Modifier.testTag("peer-review-${t.id}"),
                        ) {
                            Text(stringResource(R.string.peer_review))
                        }
                    if (t.status == LocalSharingStatus.LocalTaskCreated)
                        Button(onClick = onOpenLocalTasks) {
                            Text(stringResource(R.string.peer_open_local))
                        }
                    if (!t.terminal) {
                        Row {
                            if (
                                t.status in
                                    setOf(
                                        LocalSharingStatus.Paused,
                                        LocalSharingStatus.WaitingPeer,
                                        LocalSharingStatus.NeedsReview,
                                    )
                            )
                                TextButton(
                                    onClick = { launch { controller.resume(t.id) } },
                                    modifier = Modifier.testTag("peer-resume-${t.id}"),
                                    enabled = t.failure != "corrupt",
                                ) {
                                    Text(stringResource(R.string.peer_resume))
                                }
                            else
                                TextButton(onClick = { launch { controller.pause(t.id) } }) {
                                    Text(stringResource(R.string.peer_pause))
                                }
                            TextButton(onClick = { launch { controller.cancel(t.id) } }) {
                                Text(stringResource(R.string.peer_cancel))
                            }
                        }
                        if (t.failure == "permission" && t.direction == LocalSharingDirection.Send)
                            TextButton(
                                onClick = {
                                    regrant = t.id
                                    sources.launch(arrayOf("image/*", "video/*"))
                                }
                            ) {
                                Text(stringResource(R.string.peer_regrant))
                            }
                    }
                    if (t.status == LocalSharingStatus.Completed)
                        Text(stringResource(R.string.peer_received_not_imported))
                }
            }
        }
    }
    val review = transfers.find { it.id == reviewId }
    if (review != null)
        AlertDialog(
            onDismissRequest = { reviewId = null },
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }.testTag("peer-review-dialog"),
            title = { Text(stringResource(R.string.peer_review)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(stringResource(R.string.peer_review_scope))
                        Text(stringResource(R.string.peer_count, review.manifest!!.entries.size))
                        Text(
                            stringResource(
                                R.string.peer_progress,
                                review.manifest.totalBytes,
                                review.manifest.totalBytes,
                            )
                        )
                    }
                    items(review.manifest!!.entries, key = { it.sourceId }) { entry ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(entry.name)
                            Text(
                                "${entry.bytes} B · ${entry.sha256.take(16)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            importItems
                                .find { it.sourceId == entry.sourceId }
                                ?.let { item ->
                                    Text(
                                        stringResource(
                                            when (item.disposition) {
                                                LocalSharingImportDisposition.Add ->
                                                    R.string.peer_add
                                                LocalSharingImportDisposition.AlreadyReceived ->
                                                    R.string.peer_already
                                                LocalSharingImportDisposition.Conflict ->
                                                    R.string.peer_conflict
                                            }
                                        )
                                    )
                                    if (
                                        item.disposition == LocalSharingImportDisposition.Conflict
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                choices[entry.sourceId] ==
                                                    LocalSharingConflictChoice.KeepBoth,
                                                {
                                                    choices =
                                                        choices +
                                                            (entry.sourceId to
                                                                LocalSharingConflictChoice.KeepBoth)
                                                },
                                            )
                                            Text(stringResource(R.string.peer_keep_both))
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                choices[entry.sourceId] ==
                                                    LocalSharingConflictChoice.UseNewest,
                                                {
                                                    choices =
                                                        choices +
                                                            (entry.sourceId to
                                                                LocalSharingConflictChoice
                                                                    .UseNewest)
                                                },
                                            )
                                            Text(stringResource(R.string.peer_use_newest))
                                        }
                                    }
                                }
                        }
                    }
                    if (importItems.isNotEmpty())
                        item { Text(stringResource(R.string.peer_preserve)) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        launch {
                            when (review.status) {
                                LocalSharingStatus.AwaitingReview ->
                                    controller.confirmSend(review.id)
                                LocalSharingStatus.AwaitingReceiveConsent ->
                                    controller.acceptReceive(review.id)
                                LocalSharingStatus.ReadyToImport ->
                                    controller.confirmImport(review.id, choices)
                                else -> kotlin.error("state")
                            }
                            reviewId = null
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.testTag("peer-confirm-review"),
                ) {
                    Text(stringResource(R.string.peer_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewId = null }) {
                    Text(stringResource(R.string.peer_back))
                }
            },
        )
}

private fun peerStatusText(status: LocalSharingStatus) =
    when (status) {
        LocalSharingStatus.Preparing -> R.string.peer_status_preparing
        LocalSharingStatus.AwaitingReview -> R.string.peer_status_review
        LocalSharingStatus.Queued -> R.string.peer_status_queued
        LocalSharingStatus.Transferring -> R.string.peer_status_transfer
        LocalSharingStatus.WaitingPeer -> R.string.peer_status_waiting
        LocalSharingStatus.Paused -> R.string.peer_status_paused
        LocalSharingStatus.AwaitingReceiveConsent -> R.string.peer_status_consent
        LocalSharingStatus.ReadyToImport -> R.string.peer_status_ready
        LocalSharingStatus.Importing -> R.string.peer_status_import
        LocalSharingStatus.LocalTaskCreated -> R.string.peer_status_child
        LocalSharingStatus.Completed -> R.string.peer_status_completed
        LocalSharingStatus.Cancelled -> R.string.peer_status_cancelled
        LocalSharingStatus.NeedsReview -> R.string.peer_status_error
    }

private class PeerSourcesPicker : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class PeerQrPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal fun peerQr(text: String): Bitmap {
    val bits = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480)
    // QR is a machine-readable black/white image, not UI text; intentional 21:1 pair.
    val pixels =
        IntArray(480 * 480) { i ->
            if (bits[i % 480, i / 480]) android.graphics.Color.BLACK
            else android.graphics.Color.WHITE
        }
    return Bitmap.createBitmap(pixels, 480, 480, Bitmap.Config.ARGB_8888)
}

private fun readPeerQr(context: Context, uri: Uri): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
    val bitmap =
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: error("image")
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader()
            .decode(
                BinaryBitmap(
                    HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
                )
            )
            .text
            .also { PeerCodec.invitation(it) }
    } finally {
        bitmap.recycle()
    }
}
