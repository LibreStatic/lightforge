package com.ugallery.core.ml

import android.util.Log
import kotlinx.coroutines.CancellationException

sealed interface MlRunnerResult {
    data class Continue(val checkpoint: MlCheckpoint) : MlRunnerResult
    data class Finished(val checkpoint: MlCheckpoint) : MlRunnerResult
    data class Retry(val reason: String) : MlRunnerResult
    data object Stopped : MlRunnerResult
}

class MlChunkRunner(
    private val state: MlStateStore,
    private val controller: MlExecutionController,
) {
    suspend fun run(engine: MlTaskEngine, policy: MlWorkPolicy): MlRunnerResult {
        when (controller.decide(
            state.isConsentEnabled(engine.task),
            state.isPaused(engine.task),
            engine.hasCurrentPermission(),
        )) {
            MlExecutionDecision.ConsentRequired,
            MlExecutionDecision.Paused,
            -> {
                state.setRequestedMode(engine.task, null)
                return MlRunnerResult.Stopped
            }
            MlExecutionDecision.ThermalBackoff -> return MlRunnerResult.Retry("thermal")
            MlExecutionDecision.PermissionLost -> {
                engine.purgeDerivedData()
                state.clear(engine.task)
                return MlRunnerResult.Stopped
            }
            MlExecutionDecision.Run -> Unit
        }
        var checkpoint = state.checkpoint(engine.task)
        if (checkpoint != null && checkpoint.modelVersion != engine.modelVersion) {
            engine.purgeDerivedData()
            state.clear(engine.task)
            checkpoint = null
        }
        val current = checkpoint ?: MlCheckpoint(
            engine.task, engine.modelVersion, null, 0, MlCheckpoint.Status.Ready,
        )
        state.write(current.copy(status = MlCheckpoint.Status.Running))
        val outcome = try {
            engine.process(current.afterExclusive, policy.chunkSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.e("UGalleryMl", "${engine.task.name} batch failed", failure)
            state.write(current.copy(status = MlCheckpoint.Status.Ready))
            val root = generateSequence(failure) { it.cause }.last()
            return MlRunnerResult.Retry(
                "${root.javaClass.simpleName}: ${root.message.orEmpty()}".take(100),
            )
        }
        return when (outcome) {
            is MlChunkOutcome.More -> {
                require(outcome.processedItems in 1..policy.chunkSize)
                require(outcome.nextAfterExclusive != current.afterExclusive)
                val updated = current.copy(
                    afterExclusive = outcome.nextAfterExclusive,
                    completedItems = current.completedItems + outcome.processedItems,
                    status = MlCheckpoint.Status.Ready,
                )
                state.write(updated)
                MlRunnerResult.Continue(updated)
            }
            is MlChunkOutcome.Complete -> {
                require(outcome.processedItems in 0..policy.chunkSize)
                val updated = current.copy(
                    completedItems = current.completedItems + outcome.processedItems,
                    status = MlCheckpoint.Status.Complete,
                )
                state.write(updated)
                state.setRequestedMode(engine.task, null)
                MlRunnerResult.Finished(updated)
            }
            MlChunkOutcome.PermissionLost -> {
                engine.purgeDerivedData()
                state.clear(engine.task)
                MlRunnerResult.Stopped
            }
            is MlChunkOutcome.Retry -> MlRunnerResult.Retry(outcome.reason.take(100))
        }
    }
}
