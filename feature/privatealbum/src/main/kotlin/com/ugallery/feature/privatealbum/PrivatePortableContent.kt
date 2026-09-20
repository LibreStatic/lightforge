package com.ugallery.feature.privatealbum

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.ugallery.core.security.PrivateAlbumCrypto
import kotlinx.coroutines.*

private class PrivateLocalOpen : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class PrivateLocalCreate :
    ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

/** Modal stays under FLAG_SECURE. Passwords are never saved to instance state or files. */
@Composable
fun PrivatePortableContent(
    repository: PrivateAlbumRepository,
    hasItems: Boolean,
    onClose: () -> Unit,
) {
    PrivatePortableContent(repository, hasItems, onClose, isUnlocked = true)
}

/**
 * Keep this composition alive across the system picker; unlocking remains the parent's decision.
 */
@Composable
fun PrivatePortableContent(
    repository: PrivateAlbumRepository,
    hasItems: Boolean,
    onClose: () -> Unit,
    isUnlocked: Boolean,
    onAuthenticationRequired: () -> Unit = {},
) {
    val unlockedNow by rememberUpdatedState(isUnlocked)
    val transfer = remember(repository) { repository.portableTransfers() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operationKind by remember { mutableStateOf("") }
    var cleaningForLock by remember { mutableStateOf(false) }
    var awaitingCreate by remember { mutableStateOf(false) }
    var createReturned by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<Uri?>(null) }
    var mode by remember { mutableStateOf("choose") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<Uri?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var export by remember { mutableStateOf<PrivatePortableTransfer.Export?>(null) }
    var restore by remember { mutableStateOf<PrivatePortableTransfer.Restore?>(null) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<PrivatePortableTransfer.Result?>(null) }
    var error by remember { mutableStateOf(false) }
    var cancelConfirm by remember { mutableStateOf(false) }
    fun cleanupAndClose() {
        password = ""
        confirmation = ""
        try {
            transfer.clearOwnedStaging()
            onClose()
        } catch (_: Exception) {
            error = true
        }
    }
    LaunchedEffect(transfer, isUnlocked) {
        if (isUnlocked) {
            try {
                if (transfer.recoverAbandoned().isNotEmpty()) error = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = true
            }
        }
    }
    fun close() {
        if (busy) cancelConfirm = true else cleanupAndClose()
    }
    DisposableEffect(transfer) {
        onDispose {
            val running = job
            running?.cancel()
            fun cleanup() {
                try {
                    transfer.clearOwnedStaging()
                } catch (failure: Exception) {
                    android.util.Log.e(
                        "PrivateBackup",
                        "Owned staging retained for journal recovery",
                        failure,
                    )
                }
            }
            if (running == null) cleanup() else running.invokeOnCompletion { cleanup() }
        }
    }
    BackHandler(enabled = isUnlocked) { close() }
    fun runOperation(kind: String, operation: suspend () -> Unit) {
        if (!unlockedNow || busy || cleaningForLock) return
        operationKind = kind
        busy = true
        error = false
        job =
            scope.launch {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (PrivateAlbumCrypto.requiresAuthentication(failure)) onAuthenticationRequired()
                    error = true
                } finally {
                    busy = false
                    operationKind = ""
                    password = ""
                    confirmation = ""
                }
            }
    }
    val create =
        rememberLauncherForActivityResult(PrivateLocalCreate()) { destination ->
            // This callback can arrive while the vault is locked. It only retains a result:
            // no publishing, decryption, key access, or implicit session authorization.
            pendingDestination = destination
            createReturned = true
            awaitingCreate = false
        }
    val open =
        rememberLauncherForActivityResult(PrivateLocalOpen()) { uri ->
            if (uri != null) {
                source = uri
                mode = "restore"
                error = false
            }
        }
    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) {
            cleaningForLock = true
            password = ""
            confirmation = ""
            cancelConfirm = false
            val running = job
            val finishingCiphertext = operationKind == "publish"
            if (!finishingCiphertext) running?.cancel()
            try {
                // Lock must not race cleanup with a rapid successful reauthentication.
                // An already-authorized ciphertext-only publication can finish independently.
                withContext(NonCancellable) {
                    if (!finishingCiphertext) running?.join()
                    val sensitiveReview = restore
                    restore = null
                    if (sensitiveReview != null) {
                        try {
                            withContext(Dispatchers.IO) { transfer.discard(sensitiveReview) }
                        } catch (_: Exception) {
                            error = true
                        }
                    }
                    if (!finishingCiphertext && export == null) {
                        try {
                            withContext(Dispatchers.IO) { transfer.clearOwnedStaging() }
                        } catch (_: Exception) {
                            error = true
                        }
                    }
                    password = ""
                    confirmation = ""
                    progress = 0 to 0
                    if (!finishingCiphertext)
                        mode =
                            when {
                                result != null -> "restored"
                                savedUri != null -> "saved"
                                export != null -> "awaiting-destination"
                                source != null -> "restore"
                                else -> "choose"
                            }
                }
            } finally {
                cleaningForLock = false
            }
        }
    }
    LaunchedEffect(isUnlocked, cleaningForLock, busy, export, createReturned) {
        if (isUnlocked && !cleaningForLock && !busy) {
            val prepared = export
            if (createReturned) {
                val destination = pendingDestination
                createReturned = false
                pendingDestination = null
                if (prepared == null || destination == null) {
                    if (prepared != null) {
                        try {
                            withContext(Dispatchers.IO) { transfer.discard(prepared) }
                        } catch (_: Exception) {
                            error = true
                        }
                    }
                    export = null
                    mode = "choose"
                } else
                    runOperation("publish") {
                        // Consume the single pending callback before starting; lock cannot start it
                        // twice.
                        try {
                            savedUri = transfer.publish(prepared, destination)
                            mode = "saved"
                        } finally {
                            export = null
                        }
                    }
            } else if (prepared != null && !awaitingCreate) {
                awaitingCreate = true
                create.launch("UGallery-private-backup.ugpb")
            }
        }
    }
    // Launchers/effects remain registered, but every modal and its sensitive semantics disappear.
    if (!isUnlocked || cleaningForLock) return
    AlertDialog(
        onDismissRequest = { close() },
        modifier =
            Modifier.testTag("private-portable-screen").semantics { testTagsAsResourceId = true },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.private_portable_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.private_portable_body))
                if (error)
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            stringResource(R.string.private_portable_error),
                            Modifier.padding(12.dp).testTag("private-portable-error"),
                        )
                    }
                if (busy) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("private-portable-progress")
                    )
                    if (progress.second > 0)
                        Text(
                            stringResource(
                                R.string.private_portable_progress,
                                progress.first,
                                progress.second,
                            )
                        )
                } else
                    when (mode) {
                        "choose" -> {
                            Button(
                                onClick = { mode = "export" },
                                enabled = hasItems,
                                modifier = Modifier.testTag("private-portable-export"),
                            ) {
                                Text(stringResource(R.string.private_portable_export))
                            }
                            OutlinedButton(
                                onClick = {
                                    open.launch(
                                        arrayOf(
                                            "application/octet-stream",
                                            "application/zip",
                                            "*/*",
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("private-portable-import"),
                            ) {
                                Text(stringResource(R.string.private_portable_import))
                            }
                        }
                        "export",
                        "restore" -> {
                            Text(stringResource(R.string.private_portable_password_note))
                            OutlinedTextField(
                                password,
                                { password = it.take(1024) },
                                label = {
                                    Text(stringResource(R.string.private_portable_password))
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier =
                                    Modifier.fillMaxWidth().testTag("private-portable-password"),
                            )
                            if (mode == "export")
                                OutlinedTextField(
                                    confirmation,
                                    { confirmation = it.take(1024) },
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.private_portable_confirm_password
                                            )
                                        )
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .testTag("private-portable-confirm-password"),
                                )
                            Button(
                                enabled =
                                    password.length >= 12 &&
                                        (mode != "export" || confirmation == password),
                                modifier = Modifier.testTag("private-portable-prepare"),
                                onClick = {
                                    val secret = password.toCharArray()
                                    password = ""
                                    confirmation = ""
                                    val exporting = mode == "export"
                                    runOperation(
                                        if (exporting) "prepare-export" else "prepare-restore"
                                    ) {
                                        try {
                                            if (exporting) {
                                                val prepared =
                                                    transfer.prepareExport(
                                                        repository.requireMasterKeyBinding(),
                                                        secret,
                                                    ) { done, total ->
                                                        progress = done to total
                                                    }
                                                export = prepared
                                                mode = "awaiting-destination"
                                            } else {
                                                restore =
                                                    transfer.prepareRestore(
                                                        requireNotNull(source),
                                                        secret,
                                                    ) { done, total ->
                                                        progress = done to total
                                                    }
                                                mode = "review"
                                            }
                                        } finally {
                                            secret.fill('\u0000')
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.private_portable_prepare))
                            }
                        }
                        "review" ->
                            restore?.let { prepared ->
                                if (prepared.previousReceipt != null) {
                                    Text(
                                        stringResource(
                                            R.string.private_portable_previous,
                                            prepared.previousReceipt.itemCount,
                                        ),
                                        Modifier.testTag("private-portable-previous"),
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            R.string.private_portable_review,
                                            prepared.items.size,
                                        ),
                                        Modifier.testTag("private-portable-review"),
                                    )
                                    prepared.items.take(8).forEach {
                                        Text(it.metadata.displayName, maxLines = 1)
                                    }
                                    Button(
                                        onClick = {
                                            runOperation("commit") {
                                                result =
                                                    transfer.commit(
                                                        prepared,
                                                        repository.requireMasterKeyBinding(),
                                                    )
                                                restore = null
                                                mode = "restored"
                                            }
                                        },
                                        modifier = Modifier.testTag("private-portable-commit"),
                                    ) {
                                        Text(stringResource(R.string.private_portable_commit))
                                    }
                                }
                            }
                        "restored" ->
                            result?.let {
                                Text(
                                    stringResource(
                                        if (it.alreadyCommitted) R.string.private_portable_previous
                                        else R.string.private_portable_restored,
                                        it.count,
                                    ),
                                    Modifier.testTag("private-portable-restored"),
                                )
                            }
                        "saved" -> {
                            Text(
                                stringResource(R.string.private_portable_saved),
                                Modifier.testTag("private-portable-saved"),
                            )
                            savedUri?.let { uri ->
                                TextButton(
                                    onClick = {
                                        val intent =
                                            Intent(Intent.ACTION_SEND)
                                                .setType("application/octet-stream")
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        runCatching {
                                                context.startActivity(
                                                    Intent.createChooser(intent, null)
                                                )
                                            }
                                            .onFailure { error = true }
                                    }
                                ) {
                                    Text(stringResource(R.string.private_portable_share))
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { close() },
                modifier = Modifier.testTag("private-portable-close"),
            ) {
                Text(stringResource(if (busy) R.string.private_cancel else R.string.private_ok))
            }
        },
    )
    if (cancelConfirm)
        AlertDialog(
            onDismissRequest = { cancelConfirm = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(stringResource(R.string.private_portable_cancel_title)) },
            text = { Text(stringResource(R.string.private_portable_cancel_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelConfirm = false
                        val running = job
                        running?.cancel()
                        scope.launch {
                            running?.join()
                            cleanupAndClose()
                        }
                    },
                    modifier = Modifier.testTag("private-portable-cancel-confirm"),
                ) {
                    Text(stringResource(R.string.private_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelConfirm = false }) {
                    Text(stringResource(R.string.private_portable_continue))
                }
            },
        )
}
