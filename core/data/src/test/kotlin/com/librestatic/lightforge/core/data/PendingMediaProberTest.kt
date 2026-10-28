package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.PendingProbeRow
import com.librestatic.lightforge.core.database.ProbeResultRow
import com.librestatic.lightforge.core.mediastore.MediaFileProbe
import com.librestatic.lightforge.core.mediastore.ProbedDetails
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class PendingMediaProberTest {
    @Test
    fun probesNewestFirstAppliesResultsAndClearsTheFlag() = runTest {
        val store = FakeProbeStore(row(1, sort = 10), row(2, sort = 30), row(3, sort = 20))
        val probe = RecordingProbe { ProbedDetails(900, 640, 480, true) }
        val progress = mutableListOf<Pair<Int, Int>>()

        PendingMediaProber(store, probe, batchSize = 2).run { done, remaining -> progress += done to remaining }

        assertEquals(setOf(2L, 3L), probe.order.take(2).toSet())
        assertEquals(1L, probe.order[2])
        assertEquals(3, store.applied.size)
        assertEquals(ProbeResultRow("external_primary", 2, 1, 900, 640, 480, true), store.applied.first { it.mediaStoreId == 2L })
        assertTrue(store.pending.isEmpty())
        assertEquals(listOf(2 to 1, 3 to 0), progress)
    }

    @Test
    fun aFailedProbeStillClearsTheFlagWithoutInventingValues() = runTest {
        val store = FakeProbeStore(row(1))
        val probe = RecordingProbe { throw java.io.IOException("unparseable") }

        PendingMediaProber(store, probe).run()

        assertEquals(ProbeResultRow("external_primary", 1, 1, 0, 0, 0, false), store.applied.single())
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun aRowRewrittenMeanwhileIsNotClobberedAndTheRunDoesNotSpin() = runTest {
        val store = FakeProbeStore(row(1)).apply { rewriteBeforeApply = true }
        PendingMediaProber(store, RecordingProbe { ProbedDetails(1, 1, 1, false) }).run()
        assertEquals(1, store.batches)
        assertEquals(1, store.pending.size)
    }

    @Test
    fun permissionLossKeepsTheFlagAndStopsTheRun() = runTest {
        val store = FakeProbeStore(row(1, sort = 20), row(2, sort = 10))
        val probe = RecordingProbe { key -> if (key == 2L) throw SecurityException("revoked") else ProbedDetails(5, 1, 1, false) }

        PendingMediaProber(store, probe, batchSize = 2).run()

        assertEquals(listOf(1L), store.applied.map { it.mediaStoreId })
        assertEquals(listOf(2L), store.pending.map { it.mediaStoreId })
        assertEquals(1, store.batches)
    }

    @Test
    fun cancellationStopsTheRunBetweenBatches() = runBlocking {
        val store = FakeProbeStore(row(1, sort = 3), row(2, sort = 2), row(3, sort = 1))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = RecordingProbe { started.countDown(); release.await(); ProbedDetails(1, 1, 1, false) }
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            PendingMediaProber(store, probe, batchSize = 1, parallelism = 1).run()
        }
        started.await()
        val cancelling = async(Dispatchers.Default) { job.cancelAndJoin() }
        release.countDown()
        cancelling.await()
        assertTrue(store.pending.isNotEmpty())
    }

    @Test
    fun aSecondRunWhileOneIsActiveReturnsImmediately() = runTest {
        val store = FakeProbeStore(row(1))
        val prober = PendingMediaProber(store, RecordingProbe { ProbedDetails(1, 1, 1, false) })
        prober.run()
        prober.run()
        assertEquals(1, store.applied.size)
    }

    private class RecordingProbe(private val body: (Long) -> ProbedDetails) : MediaFileProbe {
        val order = CopyOnWriteArrayList<Long>()
        override fun probe(
            key: com.librestatic.lightforge.core.model.MediaKey,
            kind: com.librestatic.lightforge.core.model.MediaKind,
            mimeType: String?, dateModifiedSeconds: Long, durationMillis: Long, width: Int, height: Int,
        ): ProbedDetails {
            order += key.mediaStoreId
            return body(key.mediaStoreId)
        }
    }

    private class FakeProbeStore(vararg rows: Pair<PendingProbeRow, Long>) : PendingProbeStore {
        val pending = rows.sortedWith(compareByDescending<Pair<PendingProbeRow, Long>> { it.second }
            .thenByDescending { it.first.mediaStoreId }).map { it.first }.toMutableList()
        val applied = mutableListOf<ProbeResultRow>()
        var batches = 0
        var rewriteBeforeApply = false

        override suspend fun pendingProbeRows(limit: Int) = pending.take(limit)
        override suspend fun pendingProbeCount() = pending.size
        override suspend fun applyProbeResults(results: List<ProbeResultRow>): Int {
            batches++
            if (rewriteBeforeApply) return 0 // generation guard: the row was rewritten, nothing matches
            applied += results
            val ids = results.map { it.mediaStoreId }.toSet()
            pending.removeAll { it.mediaStoreId in ids }
            return results.size
        }
    }

    private companion object {
        fun row(id: Long, sort: Long = id) = PendingProbeRow(
            "external_primary", id, 3, "video/x-flv", 1, 1, 0, 0, 0,
        ) to sort
    }
}
