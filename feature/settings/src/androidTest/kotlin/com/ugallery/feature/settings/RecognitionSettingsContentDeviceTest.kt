package com.ugallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.preferences.GallerySettings
import com.ugallery.core.preferences.VideoScrubbingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionSettingsContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playbackLetsUsersChooseTheVideoScrubbingMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var settings by mutableStateOf(GallerySettings())

        compose.setContent {
            UGalleryTheme(darkTheme = false) {
                PlaybackSettingsTestContent(
                    settings = settings,
                    onSettingsChange = { transform -> settings = transform(settings) },
                )
            }
        }

        compose.onNode(hasTestTag("video_scrubbing_mode_row")).assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("video_scrubbing_mode_dialog")).assertExists()
        compose.onNode(hasTestTag("video_scrubbing_mode_Filmstrip")).performClick()
        compose.waitForIdle()

        assertEquals(VideoScrubbingMode.Filmstrip, settings.playback.videoScrubbingMode)
        compose.onNode(hasText(context.getString(R.string.settings_video_scrubbing_filmstrip))).assertIsDisplayed()
    }

    @Test
    fun consentPrivacyDeleteAndPetControlsRemainLocalizedAndExplicit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var consent by mutableStateOf(false)
        var pets by mutableStateOf(false)
        var deleteCount by mutableStateOf(0)

        compose.setContent {
            UGalleryTheme(darkTheme = false) {
                RecognitionSettingsContent(
                    state = FaceAnalysisUiState(
                        consentGranted = consent,
                        status = if (consent) AnalysisStatus.Running else null,
                        completedItems = 12,
                    ),
                    onEnable = { consent = true },
                    onPause = {},
                    onResume = {},
                    onAnalyzeAll = {},
                    onDelete = { deleteCount++ },
                    petCollectionsEnabled = pets,
                    petAnalysisState = FaceAnalysisUiState(
                        consentGranted = true,
                        status = AnalysisStatus.Running,
                        completedItems = 7,
                    ),
                    onPetCollectionsEnabledChange = { pets = it },
                    onHideDogResults = {},
                    onHideCatResults = {},
                    onRestorePetResults = {},
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }

        compose.onNode(hasText(context.getString(R.string.settings_title))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.settings_group_viewing))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.settings_group_management)))
            .performScrollTo()
            .assertIsDisplayed()

        // Nested IA: analysis controls live on the "Local analysis" sub-page.
        compose.onNode(hasText(context.getString(R.string.settings_page_ai)))
            .performClick()
        compose.waitForIdle()
        compose.onNode(hasText(context.getString(R.string.face_analysis_no_identity)))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.face_analysis_enable)))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        compose.onNode(hasText(context.getString(R.string.face_analysis_progress, 12)))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.face_analysis_delete)))
            .performScrollTo()
            .performClick()
        compose.onNode(hasText(context.getString(R.string.face_analysis_delete_body))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.face_analysis_delete_confirm))).performClick()
        assertEquals(1, deleteCount)

        compose.onNode(hasContentDescription(context.getString(R.string.pet_collections_enable)))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasTestTag("pet_collections_switch")).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(context.getString(R.string.pet_analysis_running, 7)))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasTestTag("pet_analysis_progress_indicator")).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.pet_hide_dog)))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.pet_hide_cat)))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
