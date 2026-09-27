package com.librestatic.lightforge.core.mediastore

import android.app.PendingIntent
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Coordinator boundary only: injected inert PendingIntent, never dispatches a real delete. */
class VerifiedMoveCoordinatorDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = MediaActionTarget(MediaKey("external_primary", 919191L), MediaKind.Image)
    private fun initial() = MediaActionReducer.start(MediaAction.MoveDelete(UUID.randomUUID().toString()), 1)
    private fun factory(events: MutableList<String>) = MediaStoreRequestFactory { _, _ ->
        synchronized(events) { events.add("request") }
        PendingIntent.getBroadcast(context, 0, Intent("com.librestatic.lightforge.TEST_INERT_MOVE").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    }
    @Test fun ordinaryEntryPointsRejectMoveBeforeSnapshotOrPersistenceChanges() {
        val initial = initial(); var writes = 0
        val c = MediaStoreActionCoordinator(context.contentResolver, initial, persist = { writes++ })
        assertTrue(runCatching { c.stageChunk(listOf(target)) }.isFailure)
        assertTrue(runCatching { c.retryCurrent() }.isFailure)
        assertTrue(runCatching { c.recreateCurrentRequest() }.isFailure)
        assertSame(initial, c.snapshot.value); assertEquals(0, writes)
    }
    @Test fun guardFailureNeverCreatesRequestAndRetryRevalidates() = runBlocking {
        val events = mutableListOf<String>(); var valid = false
        val c = MediaStoreActionCoordinator(context.contentResolver, initial(), factory(events), moveGuard = { _, _ ->
            events.add("guard"); check(valid) { "ChangedProof" }
        })
        assertTrue(runCatching { c.stageVerifiedMove(listOf(target)) }.isFailure)
        assertTrue(c.snapshot.value.phase is MediaActionPhase.RequestFailed)
        assertEquals(listOf("guard"), events)
        valid = true
        val launch = c.retryVerifiedMove()
        assertEquals(listOf("guard", "guard", "request"), events)
        assertEquals(2L, launch.requestId)
    }
    @Test fun recreateRunsFreshGuardAndMissingGuardFailsClosed() = runBlocking {
        val pending = MediaActionReducer.stage(initial(), listOf(target))
        val events = mutableListOf<String>()
        val denied = MediaStoreActionCoordinator(context.contentResolver, pending, factory(events))
        assertTrue(runCatching { denied.recreateVerifiedMove() }.isFailure)
        assertTrue(events.isEmpty())
        val recreated = MediaStoreActionCoordinator(context.contentResolver, pending, factory(events), moveGuard = { _, _ -> events.add("guard") })
        assertEquals(1L, recreated.recreateVerifiedMove().requestId)
        assertEquals(listOf("guard", "request"), events)
        val noGuard = MediaStoreActionCoordinator(context.contentResolver, pending, factory(events))
        assertTrue(noGuard.onSystemResult(1, true).phase is MediaActionPhase.RequestFailed)
    }
    @Test fun cancelledGuardPropagatesWithoutCreatingRequest() = runBlocking {
        val events = mutableListOf<String>()
        val c = MediaStoreActionCoordinator(context.contentResolver, initial(), factory(events), moveGuard = { _, _ -> throw CancellationException("fixture") })
        assertTrue(runCatching { c.stageVerifiedMove(listOf(target)) }.exceptionOrNull() is CancellationException)
        assertTrue(events.isEmpty())
        assertTrue(c.snapshot.value.phase is MediaActionPhase.AwaitingSystem)
    }
    @Test fun obsoleteAttemptDoesNotDeliverOrOverwriteCancelledSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val c = MediaStoreActionCoordinator(context.contentResolver, initial(), factory(events), moveGuard = { _, _ -> entered.complete(Unit); release.await() })
        val attempt = async { runCatching { c.stageVerifiedMove(listOf(target)) } }
        entered.await()
        val cancelled = c.onSystemResult(1, false)
        release.complete(Unit)
        assertTrue(attempt.await().isFailure)
        assertSame(cancelled, c.snapshot.value)
        assertTrue(events.isEmpty())
    }
}
