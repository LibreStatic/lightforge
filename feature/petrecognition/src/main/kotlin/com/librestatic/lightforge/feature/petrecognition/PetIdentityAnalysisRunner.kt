package com.librestatic.lightforge.feature.petrecognition

import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PetAnalysisProgress(
    val running: Boolean = false,
    val analyzed: Long = 0,
    val detected: Long = 0,
    val skipped: Long = 0,
    val failed: Boolean = false,
)

/**
 * Process-scoped owner of a pet analysis run, so leaving the pet screen does not cancel it.
 * The screen only observes [progress] and may cancel explicitly.
 */
object PetIdentityAnalysisRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableProgress = MutableStateFlow(PetAnalysisProgress())
    val progress: StateFlow<PetAnalysisProgress> = mutableProgress
    private var job: Job? = null
    private var signal: CancellationSignal? = null

    @Synchronized
    fun start(context: Context, repository: PetIdentityRepository) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val cancellation = CancellationSignal()
        signal = cancellation
        mutableProgress.value = PetAnalysisProgress(running = true)
        job = scope.launch {
            var failed = false
            try {
                PetIdentityAnalysis(appContext, repository, PetModelStore(appContext)).run(cancellation) { analyzed, detected, skipped ->
                    mutableProgress.update { it.copy(analyzed = analyzed, detected = detected, skipped = skipped) }
                }
            } catch (failure: Throwable) {
                if (!cancellation.isCanceled && failure !is CancellationException) {
                    Log.w("PetIdentityAnalysis", "Pet analysis failed", failure)
                    failed = true
                }
            } finally {
                mutableProgress.update { it.copy(running = false, failed = failed) }
            }
        }
    }

    @Synchronized
    fun cancel() {
        signal?.cancel()
        job?.cancel()
    }
}
