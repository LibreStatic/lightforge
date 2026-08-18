package com.ugallery.core.ml

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await

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

    suspend fun deleteDerivedData(task: MlTaskType) {
        workManager.cancelUniqueWork(MlChunkWorker.uniqueName(task)).await()
        MlRuntimeRegistry.engine(task)?.purgeDerivedData()
        state.clear(task)
        state.setConsent(task, false)
        state.setPaused(task, false)
    }

    fun checkpoint(task: MlTaskType): MlCheckpoint? = state.checkpoint(task)
    fun hasConsent(task: MlTaskType): Boolean = state.isConsentEnabled(task)
}
