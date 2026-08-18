package com.ugallery.core.ml

import com.ugallery.core.model.MediaKey

enum class MlTaskType { ImageLabels, Ocr, ExactDuplicates, Similarity, FaceDetection }
enum class MlRunMode { Recent, FullLibrary }

data class MlCheckpoint(
    val task: MlTaskType,
    val modelVersion: String,
    val afterExclusive: MediaKey?,
    val completedItems: Long,
    val status: Status,
) {
    enum class Status { Ready, Running, Paused, Complete }
}

sealed interface MlChunkOutcome {
    data class More(val nextAfterExclusive: MediaKey, val processedItems: Int) : MlChunkOutcome
    data class Complete(val processedItems: Int) : MlChunkOutcome
    data object PermissionLost : MlChunkOutcome
    data class Retry(val reason: String) : MlChunkOutcome
}

interface MlTaskEngine {
    val task: MlTaskType
    val modelVersion: String
    fun hasCurrentPermission(): Boolean
    suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome
    suspend fun purgeDerivedData()
}

object MlRuntimeRegistry {
    private val engines = java.util.concurrent.ConcurrentHashMap<MlTaskType, MlTaskEngine>()
    fun register(engine: MlTaskEngine) { engines[engine.task] = engine }
    fun unregister(task: MlTaskType) { engines.remove(task) }
    fun engine(task: MlTaskType): MlTaskEngine? = engines[task]
    fun clear() = engines.clear()
}
