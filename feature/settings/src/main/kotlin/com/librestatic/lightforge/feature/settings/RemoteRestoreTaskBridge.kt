package com.librestatic.lightforge.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Root integration: stable restore identity survives death between child creation and parent
 * linkage. [parentGrants] is the remote ledger whose destination grant the local restore takes
 * over, so it is released once, when the local restore finishes.
 */
class RemoteRestoreTaskBridge(
    context: Context,
    private val parentGrants: PersistableUriGrants? = null,
    private val schedule: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "remote-restore-receipts")
    private val store = LocalBackupTaskStore(this.context)
    private val grants = LocalBackupTaskGrants(this.context)

    /** Read-only handoff reconciliation, including death before the parent receipt was written. */
    suspend fun existing(requestId: String): String? =
        withContext(Dispatchers.IO) {
            require(LocalBackupTaskStore.validId(requestId))
            val id =
                UUID.nameUUIDFromBytes(("remote-restore-v1:" + requestId).toByteArray()).toString()
            synchronized(lock) {
                if (!root.isDirectory) return@synchronized null
                RandomAccessFile(File(root, "$requestId.lock"), "rw").use { lockFile ->
                    lockFile.channel.lock().use {
                        val receipt = AtomicFile(File(root, "$requestId.json"))
                        if (
                            receipt.baseFile.exists() ||
                                File(receipt.baseFile.path + ".bak").exists()
                        ) {
                            val json =
                                receipt.openRead().use { input ->
                                    val buffer = ByteArray(4097)
                                    var count = 0
                                    while (count < buffer.size) {
                                        val n = input.read(buffer, count, buffer.size - count)
                                        if (n < 0) break
                                        require(n > 0)
                                        count += n
                                    }
                                    require(count <= 4096)
                                    JSONObject(String(buffer, 0, count, Charsets.UTF_8))
                                }
                            require(json.getInt("version") == 1 && json.getString("id") == id)
                            id
                        } else
                            store
                                .read(id)
                                ?.also {
                                    require(
                                        it.kind == LocalBackupTaskKind.RestoreGallery ||
                                            it.kind == LocalBackupTaskKind.RestoreFolder
                                    )
                                }
                                ?.id
                    }
                }
            }
        }

    suspend fun enqueueOnce(
        requestId: String,
        archive: File,
        manifest: BackupManifest,
        destination: Uri?,
        gallery: Boolean,
        options: LocalRestoreOrganizationOptions,
    ): String =
        withContext(Dispatchers.IO) {
            require(LocalBackupTaskStore.validId(requestId))
            require(gallery || destination != null)
            require(!gallery || manifest.organization != null)
            val id =
                UUID.nameUUIDFromBytes(("remote-restore-v1:" + requestId).toByteArray()).toString()
            val manifestSha = LocalBackupTaskSnapshots.manifestSha(manifest)
            val request =
                JSONObject()
                    .put("manifest", manifestSha)
                    .put("destination", destination?.toString() ?: JSONObject.NULL)
                    .put("gallery", gallery)
                    .put("global", options.importGlobalRules)
                    .put("partial", options.allowPartial)
                    .toString()
            val requestSha =
                MessageDigest.getInstance("SHA-256").digest(request.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
            synchronized(lock) {
                check(root.isDirectory || root.mkdirs())
                RandomAccessFile(File(root, "$requestId.lock"), "rw").use { lockFile ->
                    lockFile.channel.lock().use {
                        val receipt = AtomicFile(File(root, "$requestId.json"))
                        if (
                            receipt.baseFile.exists() ||
                                File(receipt.baseFile.path + ".bak").exists()
                        ) {
                            val json =
                                receipt.openRead().use { input ->
                                    val buffer = ByteArray(4097)
                                    var count = 0
                                    while (count < buffer.size) {
                                        val read = input.read(buffer, count, buffer.size - count)
                                        if (read < 0) break
                                        require(read > 0)
                                        count += read
                                    }
                                    require(count <= 4096)
                                    JSONObject(String(buffer, 0, count, Charsets.UTF_8))
                                }
                            require(
                                json.getInt("version") == 1 &&
                                    json.getString("id") == id &&
                                    json.getString("request") == requestSha
                            )
                            // Receipt remains after task history is forgotten. Never recreate a
                            // completed restore.
                            val live = store.read(id)?.takeUnless { it.terminal }
                            // Finish a handoff interrupted after the receipt was written.
                            destination?.let {
                                if (live != null) transferGrant(requestId, id, it)
                                else parentGrants?.release(requestId)
                            }
                            live?.let { schedule(id) }
                            return@synchronized id
                        }
                        val source = LocalBackupTaskSnapshots.privateSource(context, archive)
                        require(
                            LocalBackupTaskSnapshots.manifestSha(
                                LocalBackupArchive.inspect(source)
                            ) == manifestSha
                        )
                        val existing = store.read(id)
                        if (existing == null) {
                            store.create(
                                LocalBackupTask(
                                    id = id,
                                    kind =
                                        if (gallery) LocalBackupTaskKind.RestoreGallery
                                        else LocalBackupTaskKind.RestoreFolder,
                                    createdAt = System.currentTimeMillis(),
                                    name = archive.name.take(255),
                                    destination = destination?.toString(),
                                    organization = gallery,
                                    options = options,
                                    phase = LocalBackupTaskPhase.Preparing,
                                    filesTotal = manifest.entries.size,
                                    snapshotSource = source.path,
                                    snapshotManifestSha = manifestSha,
                                )
                            )
                        } else {
                            require(
                                existing.snapshotManifestSha == manifestSha &&
                                    existing.destination == destination?.toString() &&
                                    existing.organization == gallery &&
                                    existing.options == options &&
                                    existing.snapshotSource == source.path
                            )
                        }
                        destination?.let { transferGrant(requestId, id, it) }
                        // Worker copies remote private archive; parent retains it until child
                        // completion.
                        val output = receipt.startWrite()
                        try {
                            output.write(
                                JSONObject()
                                    .put("version", 1)
                                    .put("id", id)
                                    .put("request", requestSha)
                                    .toString()
                                    .toByteArray()
                            )
                            receipt.finishWrite(output)
                        } catch (error: Throwable) {
                            receipt.failWrite(output)
                            throw error
                        }
                        schedule(id)
                        id
                    }
                }
            }
        }

    /** Local ledger records the parent's flags first, then the parent forgets them. */
    private fun transferGrant(requestId: String, id: String, destination: Uri) {
        grants.retain(
            id,
            destination,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            adopted = parentGrants?.owned(requestId, destination) ?: 0,
        )
        parentGrants?.handOff(requestId, destination)
    }

    companion object {
        private val lock = Any()
    }
}
