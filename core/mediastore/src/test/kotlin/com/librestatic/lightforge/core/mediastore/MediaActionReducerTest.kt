package com.librestatic.lightforge.core.mediastore

import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class MediaActionReducerTest {
    @Test
    fun `cancel preserves chunk and retry uses a fresh request id`() {
        val awaiting = MediaActionReducer.stage(
            MediaActionReducer.start(MediaAction.Trash(true), 100_000),
            listOf(target(1), target(2)),
        )
        val cancelled = MediaActionReducer.cancel(awaiting, 1)
        assertEquals(listOf(target(1), target(2)), (cancelled.phase as MediaActionPhase.Cancelled).targets)
        assertEquals(0L, cancelled.progress.accounted)
        val retried = MediaActionReducer.retry(cancelled)
        assertEquals(2L, (retried.phase as MediaActionPhase.AwaitingSystem).requestId)
    }

    @Test
    fun `partial verification is visible and stale callbacks do nothing`() {
        val awaiting = MediaActionReducer.stage(
            MediaActionReducer.start(MediaAction.Favorite(true), 3),
            listOf(target(1), target(2), target(3)),
        )
        assertEquals(awaiting, MediaActionReducer.cancel(awaiting, 99))
        val verified = MediaActionReducer.verified(
            awaiting, 1, succeeded = 2, failed = 1, VerifiedDisposition.Completed,
        )
        assertEquals(2L, verified.progress.completed)
        assertEquals(1L, verified.progress.failed)
        assertTrue(verified.phase is MediaActionPhase.Complete)
    }

    @Test
    fun `write approval is authorization and never false completion`() {
        val awaiting = MediaActionReducer.stage(
            MediaActionReducer.start(MediaAction.Write, 1),
            listOf(target(1)),
        )
        val approved = MediaActionReducer.verified(
            awaiting, 1, succeeded = 1, failed = 0, VerifiedDisposition.Authorized,
        )
        assertEquals(1L, approved.progress.authorized)
        assertEquals(0L, approved.progress.completed)
    }

    @Test
    fun `100k action snapshot serializes only current bounded chunk`() {
        val staged = MediaActionReducer.stage(
            MediaActionReducer.start(MediaAction.Delete, 100_000),
            (0L until 500L).map(::target),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(staged) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as MediaActionSnapshot
        }
        MediaActionReducer.validate(restored)
        assertEquals(500, (restored.phase as MediaActionPhase.AwaitingSystem).targets.size)
        assertTrue("saved state unexpectedly large: ${bytes.size}", bytes.size < 32_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `system request chunk cannot exceed bounded limit`() {
        MediaActionReducer.stage(
            MediaActionReducer.start(MediaAction.Delete, 501),
            (0L until 501L).map(::target),
        )
    }

    @Test
    fun `exhausted query accounts unresolved remainder as failed`() {
        val awaiting = MediaActionReducer.stage(
            MediaActionReducer.start(MediaAction.Delete, 3),
            listOf(target(1), target(2)),
        )
        val verified = MediaActionReducer.verified(
            awaiting, 1, succeeded = 2, failed = 0, VerifiedDisposition.Completed,
        )
        val exhausted = MediaActionReducer.failUnresolvedRemainder(verified)

        assertEquals(2L, exhausted.progress.completed)
        assertEquals(1L, exhausted.progress.failed)
        assertTrue(exhausted.phase is MediaActionPhase.Complete)
    }

    private fun key(id: Long) = MediaKey("external_primary", id)
    @Test
    fun `hand-picked selection larger than one request completes in bounded chunks`() {
        var remaining = (1L..1_234L).map(::target)
        var snapshot = MediaActionReducer.start(MediaAction.Trash(true), remaining.size.toLong())
        var requests = 0
        while (snapshot.phase == MediaActionPhase.ReadyForChunk) {
            val chunk = remaining.take(MediaActionReducer.MaxChunkSize)
            remaining = remaining.drop(chunk.size)
            snapshot = MediaActionReducer.stage(snapshot, chunk)
            val requestId = (snapshot.phase as MediaActionPhase.AwaitingSystem).requestId
            snapshot = MediaActionReducer.verified(
                snapshot, requestId, succeeded = chunk.size, failed = 0, VerifiedDisposition.Completed,
            )
            requests++
        }
        assertEquals(3, requests)
        assertTrue(snapshot.phase is MediaActionPhase.Complete)
        assertEquals(1_234L, snapshot.progress.completed)
    }

    private fun target(id: Long) = MediaActionTarget(key(id), MediaKind.Image)
}
