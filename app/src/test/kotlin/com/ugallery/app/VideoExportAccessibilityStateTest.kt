package com.ugallery.app

import com.ugallery.core.editing.video.VideoExportPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoExportAccessibilityStateTest {
    @Test fun runningProgressClampsAndFloorsToTenPercent() {
        val values = listOf(Int.MIN_VALUE to 0, -1 to 0, 0 to 0, 99 to 0, 100 to 10,
            199 to 10, 899 to 80, 900 to 90, 999 to 90, 1000 to 100, Int.MAX_VALUE to 100)
        values.forEach { (permille, expected) ->
            assertEquals(expected, state(VideoExportJobStatus.Running, permille).percent)
        }
    }

    @Test fun everyPermilleInsideOneBucketKeepsTheSameSemanticState() {
        val initial = state(VideoExportJobStatus.Running, 300)
        for (permille in 301..399) assertEquals(initial, state(VideoExportJobStatus.Running, permille))
        assertNotEquals(initial, state(VideoExportJobStatus.Running, 400))
    }

    @Test fun aNewPhaseRemainsObservableWithoutAChangedPercentage() {
        val rendering = state(VideoExportJobStatus.Running, 850)
        val publishing = videoExportAccessibilityState(VideoExportJobStatus.Running, VideoExportPhase.Publishing, 850)
        assertNotEquals(rendering, publishing)
        assertEquals(rendering.percent, publishing.percent)
        assertEquals(VideoExportPhase.Publishing, publishing.phase)
    }

    @Test fun queuedNeverUsesStalePhaseOrPercent() {
        val queued = state(VideoExportJobStatus.Queued, 980)
        assertEquals(VideoExportJobStatus.Queued, queued.status)
        assertNull(queued.phase)
        assertNull(queued.percent)
        assertEquals(queued, videoExportAccessibilityState(VideoExportJobStatus.Queued, VideoExportPhase.Preparing, null))
    }

    @Test fun unknownRunningProgressIsNotPresentedAsZero() {
        val unknown = state(VideoExportJobStatus.Running, null)
        assertEquals(VideoExportPhase.Rendering, unknown.phase)
        assertNull(unknown.percent)
        assertNotEquals(unknown, state(VideoExportJobStatus.Running, 0))
    }

    @Test fun terminalStatusDoesNotClaimStaleRenderingProgressAsSuccess() {
        for (status in listOf(VideoExportJobStatus.Completed, VideoExportJobStatus.Failed, VideoExportJobStatus.Cancelled)) {
            val terminal = state(status, 730)
            assertEquals(status, terminal.status)
            assertNull(terminal.phase)
            assertNull(terminal.percent)
        }
    }

    @Test fun activeToEachTerminalIsAnnouncedOnceWhenHostAdvancesItsSnapshot() {
        for (active in listOf(VideoExportJobStatus.Queued, VideoExportJobStatus.Running)) {
            for (status in listOf(VideoExportJobStatus.Completed, VideoExportJobStatus.Failed, VideoExportJobStatus.Cancelled)) {
                val before = job("owned", active)
                val terminal = job("owned", status)
                assertEquals(status, videoExportTerminalAnnouncement(before, terminal))
                assertNull(videoExportTerminalAnnouncement(terminal, terminal.copy(updatedAtMillis = 3)))
            }
        }
    }

    @Test fun historyJobReplacementAndNonterminalChangesAreSilent() {
        val historical = job("old", VideoExportJobStatus.Completed)
        assertNull(videoExportTerminalAnnouncement(null, historical))
        assertNull(videoExportTerminalAnnouncement(job("different", VideoExportJobStatus.Running), historical))
        assertNull(videoExportTerminalAnnouncement(job("owned", VideoExportJobStatus.Queued), job("owned", VideoExportJobStatus.Running)))
        assertNull(videoExportTerminalAnnouncement(job("owned", VideoExportJobStatus.Failed), job("owned", VideoExportJobStatus.Cancelled)))
    }

    private fun state(status: VideoExportJobStatus, progress: Int?) =
        videoExportAccessibilityState(status, VideoExportPhase.Rendering, progress)

    private fun job(id: String, status: VideoExportJobStatus) = VideoExportJob(
        id = id, workId = "work-$id", inputUri = "content://fixture/$id", encodedRecipe = "{}",
        displayName = "$id.mp4", status = status, phase = VideoExportPhase.Rendering,
        progressPermille = 730, createdAtMillis = 1, updatedAtMillis = 2,
    )
}
