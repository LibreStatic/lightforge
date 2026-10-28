package com.librestatic.lightforge.feature.petrecognition

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.librestatic.lightforge.core.ml.AndroidFullAnalysisEligibility
import com.librestatic.lightforge.core.ml.AndroidThermalStatusProvider
import com.librestatic.lightforge.core.ml.MlBackoffWait
import com.librestatic.lightforge.core.ml.MlExecutionController
import com.librestatic.lightforge.core.ml.MlExecutionDecision
import com.librestatic.lightforge.core.ml.MlRunMode
import com.librestatic.lightforge.core.ml.MlScheduler
import com.librestatic.lightforge.core.ml.MlTaskType
import com.librestatic.lightforge.core.ml.MlWorkPolicy
import com.librestatic.lightforge.core.ml.requiringCharging
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** The app supplies the Room-backed repository; a worker has no ViewModel to borrow it from. */
object PetIdentityRuntime {
    @Volatile private var factory: ((Context) -> PetIdentityRepository)? = null
    fun install(create: (Context) -> PetIdentityRepository) { factory = create }
    internal fun repository(context: Context): PetIdentityRepository? = factory?.invoke(context.applicationContext)
}

/** Why a pet analysis is being scheduled; decides whether it may replace a waiting request. */
enum class PetAnalysisTrigger {
    /** Library change or app start: keeps whatever is already queued. */
    Background,
    /** The species pass finished or the user turned analysis on: replaces a waiting (not running) request. */
    Kick,
    /** The user tapped "Analyze local photos": expedited, and does not wait for the species pass. */
    UserRequest,
}

/**
 * Background individual-pet analysis. It runs the resumable [PetIdentityAnalysis] loop under the same
 * policy as full-library ML (charging, or app open above the battery threshold; thermal pause; user
 * work first) and never creates identities: groups are only suggested for the user's review.
 * It does nothing unless pet analysis is enabled and the verified models are installed.
 */
class PetIdentityAnalysisWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private class Backoff(val wait: MlBackoffWait) : Exception()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Runtime not installed yet (worker started before Application.onCreate): retry, never drop the backlog.
        val repository = PetIdentityRuntime.repository(applicationContext) ?: return@withContext Result.retry()
        val summary = repository.currentSummary()
        val models = PetModelStore(applicationContext)
        if (!summary.enabled || summary.modelFingerprint != PetModelCatalog.Fingerprint || !models.installed()) return@withContext Result.success()
        val forced = inputData.getBoolean(KeyForce, false)
        // Species labels narrow the sources a lot, so let a running species pass finish first, and
        // poll in the background in case it completes while the app is closed.
        if (!forced && MlScheduler(applicationContext).controlState(MlTaskType.ImageLabels).let { it.consentGranted && it.requested }) {
            enqueueNext(null, forced = false, delay = SpeciesPassWait)
            return@withContext Result.success()
        }
        val controller = MlExecutionController(AndroidThermalStatusProvider(applicationContext), AndroidFullAnalysisEligibility(applicationContext))
        val signal = CancellationSignal()
        // WorkManager stops the worker when constraints are lost; the signal aborts a running inference.
        val stop = coroutineContext.job.invokeOnCompletion { signal.cancel() }
        var analyzed = 0L; var detected = 0L; var skipped = 0L
        try {
            PetIdentityAnalysis(applicationContext, repository, models).run(
                signal,
                gate = {
                    when (controller.decide(consent = true, paused = false, permission = true, mode = MlRunMode.FullLibrary)) {
                        MlExecutionDecision.Run -> Unit
                        MlExecutionDecision.PowerBackoff -> throw Backoff(MlBackoffWait.Charging)
                        else -> throw Backoff(MlBackoffWait.Thermal)
                    }
                },
            ) { a, d, s ->
                analyzed = a; detected = d; skipped = s
                setProgressAsync(workDataOf(KeyAnalyzed to a, KeyDetected to d, KeySkipped to s))
            }
            // Photos added during the run were not in its pages; go around once more if it made progress.
            if (analyzed + skipped > 0 && repository.progress.first().remaining > 0) enqueueNext(null, forced = false)
            Result.success(workDataOf(KeyAnalyzed to analyzed, KeyDetected to detected, KeySkipped to skipped))
        } catch (backoff: Backoff) {
            // A fresh request restarts runAttemptCount, so waiting never counts toward giving up.
            enqueueNext(backoff.wait, forced)
            Result.success(workDataOf(KeyAnalyzed to analyzed, KeyDetected to detected, KeySkipped to skipped))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: OperationCanceledException) {
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < MaxAttempts) Result.retry() else Result.failure()
        } finally {
            stop.dispose()
        }
    }

    private fun enqueueNext(wait: MlBackoffWait?, forced: Boolean, delay: Duration? = null) {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(UniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, request(wait, forced, expedited = false, delay = delay))
    }

    /** Expedited work needs this before API 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, applicationContext.getString(R.string.pet_analysis_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, Channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.pet_analysis_notification_title))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UniqueName = "lightforge-pet-identity-analysis"
        const val KeyAnalyzed = "analyzed"
        const val KeyDetected = "detected"
        const val KeySkipped = "skipped"
        private const val KeyForce = "force"
        private const val Channel = "pet-identity-analysis"
        private const val NotificationId = 0x7065
        private const val MaxAttempts = 3
        private val ThermalWait = Duration.ofMinutes(2)
        private val SpeciesPassWait = Duration.ofMinutes(10)

        internal fun request(wait: MlBackoffWait?, forced: Boolean, expedited: Boolean, delay: Duration? = null): OneTimeWorkRequest {
            val constraints = MlWorkPolicy.forMode(MlRunMode.FullLibrary).constraints
            val builder = OneTimeWorkRequestBuilder<PetIdentityAnalysisWorker>()
                .setInputData(Data.Builder().putBoolean(KeyForce, forced).build())
                .setConstraints(if (wait == MlBackoffWait.Charging) constraints.requiringCharging() else constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            val initialDelay = delay ?: ThermalWait.takeIf { wait == MlBackoffWait.Thermal }
            if (initialDelay != null) builder.setInitialDelay(initialDelay)
            if (expedited && initialDelay == null) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            return builder.build()
        }

        /** Safe to call often: a running pass is never interrupted and [PetAnalysisTrigger.Background] keeps what is queued. */
        suspend fun enqueue(context: Context, trigger: PetAnalysisTrigger = PetAnalysisTrigger.Background) {
            val manager = WorkManager.getInstance(context.applicationContext)
            val running = manager.getWorkInfosForUniqueWorkFlow(UniqueName).first().any { it.state == WorkInfo.State.RUNNING }
            if (running) return
            val request = request(null, forced = trigger == PetAnalysisTrigger.UserRequest, expedited = trigger == PetAnalysisTrigger.UserRequest)
            manager.enqueueUniqueWork(UniqueName, if (trigger == PetAnalysisTrigger.Background) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancels a pass in progress or waiting; later triggers may start a new one. */
        fun cancel(context: Context) { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UniqueName) }
    }
}
