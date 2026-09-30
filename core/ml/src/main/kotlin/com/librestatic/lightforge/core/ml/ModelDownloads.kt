package com.librestatic.lightforge.core.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Why a queued model download is not transferring bytes right now. */
enum class ModelDownloadWait { Network, WiFi, Battery }

/** Thrown from inside a transfer when a policy gate closes; partial bytes are kept and the download resumes later. */
class ModelDownloadPausedException(val reason: ModelDownloadWait) : RuntimeException("Model download paused: $reason")

/**
 * Shared network and battery policy for model downloads and updates.
 *
 * Downloads wait for unmetered Wi-Fi unless the user allows mobile data, and only transfer while the device
 * is charging or above the local-analysis battery threshold. Workers must stay resumable: WorkManager stops
 * them when the network constraint breaks and [ModelDownloadGate.checkpoint] pauses them for the rest.
 */
object ModelDownloads {
    const val Tag = "model-download"
    private const val Preferences = "model_downloads"
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Re-applies the network constraint to queued downloads whenever the mobile-data preference changes,
     * and runs each feature's [updateChecks] so outdated installed models update under the same policy.
     */
    fun install(context: Context, updateChecks: List<suspend (Context) -> Unit> = emptyList()) {
        val app = context.applicationContext
        updateChecks.forEach { check -> installScope.launch { runCatching { check(app) } } }
        installScope.launch {
            GallerySettingsRepository(app).settings
                .map { it.analysis.modelDownloadsOnMobileData }
                .distinctUntilChanged()
                .drop(1)
                .collect { reschedule(app, it) }
        }
    }

    suspend fun mobileDataAllowed(context: Context): Boolean =
        GallerySettingsRepository(context.applicationContext).settings.first().analysis.modelDownloadsOnMobileData

    fun constraints(mobileDataAllowed: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (mobileDataAllowed) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build()

    /** Enqueues [worker] under [uniqueName] with the current policy and remembers it for later rescheduling. */
    suspend fun enqueue(
        context: Context,
        uniqueName: String,
        worker: Class<out ListenableWorker>,
        input: Data,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val app = context.applicationContext
        app.getSharedPreferences(Preferences, Context.MODE_PRIVATE).edit()
            .putString(uniqueName, JSONObject().put("worker", worker.name).put("input", encode(input)).toString())
            .apply()
        WorkManager.getInstance(app).enqueueUniqueWork(uniqueName, policy, request(worker, input, mobileDataAllowed(app)))
    }

    /** Forgets a download once it succeeded, failed permanently or was cancelled by the user. */
    fun finished(context: Context, uniqueName: String) {
        context.applicationContext.getSharedPreferences(Preferences, Context.MODE_PRIVATE).edit().remove(uniqueName).apply()
    }

    suspend fun reschedule(context: Context, mobileDataAllowed: Boolean) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
        val work = WorkManager.getInstance(app)
        for ((name, value) in preferences.all) {
            val pending = work.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }
            val record = runCatching { JSONObject(value as String) }.getOrNull()
            val worker = record?.let { runCatching { Class.forName(it.getString("worker")).asSubclass(ListenableWorker::class.java) }.getOrNull() }
            if (!pending || record == null || worker == null) { finished(app, name); continue }
            // REPLACE stops a running transfer; its partial bytes stay on disk and the new request resumes them.
            work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request(worker, decode(record.getJSONObject("input")), mobileDataAllowed))
        }
    }

    /** The first unmet condition for transferring model bytes now, or null when a download may run. */
    suspend fun currentWait(context: Context): ModelDownloadWait? = gate(context).currentWait()

    /** Captures the current preferences; a preference change reschedules the worker that owns the gate. */
    suspend fun gate(context: Context): ModelDownloadGate {
        val settings = GallerySettingsRepository(context.applicationContext).settings.first().analysis
        return ModelDownloadGate(context, settings.modelDownloadsOnMobileData, settings.fullAnalysisMinimumBatteryPercent)
    }

    private fun request(worker: Class<out ListenableWorker>, input: Data, mobileDataAllowed: Boolean) =
        OneTimeWorkRequest.Builder(worker)
            .setConstraints(constraints(mobileDataAllowed))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .setInputData(input)
            .addTag(Tag)
            .build()

    private fun encode(input: Data) = JSONObject().apply {
        input.keyValueMap.forEach { (key, value) ->
            when (value) {
                is String, is Boolean, is Int, is Long -> put(key, JSONObject().put("t", value::class.java.simpleName).put("v", value))
                else -> error("Unsupported model download input: $key")
            }
        }
    }

    private fun decode(json: JSONObject) = Data.Builder().apply {
        json.keys().forEach { key ->
            val entry = json.getJSONObject(key)
            when (entry.getString("t")) {
                "String" -> putString(key, entry.getString("v"))
                "Boolean" -> putBoolean(key, entry.getBoolean("v"))
                "Integer" -> putInt(key, entry.getInt("v"))
                "Long" -> putLong(key, entry.getLong("v"))
            }
        }
    }.build()
}

/** Network and battery policy for one transfer; [checkpoint] is cheap enough to call from a read loop. */
class ModelDownloadGate(
    context: Context,
    private val mobileDataAllowed: Boolean,
    private val minimumBatteryPercent: Int,
    private val intervalMillis: Long = 2_000L,
) {
    private val app = context.applicationContext
    private val battery = AndroidAnalysisBatteryStateProvider(app)
    private var nextCheck = 0L

    fun currentWait(): ModelDownloadWait? {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ModelDownloadWait.Network
        }
        if (!mobileDataAllowed && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return ModelDownloadWait.WiFi
        }
        val state = battery.current()
        // An unknown level (-1) must not block downloads forever on devices without a battery.
        if (!state.charging && state.percent in 0 until minimumBatteryPercent) return ModelDownloadWait.Battery
        return null
    }

    /** Throws [ModelDownloadPausedException] when a gate closed; checks at most every [intervalMillis]. */
    fun checkpoint() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextCheck) return
        nextCheck = now + intervalMillis
        currentWait()?.let { throw ModelDownloadPausedException(it) }
    }
}
