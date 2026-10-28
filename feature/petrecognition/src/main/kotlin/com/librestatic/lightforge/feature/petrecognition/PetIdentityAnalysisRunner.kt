package com.librestatic.lightforge.feature.petrecognition

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class PetAnalysisProgress(
    val running: Boolean = false,
    val analyzed: Long = 0,
    val detected: Long = 0,
    val skipped: Long = 0,
    val failed: Boolean = false,
    /** Waiting for its turn (power, heat, a delay) rather than working. */
    val queued: Boolean = false,
)

/**
 * Thin facade over [PetIdentityAnalysisWorker]: the work itself is owned by WorkManager, so it
 * survives leaving the pet screen and process death. The screen only observes and may cancel.
 */
object PetIdentityAnalysisRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Asks for an expedited pass now ("Analyze local photos"). */
    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch { PetIdentityAnalysisWorker.enqueue(appContext, PetAnalysisTrigger.UserRequest) }
    }

    fun cancel(context: Context) = PetIdentityAnalysisWorker.cancel(context)

    /** Run state and this run's counters, derived from WorkInfo. */
    fun progress(context: Context): Flow<PetAnalysisProgress> =
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(PetIdentityAnalysisWorker.UniqueName)
            .map { infos ->
                val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val counters = (active?.progress ?: infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }?.outputData)
                PetAnalysisProgress(
                    running = active != null,
                    analyzed = counters?.getLong(PetIdentityAnalysisWorker.KeyAnalyzed, 0) ?: 0,
                    detected = counters?.getLong(PetIdentityAnalysisWorker.KeyDetected, 0) ?: 0,
                    skipped = counters?.getLong(PetIdentityAnalysisWorker.KeySkipped, 0) ?: 0,
                    failed = infos.none { !it.state.isFinished } && infos.any { it.state == WorkInfo.State.FAILED },
                    queued = active == null && infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
                )
            }
            .distinctUntilChanged()

    /** Processed/total from Room merged with the worker's running state. */
    fun identityProgress(context: Context, repository: PetIdentityRepository): Flow<PetIdentityProgress> =
        combine(repository.progress, progress(context)) { counts, work -> counts.copy(running = work.running) }.distinctUntilChanged()
}
