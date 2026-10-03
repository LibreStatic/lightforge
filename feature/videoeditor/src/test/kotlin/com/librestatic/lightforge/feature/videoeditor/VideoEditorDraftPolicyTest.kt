package com.librestatic.lightforge.feature.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEditorDraftPolicyTest {
    @Test fun preservesIntermediateDraftPosition() {
        assertEquals(4_000L, videoEditorDraftPosition(4_000, 500, 15_000, 18_000))
    }
    @Test fun trimEndIsExclusive() {
        assertEquals(14_999L, videoEditorDraftPosition(15_000, 500, 15_000, 18_000))
        assertEquals(500L, videoEditorDraftPosition(-1, 500, 15_000, 18_000))
    }
    @Test fun releasedOrLoadingControllerDoesNotOverwriteDraftWithZero() {
        assertEquals(4_000L, videoEditorCheckpointPosition(null, 4_000, 500, 15_000, 18_000))
    }
    @Test fun readyPlayerCheckpointUsesActualPosition() {
        assertEquals(5_250L, videoEditorCheckpointPosition(5_250, 4_000, 500, 15_000, 18_000))
        assertEquals(0L, videoEditorCheckpointPosition(0, 4_000, 0, 15_000, 18_000))
    }
    @Test fun shortenedTrimClampsOnlyPosition() {
        assertEquals(2_999L, videoEditorCheckpointPosition(null, 4_000, 500, 3_000, 18_000))
    }
    @Test fun fullDurationAndUnloadedPreviewUseExistingUiBounds() {
        assertEquals(17_999L, videoEditorDraftPosition(Long.MAX_VALUE, 0, 0, 18_000))
        assertEquals(0L, videoEditorDraftPosition(0, 0, 0, 0))
        assertEquals(9L, videoEditorDraftPosition(15, 30, 40, 10))
    }
    @Test fun exactEditingTimecodesKeepMillisecondsWithoutRounding() {
        assertEquals("0:00.000", formatVideoEditorDraftTime(0))
        assertEquals("0:00.001", formatVideoEditorDraftTime(1))
        assertEquals("0:00.500", formatVideoEditorDraftTime(500))
        assertEquals("0:01.234", formatVideoEditorDraftTime(1_234))
        assertEquals("0:02.678", formatVideoEditorDraftTime(2_678))
        assertEquals("0:59.999", formatVideoEditorDraftTime(59_999))
        assertEquals("1:00.000", formatVideoEditorDraftTime(60_000))
        assertEquals("60:00.007", formatVideoEditorDraftTime(3_600_007))
    }
    @Test fun lengthShowsTenthsOnlyUnderTenSeconds() {
        assertEquals("0:00.2", formatVideoEditorLengthTime(200))
        assertEquals("0:02.7", formatVideoEditorLengthTime(2_700))
        assertEquals("0:09.9", formatVideoEditorLengthTime(9_999))
        assertEquals("0:10", formatVideoEditorLengthTime(10_000))
        assertEquals("1:05", formatVideoEditorLengthTime(65_400))
    }
}
