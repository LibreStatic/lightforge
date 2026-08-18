package com.ugallery.core.ml

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await

data class MlControlState(
    val consentGranted: Boolean,
    val paused: Boolean,
    val completedItems: Long,
    val status: MlCheckpoint.Status?,
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
        }
    }

    fun grantConsent(task: MlTaskType) = setConsent(task, true)

    suspend fun revokeConsentAndDelete(task: MlTaskType) = deleteDerivedData(task)

    fun enqueue(task: MlTaskType, mode: MlRunMode = MlRunMode.Recent): Boolean {
        if (!state.isConsentEnabled(task)) return false
        state.setPaused(task, false)
        workManager.enqueueUniqueWork(
            MlChunkWorker.uniqueName(task),
            ExistingWorkPolicy.KEEP,
            MlChunkWorker.request(task, mode),
        )
        return true
    }

    fun pause(task: MlTaskType) {
        state.setPaused(task, true)
        workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task))
    }

    fun resume(task: MlTaskType, mode: MlRunMode = MlRunMode.Recent): Boolean = enqueue(task, mode)

    suspend fun restart(task: MlTaskType, mode: MlRunMode): Boolean {
        if (!state.isConsentEnabled(task)) return false
        workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task)).await()
        return enqueue(task, mode)
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
        )
    }
}
