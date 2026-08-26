package com.ugallery.feature.videoeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    }

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
