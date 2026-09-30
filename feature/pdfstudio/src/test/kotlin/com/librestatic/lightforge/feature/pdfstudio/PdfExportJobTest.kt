package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfExportJobTest {
    private fun row(phase: PdfExportPhase) =
        PdfExportJob(
            newId(),
            newId(),
            "Queue",
            "{}",
            false,
            phase.name,
            2,
            5,
            1,
            1,
            destination = "content://fixture/result",
        )

    @Test
    fun systemInterruptionQueuesInsteadOfCancelling() {
        for (phase in listOf(PdfExportPhase.Running, PdfExportPhase.Publishing)) {
            val before = row(phase)
            val after = before.interrupted()
            assertEquals(PdfExportPhase.Queued, after.phase)
            assertEquals(before.destination, after.destination)
            assertEquals(0, after.completed)
        }
    }

    @Test
    fun explicitCancellationIsTerminal() {
        val r = row(PdfExportPhase.Cancelled)
        assertEquals(r, r.interrupted())
        assertFalse(r.keepsSources)
    }

    @Test
    fun readyAndPublishedAreNotRerenderedByInterruption() {
        for (phase in listOf(PdfExportPhase.Ready, PdfExportPhase.Published)) {
            val r = row(phase)
            assertEquals(r, r.interrupted())
        }
    }

    @Test
    fun retryableAndUnpublishedJobsKeepSources() {
        for (phase in
            listOf(
                PdfExportPhase.Queued,
                PdfExportPhase.Running,
                PdfExportPhase.Ready,
                PdfExportPhase.Publishing,
                PdfExportPhase.Failed,
            )) assertTrue(row(phase).keepsSources)
    }

    @Test
    fun pendingCancellationKeepsSourcesAndIsNotRescheduled() {
        val r = row(PdfExportPhase.Cancelling)
        assertEquals(r, r.interrupted())
        assertTrue(r.keepsSources)
    }
}
