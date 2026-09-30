package com.librestatic.lightforge.feature.semanticsearch

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.librestatic.lightforge.core.ml.UserHardwareWorkloadGate

/** Suspends only semantic embedding work; model downloads remain unaffected. */
object SemanticAnalysisPriority {
    fun suspendForUserWork(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE)
        val ids = setOfNotNull(
            preferences.getString("pending_index", null),
            preferences.getString("active_index", null),
        )
        val work = WorkManager.getInstance(appContext)
        ids.forEach { work.cancelUniqueWork(SemanticIndexWorker.uniqueName(it)) }
    }

    fun resumeAfterUserWork(context: Context) {
        if (UserHardwareWorkloadGate.isActive()) return
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("enabled", false)) return
        val pendingModel = preferences.getString("pending_model", null)
        val pendingIndex = preferences.getString("pending_index", null)
        val modelId = pendingModel ?: preferences.getString("active_model", null) ?: return
        val indexId = pendingIndex ?: preferences.getString("active_index", null) ?: return
        val mode = if (pendingIndex != null) SemanticIndexMode.FullLibrary else SemanticIndexMode.Incremental
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            SemanticIndexWorker.uniqueName(indexId),
            ExistingWorkPolicy.REPLACE,
            SemanticIndexWorker.request(modelId, indexId, mode),
        )
    }
}
