package com.librestatic.lightforge.feature.videoeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.editing.video.VideoExportPhase
import com.librestatic.lightforge.core.editing.video.VideoDynamicRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoEditorContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun speedAudioAndSaveControlsAreExposed() {
        var speed = 1f
        var saves = 0
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = { saves++ },
                    onSpeedChange = { speed = it },
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                )
            }
        }
        compose.onNode(hasText(text(R.string.video_editor_speed))).performClick()
        compose.onNode(hasText("2.0×")).performClick()
        assertEquals(2f, speed)
        // Save copy lives in the export sheet that the top bar's Export action opens.
        compose.onNode(hasText(text(R.string.video_editor_export))).assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("video-export-sheet")).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.video_editor_save_copy))).assertIsDisplayed().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun toolBarShowsEveryToolWithoutScrollingOnCompactScreens() {
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    modifier = Modifier.requiredSize(width = 360.dp, height = 760.dp),
                )
            }
        }

        val rootWidthPx = compose.onNode(hasTestTag("video-editor-tool-bar"))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.width
        listOf(
            R.string.video_editor_speed,
            R.string.video_editor_audio,
            R.string.video_editor_music,
            R.string.video_editor_color,
            R.string.video_editor_transform,
            R.string.video_editor_draw,
        ).forEach { label ->
            // Every tool is on screen at once: fully inside the bar, no horizontal scrolling.
            val bounds = compose.onNode(hasText(text(label)))
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Tool label must not be clipped", bounds.left >= 0f && bounds.right <= rootWidthPx + 1f)
        }
    }

    @Test
    fun exportProgressCardKeepsTheAnimatedIndicatorInsideItsContainer() {
        compose.setContent {
            LightforgeTheme {
                VideoExportProgressCard(
                    progress = 0.42f,
                    phase = VideoExportPhase.Rendering,
                    onCancel = {},
                    modifier = Modifier.requiredSize(width = 360.dp, height = 200.dp),
                )
            }
        }

        val cardBounds = compose.onNode(hasTestTag("video-export-progress-card"))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val indicatorBounds = compose.onNode(hasTestTag("video-export-progress-indicator"))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val minimumInset = with(compose.density) { 16.dp.toPx() }

        assertTrue(indicatorBounds.top - cardBounds.top >= minimumInset - 1f)
        assertTrue(indicatorBounds.left - cardBounds.left >= minimumInset - 1f)
        assertTrue(cardBounds.right - indicatorBounds.right >= minimumInset - 1f)
    }

    @Test
    fun exportPanelExposesHdrTransferChoices() {
        var selected = VideoDynamicRange.SdrRec709
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(
                        durationMillis = 18_000,
                        trimEndMillis = 18_000,
                        isHevcMain10Available = true,
                        isHlgExportAvailable = true,
                        isHdr10ExportAvailable = true,
                    ),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    onDynamicRangeChange = { selected = it },
                )
            }
        }

        compose.onNode(hasText(text(R.string.video_editor_export))).performClick()
        compose.onNode(hasText(text(R.string.video_editor_dynamic_range))).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.video_editor_dynamic_range_hlg))).performClick()

        assertEquals(VideoDynamicRange.HdrHlg, selected)
    }

    @Test
    fun gradeSlidersPreserveOtherAdjustments() {
        var state by mutableStateOf(
            VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
        )
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = state,
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    onColorGradeChange = { grade ->
                        state = state.copy(colorGrade = grade)
                    },
                )
            }
        }

        compose.onNode(hasText(text(R.string.video_editor_color))).performClick()
        compose.onNode(hasText(text(R.string.video_editor_primaries))).performClick()
        compose.onNode(hasContentDescription(text(R.string.video_editor_exposure))).performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { setProgress ->
            setProgress(2f)
        }
        val adjustedExposure = state.colorGrade.exposureEv
        compose.onNode(hasContentDescription(text(R.string.video_editor_temperature))).performScrollTo().performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { setProgress ->
            setProgress(-0.5f)
        }

        assertEquals(2f, adjustedExposure)
        assertEquals(adjustedExposure, state.colorGrade.exposureEv)
        assertTrue(state.colorGrade.temperature < 0f)
    }

    @Test
    fun landscapePreviewAndEditingPanelsCanBeResizedVertically() {
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    modifier = Modifier.requiredSize(width = 720.dp, height = 420.dp),
                )
            }
        }

        val divider = compose.onNode(
            hasContentDescription(text(R.string.video_editor_resize_panels)),
        ).assertIsDisplayed()
        val initialTop = divider.fetchSemanticsNode().boundsInRoot.top

        divider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(0.25f)
        }
        compose.waitForIdle()

        val resizedTop = compose.onNode(
            hasContentDescription(text(R.string.video_editor_resize_panels)),
        ).fetchSemanticsNode().boundsInRoot.top
        assertTrue(resizedTop < initialTop)

        val previewBounds = compose.onNode(hasTestTag("video-editor-preview"))
            .fetchSemanticsNode().boundsInRoot
        val panelBounds = compose.onNode(hasTestTag("video-editor-panel"))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Medium landscape keeps the controls below the preview", panelBounds.top >= previewBounds.bottom)
    }

    @Test
    fun expandedLandscapeTabletUsesResizableSidePanel() {
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    modifier = Modifier.requiredSize(width = 900.dp, height = 720.dp),
                )
            }
        }

        val previewBounds = compose.onNode(hasTestTag("video-editor-preview"))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val panelBounds = compose.onNode(hasTestTag("video-editor-panel"))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded tablet controls must be beside the preview", panelBounds.left >= previewBounds.right)

        val divider = compose.onNode(hasContentDescription(text(R.string.video_editor_resize_panels)))
            .assertIsDisplayed()
        val initialLeft = divider.fetchSemanticsNode().boundsInRoot.left
        divider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.42f) }
        compose.waitForIdle()
        val resizedLeft = compose.onNode(hasContentDescription(text(R.string.video_editor_resize_panels)))
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue(resizedLeft < initialLeft)
    }

    @Test
    fun verticalFoldKeepsStackInsideTheWiderUsablePane() {
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    foldInfo = GalleryFoldInfo(
                        orientation = GalleryFoldOrientation.Vertical,
                        isSeparating = true,
                        left = 360.dp,
                        top = 0.dp,
                        right = 380.dp,
                        bottom = 1_000.dp,
                    ),
                    modifier = Modifier.requiredSize(width = 900.dp, height = 1_000.dp),
                )
            }
        }

        val previewBounds = compose.onNode(hasTestTag("video-editor-preview"))
            .fetchSemanticsNode().boundsInRoot
        val panelBounds = compose.onNode(hasTestTag("video-editor-panel"))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The wider right pane must start after the hinge", previewBounds.left > 0f)
        assertEquals(previewBounds.left, panelBounds.left, 1f)
        assertEquals(previewBounds.right, panelBounds.right, 1f)
        assertTrue("Foldable tools remain below the preview", panelBounds.top >= previewBounds.bottom)
    }

    @Test
    fun horizontalFoldLeavesTheSeparatingHingeEmpty() {
        compose.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    foldInfo = GalleryFoldInfo(
                        orientation = GalleryFoldOrientation.Horizontal,
                        isSeparating = true,
                        left = 0.dp,
                        top = 500.dp,
                        right = 900.dp,
                        bottom = 520.dp,
                    ),
                    modifier = Modifier.requiredSize(width = 900.dp, height = 1_000.dp),
                )
            }
        }

        val previewBounds = compose.onNode(hasTestTag("video-editor-preview"))
            .fetchSemanticsNode().boundsInRoot
        val panelBounds = compose.onNode(hasTestTag("video-editor-panel"))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Horizontal hinge must remain empty", panelBounds.top > previewBounds.bottom)
        assertEquals(previewBounds.left, panelBounds.left, 1f)
        assertEquals(previewBounds.right, panelBounds.right, 1f)
    }

    @Test
    fun colorSubsectionSurvivesSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = "device-video-editor",
                    state = VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                )
            }
        }

        compose.onNode(hasText(text(R.string.video_editor_color))).performClick()
        compose.onNode(hasText(text(R.string.video_editor_log_wheels))).performClick()
        restoration.emulateSavedInstanceStateRestore()

        compose.onNode(hasText(text(R.string.video_editor_shadows))).assertExists()
    }

    @Test
    fun draftPositionAndTabRestoreButAnotherSessionStartsClean() {
        var sessionId by mutableStateOf("video-draft-a")
        var checkpoint = -1L
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LightforgeTheme {
                VideoEditorContent(
                    sessionId = sessionId,
                    state = VideoEditorContentState(
                        currentMillis = 1_000,
                        durationMillis = 18_000,
                        trimStartMillis = 500,
                        trimEndMillis = 15_000,
                    ),
                    controller = null,
                    onBack = {},
                    onSaveCopy = {},
                    onSpeedChange = {},
                    onOriginalVolumeChange = {},
                    onChooseMusic = {},
                    onRemoveMusic = {},
                    onSeek = {},
                    onTrimChange = { _, _ -> },
                    onPositionCheckpoint = { checkpoint = it },
                )
            }
        }
        compose.onNode(hasTestTag("video-editor-position")).performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { it(4_000f) }
        compose.onNode(hasText(text(R.string.video_editor_audio))).performClick()
        compose.runOnIdle { assertEquals(4_000L, checkpoint) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasContentDescription(text(R.string.video_editor_audio_description))).assertExists()
        val restoredPosition = compose.onNode(hasTestTag("video-editor-position"))
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(4_000f, restoredPosition.current, 0f)
        compose.onNode(hasTestTag("video-editor-position-value").and(hasText("0:04.000", substring = true)))
            .assertExists()
        compose.onNode(hasTestTag("video-editor-trim-value").and(hasText("0:00.500", substring = true)))
            .assertExists()
        compose.runOnIdle { sessionId = "video-draft-b" }
        compose.onNode(hasContentDescription(text(R.string.video_editor_audio_description))).assertDoesNotExist()
        val freshPosition = compose.onNode(hasTestTag("video-editor-position"))
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1_000f, freshPosition.current, 0f)
    }

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
