package com.ugallery.feature.semanticsearch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.ml.AndroidAnalysisBatteryStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.Closeable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.withLock

enum class SemanticSelectionMode { Automatic, Manual }

data class SemanticModelItemState(
    val descriptor: SemanticModelDescriptor,
    val compatibility: SemanticModelCompatibility,
    val installed: Boolean,
    val active: Boolean,
    val downloading: Boolean,
    val downloadedBytes: Long = 0,
    val error: String? = null,
)

data class SemanticModelManagerState(
    val enabled: Boolean = false,
    val selectionMode: SemanticSelectionMode = SemanticSelectionMode.Automatic,
    val activeModelId: String? = null,
    val buildingModelId: String? = null,
    val recommendedModelId: String? = null,
    val indexError: String? = null,
    val models: List<SemanticModelItemState> = emptyList(),
)

class SemanticModelManager(
    context: Context,
    private val database: GalleryDatabase,
) : Closeable {
    private val appContext = context.applicationContext
    private val storage = SemanticModelStorage(appContext)
    private val work = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE)
    private val profile = SemanticModelSelector.profile(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(snapshot(loadWorkState = false))
    val state: StateFlow<SemanticModelManagerState> = mutableState

    init {
        SemanticModelCatalog.models.forEach { model ->
            scope.launch {
                work.getWorkInfosForUniqueWorkFlow(SemanticModelDownloadWorker.uniqueName(model.id)).collect {
                    mutableState.value = snapshot()
                    activateRecommendedIfReady()
                }
            }
        }
        scope.launch { recoverPendingIndex() }
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().also { editor ->
            editor.putBoolean(KeyEnabled, enabled)
            if (!enabled) editor.remove(KeyIndexError)
        }.apply()
        if (!enabled) cancelPendingIndex()
        if (enabled) activateRecommendedIfReady()
        refresh()
    }

    fun initializeEnabledDefault(localAnalysisAccepted: Boolean) {
        if (!preferences.contains(KeyEnabled)) {
            preferences.edit().putBoolean(KeyEnabled, localAnalysisAccepted).apply()
            refresh()
        }
    }

    fun useAutomaticSelection() {
        preferences.edit().putString(KeySelectionMode, SemanticSelectionMode.Automatic.name).apply()
        recommended()?.let { model ->
            if (storage.installed(model)) activate(model.id, keepAutomatic = true) else download(model.id, allowMetered = false)
        }
        refresh()
    }

    fun download(modelId: String, allowMetered: Boolean) {
        val model = requireModel(modelId)
        if (storage.installed(model)) return
        val request = OneTimeWorkRequestBuilder<SemanticModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
            .setInputData(workDataOf(
                SemanticModelDownloadWorker.KeyModelId to modelId,
                SemanticModelDownloadWorker.KeyWifiOnly to !allowMetered,
            ))
            .build()
        work.enqueueUniqueWork(SemanticModelDownloadWorker.uniqueName(modelId), ExistingWorkPolicy.KEEP, request)
        refresh()
    }

    fun cancelDownload(modelId: String) {
        work.cancelUniqueWork(SemanticModelDownloadWorker.uniqueName(modelId))
        refresh()
    }

    fun activate(modelId: String, allowUnsupported: Boolean = false, keepAutomatic: Boolean = false) {
        val model = requireModel(modelId)
        require(storage.installed(model)) { "Model is not installed" }
        val compatibility = SemanticModelSelector.compatibility(model, profile)
        require(compatibility != SemanticModelCompatibility.TechnicallyUnsupported) {
            "Model is technically incompatible"
        }
        // allowUnsupported means "allow a supported but non-recommended model". A true ABI/RAM
        // incompatibility is never activatable because it cannot execute safely.
        if (!allowUnsupported && !keepAutomatic) require(compatibility == SemanticModelCompatibility.Recommended) {
            "Model is supported but not recommended for this device"
        }
        val indexId = "${model.id}-${model.version}-${UUID.randomUUID()}"
        scope.launch {
            try {
                SemanticIndexCommitGate.mutex.withLock {
                    // Installation markers are status hints. Activation proves bytes and tensors.
                    LiteRtSemanticEmbeddingInference(appContext, InstalledSemanticModel(model, storage.directory(model))).use { it.embedText("a photo") }
                    cancelPendingIndex()
                    check(preferences.edit().putBoolean(KeyEnabled, true)
                        .putString(KeySelectionMode, if (keepAutomatic) SemanticSelectionMode.Automatic.name else SemanticSelectionMode.Manual.name)
                        .putString(KeyPendingModel, model.id).putString(KeyPendingIndex, indexId)
                        .remove(KeyIndexError).commit())
                    val now = System.currentTimeMillis()
                    database.semanticDao().upsertIndex(com.ugallery.core.database.SemanticIndexEntity(indexId, model.id, model.version, "building", 0, now, now))
                    work.enqueueUniqueWork(SemanticIndexWorker.uniqueName(indexId), ExistingWorkPolicy.REPLACE,
                        SemanticIndexWorker.request(modelId, indexId, SemanticIndexMode.FullLibrary))
                }
                monitorIndexCompletion(indexId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Throwable) { preferences.edit().putString(KeyIndexError, "model_validation_failed").apply() }
            refresh()
        }
    }

    fun deleteModel(modelId: String) {
        val model = requireModel(modelId)
        cancelDownload(modelId)
        scope.launch { SemanticIndexCommitGate.mutex.withLock {
            if (preferences.getString(KeyActiveModel, null) == modelId)
                preferences.getString(KeyActiveIndex, null)?.let { work.cancelUniqueWork(SemanticIndexWorker.uniqueName(it)) }
            if (preferences.getString(KeyPendingModel, null) == modelId) cancelPendingIndex()
            if (preferences.getString(KeyActiveModel, null) == modelId) {
                val activeIndex = preferences.getString(KeyActiveIndex, null)
                cancelPendingIndex()
                preferences.edit().remove(KeyActiveModel).remove(KeyActiveIndex).putBoolean(KeyEnabled, false).apply()
                if (activeIndex != null) database.semanticDao().deleteIndex(activeIndex)
            }
            database.semanticDao().deleteIndexesForModel(modelId)
            storage.delete(model)
            refresh()
        } }
    }

    fun deleteAllModels() {
        setEnabled(false)
        SemanticModelCatalog.models.forEach { cancelDownload(it.id) }
        scope.launch { SemanticIndexCommitGate.mutex.withLock {
            preferences.getString(KeyActiveIndex, null)?.let { work.cancelUniqueWork(SemanticIndexWorker.uniqueName(it)) }
            database.semanticDao().deleteAllIndexes()
            SemanticModelCatalog.models.forEach(storage::delete)
            preferences.edit().remove(KeyActiveModel).remove(KeyActiveIndex).apply()
            refresh()
        } }
    }

    fun activeModel(): InstalledSemanticModel? {
        val id = preferences.getString(KeyActiveModel, null) ?: return null
        return SemanticModelCatalog.models.firstOrNull { it.id == id }?.let(storage::installedModel)
    }

    fun isEnabled(): Boolean = preferences.getBoolean(KeyEnabled, false)

    fun activeIndexId(): String? = preferences.getString(KeyActiveIndex, null)

    fun scheduleActiveIndexUpdate() {
        if (!preferences.getBoolean(KeyEnabled, false)) return
        val modelId = preferences.getString(KeyActiveModel, null) ?: return
        val indexId = preferences.getString(KeyActiveIndex, null) ?: return
        val request = SemanticIndexWorker.request(
            modelId,
            indexId,
            SemanticIndexMode.Incremental,
        )
        work.enqueueUniqueWork(SemanticIndexWorker.uniqueName(indexId), ExistingWorkPolicy.KEEP, request)
    }

    fun onAppBackgrounded() {
        if (AndroidAnalysisBatteryStateProvider(appContext).current().charging) return
        preferences.getString(KeyPendingIndex, null)?.let { indexId ->
            work.cancelUniqueWork(SemanticIndexWorker.uniqueName(indexId))
        }
    }

    fun onAppForegrounded() {
        val modelId = preferences.getString(KeyPendingModel, null) ?: return
        val indexId = preferences.getString(KeyPendingIndex, null) ?: return
        work.enqueueUniqueWork(
            SemanticIndexWorker.uniqueName(indexId),
            ExistingWorkPolicy.REPLACE,
            SemanticIndexWorker.request(modelId, indexId, SemanticIndexMode.FullLibrary),
        )
        monitorIndexCompletion(indexId)
    }

    fun ensureAutomaticDownload(allowMetered: Boolean = false) {
        if (selectionMode() != SemanticSelectionMode.Automatic) return
        recommended()?.let { model ->
            if (storage.installed(model)) activateRecommendedIfReady()
            else download(model.id, allowMetered)
        }
    }

    private fun snapshot(loadWorkState: Boolean = true): SemanticModelManagerState {
        val active = preferences.getString(KeyActiveModel, null)
        val infos = if (loadWorkState) {
            SemanticModelCatalog.models.associateWith { model ->
                work.getWorkInfosForUniqueWork(SemanticModelDownloadWorker.uniqueName(model.id)).get().firstOrNull()
            }
        } else emptyMap()
        return SemanticModelManagerState(
            enabled = preferences.getBoolean(KeyEnabled, false),
            selectionMode = selectionMode(),
            activeModelId = active,
            buildingModelId = preferences.getString(KeyPendingModel, null),
            recommendedModelId = recommended()?.id,
            indexError = preferences.getString(KeyIndexError, null),
            models = SemanticModelCatalog.models.map { model ->
                val info = infos[model]
                SemanticModelItemState(
                    descriptor = model,
                    compatibility = SemanticModelSelector.compatibility(model, profile),
                    installed = storage.installed(model),
                    active = active == model.id,
                    downloading = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED,
                    downloadedBytes = info?.progress?.getLong(SemanticModelDownloadWorker.KeyDownloadedBytes, 0L) ?: 0L,
                    error = info?.takeIf { it.state == WorkInfo.State.FAILED && !storage.supersedesDownloadFailure(model, it.id.toString()) }?.outputData?.getString(SemanticModelDownloadWorker.KeyError),
                )
            },
        )
    }

    private fun refresh() { scope.launch { mutableState.value = snapshot() } }
    private fun monitorIndexCompletion(indexId: String) {
        scope.launch {
            work.getWorkInfosForUniqueWorkFlow(SemanticIndexWorker.uniqueName(indexId)).first { infos ->
                infos.isNotEmpty() && infos.all { it.state.isFinished }
            }
            mutableState.value = snapshot()
        }
    }
    private suspend fun recoverPendingIndex() {
        val modelId = preferences.getString(KeyPendingModel, null) ?: return
        val indexId = preferences.getString(KeyPendingIndex, null) ?: return
        val model = SemanticModelCatalog.models.firstOrNull { it.id == modelId }
        val index = database.semanticDao().index(indexId)
        if (model == null || !storage.installed(model) || index == null || index.status != "building") {
            preferences.edit().remove(KeyPendingModel).remove(KeyPendingIndex).apply()
            refresh()
            return
        }
        val request = SemanticIndexWorker.request(
            modelId,
            indexId,
            SemanticIndexMode.FullLibrary,
        )
        work.enqueueUniqueWork(SemanticIndexWorker.uniqueName(indexId), ExistingWorkPolicy.KEEP, request)
        monitorIndexCompletion(indexId)
    }
    private fun cancelPendingIndex() {
        val indexId = preferences.getString(KeyPendingIndex, null) ?: return
        work.cancelUniqueWork(SemanticIndexWorker.uniqueName(indexId))
        preferences.edit().remove(KeyPendingModel).remove(KeyPendingIndex).apply()
        scope.launch { SemanticIndexCommitGate.mutex.withLock { database.semanticDao().deleteIndex(indexId) }; refresh() }
    }
    private fun activateRecommendedIfReady() {
        if (!preferences.getBoolean(KeyEnabled, false) || selectionMode() != SemanticSelectionMode.Automatic) return
        if (preferences.getString(KeyActiveModel, null) != null || preferences.getString(KeyPendingModel, null) != null) return
        recommended()?.takeIf(storage::installed)?.let { activate(it.id, keepAutomatic = true) }
    }

    private fun selectionMode() = preferences.getString(KeySelectionMode, SemanticSelectionMode.Automatic.name)
        ?.let { runCatching { SemanticSelectionMode.valueOf(it) }.getOrNull() } ?: SemanticSelectionMode.Automatic
    private fun recommended() = SemanticModelSelector.recommended(SemanticModelCatalog.models, profile)
    private fun requireModel(id: String) = requireNotNull(SemanticModelCatalog.models.firstOrNull { it.id == id })

    override fun close() { scope.cancel() }

    private companion object {
        const val KeyEnabled = "enabled"
        const val KeySelectionMode = "selection_mode"
        const val KeyActiveModel = "active_model"
        const val KeyActiveIndex = "active_index"
        const val KeyPendingModel = "pending_model"
        const val KeyPendingIndex = "pending_index"
        const val KeyIndexError = "index_error"
    }
}
