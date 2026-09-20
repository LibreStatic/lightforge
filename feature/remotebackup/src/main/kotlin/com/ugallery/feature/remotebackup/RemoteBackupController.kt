package com.ugallery.feature.remotebackup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.ugallery.core.remotestorage.*
import com.ugallery.feature.settings.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteArchiveReview(
    val archive: File,
    val manifest: BackupManifest,
    val organization: LocalBackupOrganizationReview?,
)

/** Application lifetime, not screen lifetime. No passwords or keys are stored in its UI state. */
class RemoteBackupController(
    context: Context,
    private val services: RemoteBackupServices,
    private val schedule: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val store = RemoteBackupStore(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableProfiles = MutableStateFlow<List<RemoteProfile>>(emptyList())
    private val mutableTasks = MutableStateFlow<List<RemoteBackupTask>>(emptyList())
    private val mutableProfileFailure = MutableStateFlow(false)
    val profiles = mutableProfiles.asStateFlow()
    val tasks = mutableTasks.asStateFlow()
    val profileFailure = mutableProfileFailure.asStateFlow()
    private val mutableProbes = MutableStateFlow<List<RemoteProfileProbe>>(emptyList())
    val probes = mutableProbes.asStateFlow()
    val organizationAvailable: Boolean
        get() = services.organization != null

    init {
        scope.launch { RemoteBackupStore.changes.collect { refresh() } }
    }

    private fun refresh() {
        mutableTasks.value = store.list()
        runCatching { store.probes() }
            .onSuccess { mutableProbes.value = it }
            .onFailure { mutableProfileFailure.value = true }
        runCatching { store.profiles() }
            .onSuccess {
                mutableProfiles.value = it
                mutableProfileFailure.value = false
            }
            .onFailure { mutableProfileFailure.value = true }
    }

    fun close() {
        scope.cancel()
    }

    suspend fun saveProfile(profile: RemoteProfile, credentials: RemoteCredentials) =
        withContext(Dispatchers.IO) {
            profile.validate()
            // Metadata is write-ahead; a failed vault save leaves a visible profile requiring
            // credentials.
            store.saveProfile(profile)
            credentials.use { services.credentials.save(profile.id, it) }
            refresh()
        }

    suspend fun updateCredentials(profileId: String, credentials: RemoteCredentials) =
        withContext(Dispatchers.IO) {
            require(store.profiles().any { it.id == profileId })
            credentials.use { services.credentials.save(profileId, it) }
        }

    suspend fun testConnection(profileId: String): RemoteConnectionReview =
        connection(profile(profileId)) { connection ->
            connection.list(1000) // Authenticate and check read/list, not just open a TCP socket.
            RemoteConnectionReview(
                connection.identity,
                connection.capabilities.encrypted,
                connection.capabilities.signed,
                connection.capabilities.atomicPublish,
            )
        }

    suspend fun trustProfile(profileId: String, observedKey: String) =
        withContext(Dispatchers.IO) {
            val before = profile(profileId)
            require(before.protocol == RemoteProtocol.SFTP)
            require(
                store.probes().singleOrNull { it.profileId == profileId }?.observedHostKey ==
                    observedKey
            )
            store.saveProfile(before.copy(trustedHostKey = observedKey).also { it.validate() })
        }

    suspend fun listArchives(profileId: String): List<RemoteEntry> =
        connection(profile(profileId)) {
            it.list(1000).filter { entry ->
                entry.regularFile &&
                    entry.name.endsWith(".ugallery.zip", true) &&
                    entry.size in 1..MaxArchiveBytes
            }
        }

    suspend fun enqueueBackup(
        profileId: String,
        sources: List<String>,
        organization: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            require(
                sources.isNotEmpty() &&
                    sources.size <= BackupManifest.MAX_ENTRIES &&
                    sources.all { it.startsWith("content://") }
            )
            require(!organization || services.organization != null)
            connection(profile(profileId)) { connection ->
                RemoteBackupIO.requireUploadCapability(connection)
                connection.list(1000)
            }
            val id = UUID.randomUUID().toString()
            store.create(
                RemoteBackupTask(
                    id,
                    profile(profileId),
                    RemoteBackupDirection.Upload,
                    System.currentTimeMillis(),
                    status = RemoteBackupStatus.WaitingPermission,
                    sources = sources.toList(),
                    organization = organization,
                    name = "UGallery-$id.ugallery.zip",
                )
            )
            try {
                sources.forEach {
                    RemoteBackupGrants(context)
                        .retain(
                            id,
                            Uri.parse(it),
                            required = DocumentsContract.isDocumentUri(context, Uri.parse(it)),
                        )
                }
            } catch (error: Exception) {
                store.update(id) {
                    it.copy(status = RemoteBackupStatus.WaitingPermission, failure = "PERMISSION")
                }
                throw error
            }
            store.update(id) { it.copy(status = RemoteBackupStatus.Queued) }
            schedule(id)
            id
        }

    suspend fun enqueueDownload(profileId: String, remote: RemoteEntry): String =
        withContext(Dispatchers.IO) {
            RemoteNames.requireChild(remote.name)
            require(remote.regularFile && remote.size in 1..MaxArchiveBytes)
            val id = UUID.randomUUID().toString()
            store.create(
                RemoteBackupTask(
                    id,
                    profile(profileId),
                    RemoteBackupDirection.Download,
                    System.currentTimeMillis(),
                    phase = RemoteBackupPhase.Transfer,
                    remote = remote,
                    name = remote.name,
                    totalBytes = remote.size,
                )
            )
            schedule(id)
            id
        }

    suspend fun confirmUpload(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                require(it.status == RemoteBackupStatus.AwaitingUploadReview)
                it.copy(status = RemoteBackupStatus.Queued, phase = RemoteBackupPhase.Transfer)
            }
            schedule(id)
        }

    suspend fun review(id: String): RemoteArchiveReview =
        withContext(Dispatchers.IO) {
            val task = store.get(id) ?: throw IOException("Missing task")
            require(
                task.status in
                    setOf(
                        RemoteBackupStatus.AwaitingUploadReview,
                        RemoteBackupStatus.AwaitingRestoreReview,
                        RemoteBackupStatus.Completed,
                    )
            )
            val archive = store.archive(id)
            val actual = RemoteBackupIO.digest(archive) { ensureActive() }
            require(actual.size == task.totalBytes && actual.sha256 == task.archiveSha)
            val manifest = LocalBackupArchive.inspect(archive) { ensureActive() }
            val review =
                if (manifest.organization != null && services.organization != null)
                    services.organization.review(
                        LocalBackupArchive.readOrganization(archive, manifest),
                        manifest,
                    )
                else null
            RemoteArchiveReview(archive, manifest, review)
        }

    suspend fun restoreReviewed(
        id: String,
        gallery: Boolean,
        destination: Uri?,
        options: LocalRestoreOrganizationOptions,
    ) =
        withContext(Dispatchers.IO) {
            val review = review(id)
            require(
                !gallery ||
                    (review.manifest.organization != null &&
                        review.organization?.canRestore == true)
            )
            require(!gallery || review.organization?.partial != true || options.allowPartial)
            require(gallery || destination != null)
            store.update(id) {
                require(it.status == RemoteBackupStatus.AwaitingRestoreReview)
                it.copy(
                    status = RemoteBackupStatus.WaitingPermission,
                    phase = RemoteBackupPhase.RestoreHandoff,
                    restoreGallery = gallery,
                    restoreDestination = destination?.toString(),
                    restoreOptions = options,
                    pauseRequested = false,
                    cancelRequested = false,
                )
            }
            try {
                destination?.let {
                    RemoteBackupGrants(context)
                        .retain(
                            id,
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            required = true,
                        )
                }
                store.update(id) { it.copy(status = RemoteBackupStatus.Queued) }
            } catch (error: SecurityException) {
                store.update(id) {
                    it.copy(status = RemoteBackupStatus.WaitingPermission, failure = "PERMISSION")
                }
                throw error
            }
            schedule(id)
        }

    suspend fun regrantSources(id: String, uris: List<Uri>) =
        withContext(Dispatchers.IO) {
            val task = requireNotNull(store.get(id))
            require(
                task.status == RemoteBackupStatus.WaitingPermission &&
                    task.phase == RemoteBackupPhase.Preparing
            )
            require(task.sources.sorted() == uris.map(Uri::toString).sorted())
            uris.forEach {
                RemoteBackupGrants(context)
                    .retain(id, it, required = DocumentsContract.isDocumentUri(context, it))
            }
            resume(id)
        }

    suspend fun regrantDestination(id: String, uri: Uri) =
        withContext(Dispatchers.IO) {
            val task = requireNotNull(store.get(id))
            require(
                task.status == RemoteBackupStatus.WaitingPermission &&
                    task.phase == RemoteBackupPhase.RestoreHandoff
            )
            require(task.restoreDestination == uri.toString())
            RemoteBackupGrants(context)
                .retain(
                    id,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    required = true,
                )
            resume(id)
        }

    suspend fun pause(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                if (
                    it.terminal ||
                        it.status in
                            setOf(
                                RemoteBackupStatus.AwaitingUploadReview,
                                RemoteBackupStatus.AwaitingRestoreReview,
                            )
                )
                    it
                else
                    it.copy(
                        pauseRequested = true,
                        status =
                            if (it.status == RemoteBackupStatus.Running) it.status
                            else RemoteBackupStatus.Paused,
                    )
            }
        }

    suspend fun resume(id: String) =
        withContext(Dispatchers.IO) {
            val result =
                store.update(id) {
                    if (
                        it.terminal ||
                            it.status == RemoteBackupStatus.NeedsReview ||
                            it.status == RemoteBackupStatus.WaitingIdentity ||
                            it.status in
                                setOf(
                                    RemoteBackupStatus.AwaitingUploadReview,
                                    RemoteBackupStatus.AwaitingRestoreReview,
                                )
                    )
                        it
                    else
                        it.copy(
                            status = RemoteBackupStatus.Queued,
                            pauseRequested = false,
                            failure = null,
                        )
                }
            if (result.status == RemoteBackupStatus.Queued) schedule(id)
        }

    suspend fun trustTask(id: String, observedKey: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                require(
                    it.status == RemoteBackupStatus.WaitingIdentity &&
                        it.observedHostKey == observedKey
                )
                it.copy(
                    profile =
                        it.profile.copy(trustedHostKey = observedKey).also { p -> p.validate() },
                    status = RemoteBackupStatus.Queued,
                    pauseRequested = false,
                    failure = null,
                )
            }
            schedule(id)
        }

    suspend fun cancel(id: String) =
        withContext(Dispatchers.IO) {
            val value =
                store.update(id) {
                    if (it.terminal) it else it.copy(cancelRequested = true, pauseRequested = false)
                }
            if (!value.terminal) schedule(id)
        }

    suspend fun reconcile() =
        withContext(Dispatchers.IO) {
            store
                .list()
                .filter {
                    !it.terminal &&
                        it.failure != "corrupt" &&
                        (it.cancelRequested ||
                            (!it.pauseRequested &&
                                it.status in
                                    setOf(RemoteBackupStatus.Queued, RemoteBackupStatus.Running)))
                }
                .forEach { schedule(it.id) }
        }

    private fun profile(id: String): RemoteProfile = store.profiles().single { it.id == id }

    private suspend fun <T> connection(profile: RemoteProfile, block: (RemoteConnection) -> T): T =
        coroutineScope {
            val cancellation = RemoteCancellation()
            val guard = launch {
                try {
                    awaitCancellation()
                } finally {
                    cancellation.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    if (!services.networkAllowed())
                        throw RemoteStorageException(RemoteFailure.PERMISSION)
                    val credentials =
                        services.credentials.load(profile.id)
                            ?: throw RemoteStorageException(RemoteFailure.AUTHENTICATION)
                    try {
                        credentials.use {
                            services.connections.connect(profile, it, cancellation).use { connection
                                ->
                                try {
                                    block(connection)
                                } finally {
                                    store.saveProbe(
                                        RemoteProfileProbe(
                                            profile.id,
                                            identity = connection.identity,
                                            residuals = connection.residualNames,
                                            encrypted = connection.capabilities.encrypted,
                                            signed = connection.capabilities.signed,
                                            atomicPublish = connection.capabilities.atomicPublish,
                                        )
                                    )
                                }
                            }
                        }
                    } catch (error: RemoteStorageException) {
                        store.saveProbe(
                            RemoteProfileProbe(
                                profile.id,
                                failure = error.failure.name,
                                observedHostKey = error.observedHostKey,
                                residuals = error.residualNames,
                            )
                        )
                        throw error
                    }
                }
            } finally {
                guard.cancel()
                cancellation.cancel()
            }
        }

    companion object {
        const val MaxArchiveBytes = 100L * 1024 * 1024 * 1024
    }
}
