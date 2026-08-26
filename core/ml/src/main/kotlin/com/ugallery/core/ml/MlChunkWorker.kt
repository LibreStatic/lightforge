package com.ugallery.core.ml

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration

class MlChunkWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val task = inputData.getString(Input.Task)?.let { runCatching { MlTaskType.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val mode = inputData.getString(Input.Mode)?.let { runCatching { MlRunMode.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val engine = MlRuntimeRegistry.engine(task) ?: return Result.retry()
        val policy = MlWorkPolicy.forMode(mode)
        val runner = MlChunkRunner(
            MlStateStore(applicationContext),
            MlExecutionController(
                AndroidThermalStatusProvider(applicationContext),
                AndroidFullAnalysisEligibility(applicationContext),
            ),
        )
        return when (val result = runner.run(engine, policy)) {
            is MlRunnerResult.Continue -> {
                setProgress(workDataOf(Output.Completed to result.checkpoint.completedItems))
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    uniqueName(task),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request(task, mode),
                )
                Result.success()
            }
            is MlRunnerResult.Finished -> {
                setProgress(workDataOf(Output.Completed to result.checkpoint.completedItems))
                Result.success(workDataOf(Output.Completed to result.checkpoint.completedItems))
            }
            is MlRunnerResult.Retry -> {
                Log.w(LogTag, "Backing off ${task.name}: ${result.reason}")
                Result.retry()
            }
            MlRunnerResult.Stopped -> Result.success()
        }
    }

    companion object {
        private const val LogTag = "UGalleryMl"
        internal fun uniqueName(task: MlTaskType) = "ugallery-ml-${task.name}"
        internal fun request(task: MlTaskType, mode: MlRunMode): OneTimeWorkRequest {
            val policy = MlWorkPolicy.forMode(mode)
            val request = OneTimeWorkRequestBuilder<MlChunkWorker>()
                .setInputData(input(task, mode))
                .setConstraints(policy.constraints)
                .addTag(uniqueName(task))
            // Android's JobScheduler rejects backoff criteria for idle-mode jobs.
            // Keep this guard if a future background-only mode adds that constraint.
            if (!policy.constraints.requiresDeviceIdle()) {
                request.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            }
            return request.build()
        }

        internal fun input(task: MlTaskType, mode: MlRunMode): Data =
            workDataOf(Input.Task to task.name, Input.Mode to mode.name)
    }

    private object Input { const val Task = "task"; const val Mode = "mode" }
    private object Output { const val Completed = "completed" }
}
