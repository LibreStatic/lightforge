package com.ugallery.core.ml

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlSchedulerPolicyDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var state: MlStateStore

    @Before fun setUp() {
        state = MlStateStore(context)
        MlTaskType.entries.forEach {
            state.clear(it); state.setConsent(it, false); state.setPaused(it, false)
        }
        MlRuntimeRegistry.clear()
    }

    @After fun tearDown() = MlRuntimeRegistry.clear()

    @Test fun workPoliciesNeverRequireNetworkAndFullScanAddsChargingAndIdle() {
        val recent = MlWorkPolicy.forMode(MlRunMode.Recent)
        val full = MlWorkPolicy.forMode(MlRunMode.FullLibrary)

        assertEquals(50, recent.chunkSize)
        assertEquals(100, full.chunkSize)
        assertFalse(recent.constraints.requiresCharging())
        assertFalse(recent.constraints.requiresDeviceIdle())
        assertTrue(full.constraints.requiresCharging())
        assertTrue(full.constraints.requiresDeviceIdle())
        assertTrue(full.constraints.requiresBatteryNotLow())
        assertTrue(full.constraints.requiresStorageNotLow())
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, full.constraints.requiredNetworkType)
    }

    @Test fun moderateThermalStatusBacksOffBeforeOpeningMedia() = runBlocking {
        val engine = FakeEngine()
        state.setConsent(engine.task, true)
        val runner = MlChunkRunner(
            state,
            MlExecutionController(ThermalStatusProvider { PowerManager.THERMAL_STATUS_MODERATE }),
        )

        assertEquals(MlRunnerResult.Retry("thermal"), runner.run(engine, MlWorkPolicy.forMode(MlRunMode.Recent)))
        assertEquals(0, engine.processCalls)
    }

    @Test fun permissionLossPurgesDerivedDataAndCheckpoint() = runBlocking {
        val engine = FakeEngine(permission = false)
        state.setConsent(engine.task, true)
        state.write(MlCheckpoint(engine.task, engine.modelVersion, MediaKey("external_primary", 5), 6, MlCheckpoint.Status.Ready))
        val runner = runner()

        assertEquals(MlRunnerResult.Stopped, runner.run(engine, MlWorkPolicy.forMode(MlRunMode.Recent)))
        assertEquals(1, engine.purgeCalls)
        assertNull(state.checkpoint(engine.task))
    }

    @Test fun checkpointAdvancesOnlyAfterIdempotentChunkAndResumes() = runBlocking {
        val engine = FakeEngine(
            outcomes = ArrayDeque(
                listOf(
                    MlChunkOutcome.More(MediaKey("external_primary", 49), 50),
                    MlChunkOutcome.Complete(20),
                ),
            ),
        )
        state.setConsent(engine.task, true)

        val first = runner().run(engine, MlWorkPolicy.forMode(MlRunMode.Recent))
        assertTrue(first is MlRunnerResult.Continue)
        assertEquals(50L, state.checkpoint(engine.task)?.completedItems)
        val second = runner().run(engine, MlWorkPolicy.forMode(MlRunMode.Recent))
        assertTrue(second is MlRunnerResult.Finished)
        assertEquals(70L, state.checkpoint(engine.task)?.completedItems)
        assertEquals(listOf(null, MediaKey("external_primary", 49)), engine.receivedCursors)
    }

    @Test fun modelVersionChangePurgesOldOutputsBeforeRestart() = runBlocking {
        val engine = FakeEngine(modelVersion = "v2", outcomes = ArrayDeque(listOf(MlChunkOutcome.Complete(0))))
        state.setConsent(engine.task, true)
        state.write(MlCheckpoint(engine.task, "v1", MediaKey("external_primary", 9), 10, MlCheckpoint.Status.Ready))

        runner().run(engine, MlWorkPolicy.forMode(MlRunMode.Recent))

        assertEquals(1, engine.purgeCalls)
        assertEquals(listOf(null), engine.receivedCursors)
        assertEquals("v2", state.checkpoint(engine.task)?.modelVersion)
    }

    @Test fun workerRetriesWhenProcessRuntimeHasNotRegisteredItsEngineYet() = runBlocking {
        val worker = TestListenableWorkerBuilder<MlChunkWorker>(context)
            .setInputData(MlChunkWorker.input(MlTaskType.Ocr, MlRunMode.Recent))
            .build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    private fun runner() = MlChunkRunner(
        state,
        MlExecutionController(ThermalStatusProvider { PowerManager.THERMAL_STATUS_NONE }),
    )

    private class FakeEngine(
        override val task: MlTaskType = MlTaskType.Ocr,
        override val modelVersion: String = "v1",
        private val permission: Boolean = true,
        private val outcomes: ArrayDeque<MlChunkOutcome> = ArrayDeque(listOf(MlChunkOutcome.Complete(0))),
    ) : MlTaskEngine {
        var processCalls = 0
        var purgeCalls = 0
        val receivedCursors = mutableListOf<MediaKey?>()
        override fun hasCurrentPermission() = permission
        override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome {
            processCalls++
            receivedCursors += afterExclusive
            return outcomes.removeFirst()
        }
        override suspend fun purgeDerivedData() { purgeCalls++ }
    }
}
