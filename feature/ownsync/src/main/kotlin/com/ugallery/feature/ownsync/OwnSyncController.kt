package com.ugallery.feature.ownsync

import android.content.Context
import android.net.Uri
import com.ugallery.core.remotestorage.RemoteProfile
import com.ugallery.core.remotestorage.RemoteProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class OwnSyncController(
    context: Context,
    private val services: OwnSyncServices,
    private val schedule: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val store = OwnSyncStore(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableJobs = MutableStateFlow<List<OwnSyncJob>>(emptyList())
    private val mutableRuns = MutableStateFlow<List<OwnSyncRun>>(emptyList())
    private val mutableProfiles = MutableStateFlow<List<RemoteProfile>>(emptyList())
    val jobs = mutableJobs.asStateFlow()
    val runs = mutableRuns.asStateFlow()
    val profiles = mutableProfiles.asStateFlow()

    init {
        scope.launch { OwnSyncStore.changes.collectLatest { reload() } }
        reconcile()
    }

    private fun reload() {
        mutableJobs.value = store.jobs()
        mutableRuns.value = store.runs()
        mutableProfiles.value = runCatching { services.profiles() }.getOrDefault(emptyList())
    }

    fun reconcile() {
        scope.launch {
            reload()
            store
                .runs()
                .filter {
                    !it.terminal &&
                        (it.status in listOf(OwnSyncStatus.Running, OwnSyncStatus.Queued) ||
                            it.cancelRequested) &&
                        it.failure != "corrupt"
                }
                .forEach { schedule(it.id) }
        }
    }

    fun close() {
        scope.cancel()
    }

    suspend fun create(name: String, tree: Uri, profileId: String, policy: OwnSyncPolicy): String =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank() && name.length <= 120)
            val profile = services.profiles().single { it.id == profileId }.also { it.validate() }
            require(profile.protocol != RemoteProtocol.SFTP || profile.trustedHostKey != null)
            val job = OwnSyncJob(ownSyncUuid(), name.trim(), tree.toString(), profile, policy)
            services.sourceFactory(context).retain(job.id, tree)
            val run = OwnSyncRun(ownSyncUuid(), job.id)
            store.create(job, run)
            schedule(run.id)
            reload()
            run.id
        }

    suspend fun rerun(jobId: String): String =
        withContext(Dispatchers.IO) {
            require(store.runs().none { it.jobId == jobId && !it.terminal })
            val job = store.job(jobId)!!
            services.sourceFactory(context).retain(jobId, Uri.parse(job.tree))
            val run = OwnSyncRun(ownSyncUuid(), jobId)
            store.create(run)
            schedule(run.id)
            run.id
        }

    suspend fun confirm(
        runId: String,
        allowPartial: Boolean = false,
        mirrorConfirmed: Boolean = false,
    ) =
        withContext(Dispatchers.IO) {
            store.update(runId) { run ->
                require(
                    run.status == OwnSyncStatus.AwaitingReview &&
                        !run.pauseRequested &&
                        !run.cancelRequested
                )
                require(
                    run.snapshot != null &&
                        (allowPartial ||
                            run.snapshot.complete &&
                                run.plan.none { it.action == OwnSyncAction.Inaccessible })
                )
                require(
                    run.plan.none { it.action == OwnSyncAction.Quarantine } ||
                        mirrorConfirmed && run.snapshot.complete
                )
                run.copy(
                    status = OwnSyncStatus.Queued,
                    approved = true,
                    mirrorApproved = mirrorConfirmed,
                )
            }
            schedule(runId)
        }

    suspend fun pause(runId: String) =
        withContext(Dispatchers.IO) {
            store.update(runId) {
                if (it.terminal) it
                else
                    it.copy(
                        pauseRequested = true,
                        status =
                            if (it.status == OwnSyncStatus.Running) it.status
                            else OwnSyncStatus.Paused,
                    )
            }
            schedule(runId)
        }

    suspend fun cancel(runId: String) =
        withContext(Dispatchers.IO) {
            store.update(runId) { if (it.terminal) it else it.copy(cancelRequested = true) }
            schedule(runId)
        }

    suspend fun resume(runId: String) =
        withContext(Dispatchers.IO) {
            store.update(runId) { run ->
                require(!run.terminal && !run.cancelRequested && run.failure != "corrupt")
                // Changed/uncertain destinations require a fresh scan, not an unreviewed overwrite.
                require(run.failure != "changed")
                run.copy(
                    status =
                        if (run.snapshot != null && !run.approved) OwnSyncStatus.AwaitingReview
                        else OwnSyncStatus.Queued,
                    pauseRequested = false,
                    failure = null,
                )
            }
            schedule(runId)
        }

    suspend fun regrant(runId: String, tree: Uri) =
        withContext(Dispatchers.IO) {
            val run = store.run(runId)!!
            val job = store.job(run.jobId)!!
            require(tree.toString() == job.tree)
            services.sourceFactory(context).retain(job.id, tree)
            resume(runId)
        }

    suspend fun restoreQuarantine(jobId: String, outputId: String): String =
        withContext(Dispatchers.IO) {
            require(store.runs().none { it.jobId == jobId && !it.terminal })
            val output =
                store.job(jobId)!!.outputs.single { it.id == outputId && it.quarantine != null }
            val run =
                OwnSyncRun(
                    ownSyncUuid(),
                    jobId,
                    approved = true,
                    mirrorApproved = true,
                    restoration = true,
                    plan =
                        listOf(
                            OwnSyncPlanEntry(
                                ownSyncUuid(),
                                OwnSyncAction.Restore,
                                output = output,
                                path = output.path,
                            )
                        ),
                )
            store.create(run)
            schedule(run.id)
            run.id
        }

    /**
     * A cancelled move can have crossed the server boundary. The intent supplies a safe reverse
     * pair.
     */
    suspend fun recoverCancelledMove(runId: String, entryId: String): String =
        withContext(Dispatchers.IO) {
            val previous = store.run(runId)!!
            require(previous.terminal)
            val entry =
                previous.plan.single {
                    it.id == entryId &&
                        it.action == OwnSyncAction.Quarantine &&
                        !it.done &&
                        it.staging != null
                }
            require(store.runs().none { it.jobId == previous.jobId && !it.terminal })
            val output = entry.output!!.copy(quarantine = entry.staging)
            val run =
                OwnSyncRun(
                    ownSyncUuid(),
                    previous.jobId,
                    approved = true,
                    mirrorApproved = true,
                    restoration = true,
                    plan =
                        listOf(
                            OwnSyncPlanEntry(
                                ownSyncUuid(),
                                OwnSyncAction.Restore,
                                output = output,
                                path = output.path,
                            )
                        ),
                )
            store.create(run)
            schedule(run.id)
            run.id
        }
}
