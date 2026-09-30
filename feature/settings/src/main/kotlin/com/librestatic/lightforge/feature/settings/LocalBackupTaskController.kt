package com.librestatic.lightforge.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-owned controller; leaving its screen never cancels queued operations. */
class LocalBackupTaskController(context: Context, private val schedule: (String) -> Unit) {
    private val context = context.applicationContext
    private val store = LocalBackupTaskStore(this.context)
    private val grants = LocalBackupTaskGrants(this.context)
    private val mutableTasks = MutableStateFlow<List<LocalBackupTask>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { LocalBackupTaskStore.revisions.collect { refresh() } }
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun refresh() {
        mutableTasks.value = store.list()
    }

    suspend fun enqueueBackup(
        sources: List<String>,
        destination: Uri,
        name: String,
        organization: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            require(sources.isNotEmpty() && sources.size <= BackupManifest.MAX_ENTRIES)
            require(sources.all { it.startsWith("content://") })
            val id = UUID.randomUUID().toString()
            // Only a freshly created, empty SAF output enters this operation's ownership journal.
            (context.contentResolver.openInputStream(destination)
                    ?: throw IOException("Destination unreadable"))
                .use { require(it.read() == -1) { "Destination is not empty" } }
            sources.forEach {
                grants.retain(id, Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            grants.retain(
                id,
                destination,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            store.create(
                LocalBackupTask(
                    id,
                    LocalBackupTaskKind.Backup,
                    System.currentTimeMillis(),
                    name.take(255),
                    sources = sources,
                    destination = destination.toString(),
                    organization = organization,
                    outputs = listOf(LocalBackupTaskOutput(destination.toString(), 0)),
                    filesTotal = sources.size,
                )
            )
            refresh()
            schedule(id)
            id
        }

    suspend fun enqueueRestore(
        archive: File,
        name: String,
        destination: Uri?,
        gallery: Boolean,
        options: LocalRestoreOrganizationOptions,
        expectedManifest: BackupManifest? = null,
    ): String =
        withContext(Dispatchers.IO) {
            require(gallery || destination != null)
            val source = LocalBackupTaskSnapshots.privateSource(context, archive)
            val manifest = expectedManifest ?: LocalBackupArchive.inspect(source)
            require(!gallery || manifest.organization != null)
            val id = UUID.randomUUID().toString()
            // The durable Preparing record precedes any copy/link. Process death can leave a
            // visible
            // resumable task, never a large unaccounted directory. Foreign private callers are
            // copied
            // by the worker; only our session-owned staging receives atomic inode ownership.
            store.create(
                LocalBackupTask(
                    id = id,
                    kind =
                        if (gallery) LocalBackupTaskKind.RestoreGallery
                        else LocalBackupTaskKind.RestoreFolder,
                    createdAt = System.currentTimeMillis(),
                    name = name.take(255),
                    destination = destination?.toString(),
                    organization = gallery,
                    options = options,
                    phase = LocalBackupTaskPhase.Preparing,
                    filesTotal = manifest.entries.size,
                    snapshotSource = source.path,
                    snapshotManifestSha = LocalBackupTaskSnapshots.manifestSha(manifest),
                )
            )
            try {
                destination?.let {
                    grants.retain(
                        id,
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                LocalBackupTaskSnapshots.attachOwned(context, source, store.archive(id))
            } catch (error: Exception) {
                store.update(id) {
                    it.copy(status = LocalBackupTaskStatus.Failed, failure = "snapshot")
                }
                refresh()
                throw error
            }
            refresh()
            schedule(id)
            id
        }

    suspend fun pause(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                check(!it.terminal)
                it.copy(
                    pauseRequested = true,
                    status =
                        if (it.status == LocalBackupTaskStatus.Running) it.status
                        else LocalBackupTaskStatus.Paused,
                )
            }
            refresh()
        }

    suspend fun resume(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                check(!it.terminal)
                it.copy(
                    pauseRequested = false,
                    status =
                        if (it.cancelRequested) LocalBackupTaskStatus.Cancelling
                        else LocalBackupTaskStatus.Queued,
                    failure = null,
                )
            }
            refresh()
            schedule(id)
        }

    suspend fun cancel(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                check(!it.terminal)
                it.copy(
                    cancelRequested = true,
                    pauseRequested = false,
                    status = LocalBackupTaskStatus.Cancelling,
                )
            }
            refresh()
            schedule(id)
        }

    suspend fun regrant(id: String, uri: Uri) =
        withContext(Dispatchers.IO) {
            val task = requireNotNull(store.read(id))
            require(uri.toString() == task.destination) { "Choose the original destination" }
            grants.retain(
                id,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                required = true,
            )
            resume(id)
        }

    suspend fun reviewArchive(id: String): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val task = requireNotNull(store.read(id))
            check(task.status == LocalBackupTaskStatus.Completed)
            val archive = store.archive(id)
            LocalBackupArchive.inspect(archive)
            archive to task.name
        }

    suspend fun forget(id: String) =
        withContext(Dispatchers.IO) {
            check(requireNotNull(store.read(id)).terminal)
            grants.release(id)
            store.forget(id)
            refresh()
        }

    suspend fun reconcile() =
        withContext(Dispatchers.IO) {
            store
                .list()
                .filter {
                    !it.terminal &&
                        !it.pauseRequested &&
                        it.status in
                            setOf(
                                LocalBackupTaskStatus.Queued,
                                LocalBackupTaskStatus.Running,
                                LocalBackupTaskStatus.Cancelling,
                            )
                }
                .forEach { schedule(it.id) }
            refresh()
        }
}
