package com.librestatic.lightforge.core.ml

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await

data class MlControlState(
    val consentGranted: Boolean,
    val paused: Boolean,
    val completedItems: Long,
    val status: MlCheckpoint.Status?,
    val requested: Boolean = false,
    val runMode: MlRunMode? = null,
    val activeTask: MlTaskType? = null,
    /** Set while requested work waits on an external condition (power, heat) rather than the user. */
    val waitReason: MlBackoffWait? = null,
)

class MlScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val state = MlStateStore(appContext)

    fun setConsent(task: MlTaskType, enabled: Boolean) {
        state.setConsent(task, enabled)
        if (!enabled) {
            workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task))
            state.setPaused(task, true)
            state.setRequestedMode(task, null)
        }
    }

    fun grantConsent(task: MlTaskType) = setConsent(task, true)

    suspend fun revokeConsentAndDelete(task: MlTaskType) = deleteDerivedData(task)

    fun enqueue(task: MlTaskType, mode: MlRunMode = MlRunMode.Recent): Boolean {
        if (!state.isConsentEnabled(task)) return false
        if (state.requestedMode(task) != null) return true
        state.setPaused(task, false)
        state.setRequestedMode(task, mode)
        if (UserHardwareWorkloadGate.isActive()) return true
        workManager.enqueueUniqueWork(
            MlChunkWorker.uniqueName(task),
            ExistingWorkPolicy.KEEP,
            MlChunkWorker.request(task, mode),
        )
        return true
    }

    fun pause(task: MlTaskType) {
        state.setPaused(task, true)
        state.setRequestedMode(task, null)
        workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task))
    }

    fun unpause(task: MlTaskType) {
        state.setPaused(task, false)
    }

    fun resume(task: MlTaskType, mode: MlRunMode = MlRunMode.Recent): Boolean = enqueue(task, mode)

    suspend fun restart(task: MlTaskType, mode: MlRunMode): Boolean {
        if (!state.isConsentEnabled(task)) return false
        workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task)).await()
        state.setRequestedMode(task, null)
        return enqueue(task, mode)
    }

    /**
     * Stops unplugged full-library work while preserving its durable request: the replacement
     * waits for a charger, so plugging in resumes it without reopening the app.
     */
    fun onAppBackgrounded() {
        LocalAnalysisForegroundState.setForeground(false)
        if (AndroidAnalysisBatteryStateProvider(appContext).current().charging) return
        MlTaskType.entries.filter { state.requestedMode(it) == MlRunMode.FullLibrary }.forEach {
            if (!state.isConsentEnabled(it) || state.isPaused(it)) return@forEach
            workManager.enqueueUniqueWork(
                MlChunkWorker.uniqueName(it),
                ExistingWorkPolicy.REPLACE,
                MlChunkWorker.request(it, MlRunMode.FullLibrary, MlBackoffWait.Charging),
            )
        }
    }

    /** Replaces delayed retries so an eligible foreground pass resumes immediately. */
    fun onAppForegrounded() {
        LocalAnalysisForegroundState.setForeground(true)
        if (UserHardwareWorkloadGate.isActive()) return
        resumeRequestedWork()
    }

    /** Stops heavy analysis without changing consent, manual pause, checkpoints, or requested modes. */
    fun suspendForUserWork() {
        MlTaskType.entries.forEach { task ->
            state.checkpoint(task)?.takeIf { it.status == MlCheckpoint.Status.Running }?.let {
                state.write(it.copy(status = MlCheckpoint.Status.Ready))
            }
            workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task))
        }
    }

    fun resumeAfterUserWork() {
        if (UserHardwareWorkloadGate.isActive()) return
        resumeRequestedWork()
    }

    private fun resumeRequestedWork() {
        MlTaskType.entries.forEach { task ->
            val mode = state.requestedMode(task) ?: return@forEach
            if (!state.isConsentEnabled(task) || state.isPaused(task)) return@forEach
            workManager.enqueueUniqueWork(
                MlChunkWorker.uniqueName(task),
                ExistingWorkPolicy.REPLACE,
                MlChunkWorker.request(task, mode),
            )
        }
    }

    suspend fun deleteDerivedData(task: MlTaskType) {
        workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task)).await()
        MlRuntimeRegistry.engine(task)?.purgeDerivedData()
        state.clear(task)
        state.setConsent(task, false)
        state.setPaused(task, false)
    }

    fun checkpoint(task: MlTaskType): MlCheckpoint? = state.checkpoint(task)
    fun hasConsent(task: MlTaskType): Boolean = state.isConsentEnabled(task)

    fun controlState(task: MlTaskType): MlControlState {
        val checkpoint = state.checkpoint(task)
        return MlControlState(
            consentGranted = state.isConsentEnabled(task),
            paused = state.isPaused(task),
            completedItems = checkpoint?.completedItems ?: 0,
            status = checkpoint?.status,
            requested = state.requestedMode(task) != null,
            runMode = state.requestedMode(task),
            activeTask = task,
            waitReason = waitReason(task),
        )
    }

    private fun waitReason(task: MlTaskType): MlBackoffWait? {
        if (state.requestedMode(task) == null || state.checkpoint(task)?.status == MlCheckpoint.Status.Running) return null
        state.waitReason(task)?.let { return it }
        // WorkManager holds battery-not-low work before the worker can record why it is waiting.
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return if (battery?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) == true) MlBackoffWait.Charging else null
    }
}
