package com.ugallery.app

import com.ugallery.core.editing.video.VideoEditRecipe
import com.ugallery.core.editing.video.VideoExportPhase
import com.ugallery.core.editing.video.VideoOutputQuality
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.feature.videoeditor.VideoEditorContentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoEditorFlowStateTest {
    @Test
    fun newlyOpenedEditorAttachesOnlyToNewestActiveExportForItsInput() {
        val completed = job("completed", InputA, VideoExportJobStatus.Completed, createdAt = 30)
        val running = job("running", InputA, VideoExportJobStatus.Running, createdAt = 20)
        val otherInput = job("other", InputB, VideoExportJobStatus.Queued, createdAt = 40)

        assertEquals(
            running,
            activeVideoExportForInput(listOf(completed, running, otherInput), InputA),
        )
        assertNull(activeVideoExportForInput(listOf(completed, otherInput), InputA))
    }

    @Test
    fun editorFollowsItsExactExportThroughCompletion() {
        val completed = job("owned", InputA, VideoExportJobStatus.Completed, createdAt = 10)
        val newer = job("newer", InputA, VideoExportJobStatus.Running, createdAt = 20)

        assertEquals(
            completed,
            trackedVideoExport(listOf(newer, completed), exportJobId = "owned", inputUri = InputA),
        )
        assertEquals(
            newer,
            trackedVideoExport(listOf(newer, completed), exportJobId = null, inputUri = InputA),
        )
    }

    @Test
    fun activeExportRecipeWinsOverAStalePersistedDraft() {
        val staleDraft = VideoEditRecipe(speed = 2f)
        val exported = VideoEditRecipe(originalAudioVolume = 0.5f)

        assertEquals(
            exported,
            preferredVideoEditorRecipe(
                storedRecipe = staleDraft,
                storedUpdatedAtMillis = 10,
                activeExportRecipe = exported,
                activeExportCreatedAtMillis = 20,
            ),
        )
        assertEquals(
            staleDraft,
            preferredVideoEditorRecipe(
                storedRecipe = staleDraft,
                storedUpdatedAtMillis = 30,
                activeExportRecipe = exported,
                activeExportCreatedAtMillis = 20,
            ),
        )
    }

    @Test
    fun legacyGlobalSlowMotionIsNormalizedWithoutLosingTheTrim() {
        val normalized = VideoEditRecipe(startMillis = 1_000, endMillis = 6_000, speed = 0.25f)
            .normalizedForEditor(durationMillis = 10_000, supportsHevcMain10 = true)

        assertEquals(1f, normalized.speed)
        assertEquals(1_000, normalized.slowMotionSegments.single().startMillis)
        assertEquals(6_000, normalized.slowMotionSegments.single().endMillis)
        assertEquals(0.25f, normalized.slowMotionSegments.single().speed)
    }

    @Test
    fun unavailableMain10OutputIsDowngradedForEditing() {
        val normalized = VideoEditRecipe(outputQuality = VideoOutputQuality.HevcMain10)
            .normalizedForEditor(durationMillis = 10_000, supportsHevcMain10 = false)

        assertEquals(VideoOutputQuality.H264Compatible, normalized.outputQuality)
    }

    @Test
    fun explicitSourceEndIsCanonicalizedAndDoesNotCreateAFalseTrim() {
        val normalized = VideoEditRecipe(startMillis = 0, endMillis = 10_000)
            .normalizedForEditor(durationMillis = 10_000, supportsHevcMain10 = true)

        assertNull(normalized.endMillis)
        assertEquals(VideoEditRecipe(), normalized)
    }

    @Test
    fun playbackPositionTreatsTheTrimEndAsExclusive() {
        assertEquals(4_999, clampVideoPosition(5_000, trimStartMillis = 1_000, trimEndMillis = 5_000))
        assertEquals(1_000, clampVideoPosition(0, trimStartMillis = 1_000, trimEndMillis = 5_000))
    }

    @Test
    fun pendingExportIsTheDirtyBaselineUntilItReachesATerminalState() {
        val exported = VideoEditRecipe(speed = 2f)
        val session = VideoEditorSession(
            media = media(),
            baselineRecipe = VideoEditRecipe(),
            recipe = exported,
            pendingExportRecipe = exported,
            content = VideoEditorContentState(durationMillis = 10_000, trimEndMillis = 10_000),
        )

        assertEquals(false, session.isDirty())
        assertEquals(true, session.isDirty(exported.copy(originalAudioVolume = 0.5f)))
        assertEquals(true, session.copy(pendingExportRecipe = null).isDirty())
    }

    private fun job(
        id: String,
        input: String,
        status: VideoExportJobStatus,
        createdAt: Long,
    ) = VideoExportJob(
        id = id,
        workId = "work-$id",
        inputUri = input,
        encodedRecipe = "{}",
        displayName = "$id.mp4",
        status = status,
        phase = if (status == VideoExportJobStatus.Completed) {
            VideoExportPhase.Completed
        } else {
            VideoExportPhase.Rendering
        },
        progressPermille = if (status == VideoExportJobStatus.Completed) 1_000 else 500,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    private fun media() = TimelineMedia(
        key = MediaKey("external_primary", 1),
        kind = MediaKind.Video,
        generationModified = 1,
        timelineSortMillis = 1,
        width = 1_920,
        height = 1_080,
        durationMillis = 10_000,
    )

    private companion object {
        const val InputA = "content://media/external/video/media/1"
        const val InputB = "content://media/external/video/media/2"
    }
}
