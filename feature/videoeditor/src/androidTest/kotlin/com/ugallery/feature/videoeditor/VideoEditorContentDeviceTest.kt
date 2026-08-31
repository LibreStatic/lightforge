package com.ugallery.feature.videoeditor

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
import com.ugallery.core.designsystem.GalleryFoldInfo
import com.ugallery.core.designsystem.GalleryFoldOrientation
import com.ugallery.core.designsystem.UGalleryTheme
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
            UGalleryTheme {
                VideoEditorContent(
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
        compose.onNode(hasText(text(R.string.video_editor_save_copy))).assertIsDisplayed().performClick()
        compose.onNode(hasText(text(R.string.video_editor_speed))).performClick()
        compose.onNode(hasText("2.0×")).performClick()
        assertEquals(2f, speed)
        assertEquals(1, saves)
    }

    @Test
    fun topLevelToolsKeepReadableMinimumWidthOnCompactScreens() {
        compose.setContent {
            UGalleryTheme {
                VideoEditorContent(
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

        val minimumWidthPx = with(compose.density) { 112.dp.toPx() }
        val speedWidth = compose.onNode(hasText(text(R.string.video_editor_speed)))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.width
        val audioWidth = compose.onNode(hasText(text(R.string.video_editor_audio)))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.width

        assertTrue("Tool labels must not be squeezed into clipped buttons", speedWidth >= minimumWidthPx - 1f)
        assertTrue("Every visible tool keeps the same readable minimum", audioWidth >= minimumWidthPx - 1f)
        compose.onNode(hasText(text(R.string.video_editor_export))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun gradeSlidersPreserveOtherAdjustments() {
        var state by mutableStateOf(
            VideoEditorContentState(durationMillis = 18_000, trimEndMillis = 18_000),
        )
        compose.setContent {
            UGalleryTheme {
                VideoEditorContent(
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
            UGalleryTheme {
                VideoEditorContent(
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
            UGalleryTheme {
                VideoEditorContent(
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
            UGalleryTheme {
                VideoEditorContent(
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
            UGalleryTheme {
                VideoEditorContent(
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
            UGalleryTheme {
                VideoEditorContent(
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

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
