package com.ugallery.app

import com.ugallery.core.editing.video.VideoExportPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportQueueTest {
    @Test
    fun systemInterruptionRequeuesTheExportWithoutStalePublishingState() {
        val interrupted = job(VideoExportJobStatus.Running).copy(
            phase = VideoExportPhase.Publishing,
            progressPermille = 930,
            pendingUri = "content://media/pending/1",
            error = "stale",
        ).afterWorkerInterruption()

        assertEquals(VideoExportJobStatus.Queued, interrupted.status)
        assertEquals(VideoExportPhase.Preparing, interrupted.phase)
        assertEquals(0, interrupted.progressPermille)
        assertNull(interrupted.pendingUri)
        assertNull(interrupted.error)
    }

    @Test
    fun explicitUserCancellationStaysCancelled() {
        val cancelled = job(VideoExportJobStatus.Cancelled).copy(
            phase = VideoExportPhase.Rendering,
            progressPermille = 420,
            error = "stale",
        ).afterWorkerInterruption()

        assertEquals(VideoExportJobStatus.Cancelled, cancelled.status)
        assertEquals(VideoExportPhase.Rendering, cancelled.phase)
        assertEquals(420, cancelled.progressPermille)
        assertNull(cancelled.error)
    }

    @Test
    fun globalQueueContainsOnlyActiveJobsInFifoOrder() {
        val newestQueued = job(VideoExportJobStatus.Queued).copy(id = "new", createdAtMillis = 30L)
        val running = job(VideoExportJobStatus.Running).copy(
            id = "running",
            createdAtMillis = 10L,
            progressPermille = 425,
        )
        val oldestQueued = job(VideoExportJobStatus.Queued).copy(id = "old", createdAtMillis = 5L)
        val completed = job(VideoExportJobStatus.Completed).copy(id = "done", createdAtMillis = 1L)

        val queue = activeVideoExportQueue(listOf(newestQueued, completed, running, oldestQueued))

        assertEquals(listOf("old", "running", "new"), queue.map(VideoExportJob::id))
        assertEquals(0.425f, requireNotNull(activeVideoExportProgress(queue)), 0.0001f)
        assertTrue(queue.none { it.status == VideoExportJobStatus.Completed })
    }

    private fun job(status: VideoExportJobStatus) = VideoExportJob(
        id = "job",
        workId = "work",
        inputUri = "content://media/input/1",
        encodedRecipe = "recipe",
        displayName = "output.mp4",
        status = status,
        phase = VideoExportPhase.Preparing,
        progressPermille = 0,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
