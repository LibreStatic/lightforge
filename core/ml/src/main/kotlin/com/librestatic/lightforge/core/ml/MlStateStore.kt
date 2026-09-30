package com.librestatic.lightforge.core.ml

import android.content.Context
import com.librestatic.lightforge.core.model.MediaKey

class MlStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ml-task-state",
        Context.MODE_PRIVATE,
    )

    fun checkpoint(task: MlTaskType): MlCheckpoint? {
        val prefix = task.name
        val version = preferences.getString("$prefix.version", null) ?: return null
        val volume = preferences.getString("$prefix.volume", null)
        val id = preferences.getLong("$prefix.id", Long.MIN_VALUE)
        return MlCheckpoint(
            task = task,
            modelVersion = version,
            afterExclusive = if (volume == null || id == Long.MIN_VALUE) null else MediaKey(volume, id),
            completedItems = preferences.getLong("$prefix.completed", 0),
            status = runCatching {
                MlCheckpoint.Status.valueOf(preferences.getString("$prefix.status", "Ready")!!)
            }.getOrDefault(MlCheckpoint.Status.Ready),
        )
    }

    fun write(checkpoint: MlCheckpoint) {
        val prefix = checkpoint.task.name
        preferences.edit()
            .putString("$prefix.version", checkpoint.modelVersion)
            .putString("$prefix.volume", checkpoint.afterExclusive?.volumeName)
            .putLong("$prefix.id", checkpoint.afterExclusive?.mediaStoreId ?: Long.MIN_VALUE)
            .putLong("$prefix.completed", checkpoint.completedItems)
            .putString("$prefix.status", checkpoint.status.name)
            .commit()
    }

    fun clear(task: MlTaskType) {
        val prefix = task.name
        preferences.edit().also { editor ->
            listOf("version", "volume", "id", "completed", "status", "requestedMode", "waitReason").forEach {
                editor.remove("$prefix.$it")
            }
        }.commit()
    }

    fun isConsentEnabled(task: MlTaskType): Boolean = preferences.getBoolean("${task.name}.consent", false)
    fun setConsent(task: MlTaskType, enabled: Boolean) {
        preferences.edit().putBoolean("${task.name}.consent", enabled).commit()
    }

    fun isPaused(task: MlTaskType): Boolean = preferences.getBoolean("${task.name}.paused", false)
    fun setPaused(task: MlTaskType, paused: Boolean) {
        preferences.edit().putBoolean("${task.name}.paused", paused).commit()
        checkpoint(task)?.let {
            write(it.copy(status = if (paused) MlCheckpoint.Status.Paused else MlCheckpoint.Status.Ready))
        }
    }

    /** Why the last run backed off instead of working; null once a run proceeds or stops. */
    fun waitReason(task: MlTaskType): MlBackoffWait? = preferences
        .getString("${task.name}.waitReason", null)
        ?.let { runCatching { MlBackoffWait.valueOf(it) }.getOrNull() }

    fun setWaitReason(task: MlTaskType, wait: MlBackoffWait?) {
        if (waitReason(task) == wait) return
        preferences.edit().also { editor ->
            if (wait == null) editor.remove("${task.name}.waitReason")
            else editor.putString("${task.name}.waitReason", wait.name)
        }.commit()
    }

    fun requestedMode(task: MlTaskType): MlRunMode? = preferences
        .getString("${task.name}.requestedMode", null)
        ?.let { runCatching { MlRunMode.valueOf(it) }.getOrNull() }

    fun setRequestedMode(task: MlTaskType, mode: MlRunMode?) {
        preferences.edit().also { editor ->
            if (mode == null) editor.remove("${task.name}.requestedMode")
            else editor.putString("${task.name}.requestedMode", mode.name)
        }.commit()
    }
}
