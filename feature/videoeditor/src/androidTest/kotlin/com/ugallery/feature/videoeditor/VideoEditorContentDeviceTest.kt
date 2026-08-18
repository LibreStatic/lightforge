package com.ugallery.feature.videoeditor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.designsystem.UGalleryTheme
import org.junit.Assert.assertEquals
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
        compose.onNode(hasText("Save copy")).assertIsDisplayed().performClick()
        compose.onNode(hasText("Speed")).performClick()
        compose.onNode(hasText("2× Fast")).performClick()
        assertEquals(2f, speed)
        assertEquals(1, saves)
    }
}
