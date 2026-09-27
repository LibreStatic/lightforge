package com.librestatic.lightforge.feature.settings

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
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.preferences.VideoScrubbingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionSettingsContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun folderBrowserNavigatesHierarchyAndKeepsDirectFolderExceptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var settings by mutableStateOf(GallerySettings())
        val folders = listOf(
            GalleryFolderOption("external_primary", 1L, "Pictures/", "Pictures", 2L),
            GalleryFolderOption("external_primary", 2L, "Pictures/Family/", "Family", 3L),
        )

        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                RecognitionSettingsContent(
                    state = FaceAnalysisUiState(),
                    onEnable = {},
                    onPause = {},
                    onResume = {},
                    onAnalyzeAll = {},
                    onDelete = {},
                    petCollectionsEnabled = false,
                    onPetCollectionsEnabledChange = {},
                    onHideDogResults = {},
                    onHideCatResults = {},
                    onRestorePetResults = {},
                    settings = settings,
                    folderOptions = folders,
                    onSettingsChange = { transform -> settings = transform(settings) },
                )
            }
        }

        compose.onNode(hasText(context.getString(R.string.settings_library))).performClick()
        compose.onNode(hasTestTag("folder_browser_row")).performClick()
        compose.onNode(hasContentDescription(context.getString(R.string.settings_toggle_folder, "Pictures")))
            .performClick()
        compose.waitForIdle()

        assertFalse(settings.library.folderRules[FolderSelectionTarget.Path("external_primary", "Pictures/")]!!)

        compose.onNode(hasText("Pictures")).performClick()
        compose.onNode(hasText(context.getString(R.string.settings_subfolders))).assertIsDisplayed()
        compose.onNode(hasText("Family")).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.settings_folder_direct_items))).performClick()
        compose.waitForIdle()

        assertTrue(settings.library.folderRules[FolderSelectionTarget.Bucket("external_primary", 1L)]!!)
    }

    @Test
    fun playbackLetsUsersChooseTheVideoScrubbingMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var settings by mutableStateOf(GallerySettings())

        compose.setContent {
            LightforgeTheme(darkTheme = false) {
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
            LightforgeTheme(darkTheme = false) {
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
        compose.onNode(hasText(context.resources.getQuantityString(R.plurals.face_analysis_progress, 12, 12)))
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
        compose.onNode(hasText(context.resources.getQuantityString(R.plurals.pet_analysis_running, 7, 7)))
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

    @Test
    fun firstSemanticModelBeingIndexedIsReportedAsPreparingRatherThanActive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = SemanticModelSettingsItemUi(
            id = "quality",
            name = "TinyCLIP Quality",
            version = "1.0.0",
            sizeBytes = 225L * 1024 * 1024,
            quality = "Higher quality",
            languages = "Mainly English",
            compatibility = SemanticModelCompatibilityUi.Supported,
            installed = true,
            active = false,
            downloading = false,
        )

        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                SemanticModelsTestContent(
                    SemanticModelSettingsUiState(
                        enabled = true,
                        buildingModelId = model.id,
                        models = listOf(model),
                    ),
                )
            }
        }

        compose.onNode(
            hasText(context.getString(R.string.semantic_settings_preparing, model.name, model.version)),
        ).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.semantic_model_building))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.semantic_model_building_first_description))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.semantic_settings_no_active))).assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.semantic_model_use))).assertDoesNotExist()
    }

    @Test
    fun aboutRowIsReachableAndOpensAbout() {
        var openCount = 0

        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                RecognitionSettingsContent(
                    state = FaceAnalysisUiState(),
                    onEnable = {},
                    onPause = {},
                    onResume = {},
                    onAnalyzeAll = {},
                    onDelete = {},
                    petCollectionsEnabled = false,
                    onPetCollectionsEnabledChange = {},
                    onHideDogResults = {},
                    onHideCatResults = {},
                    onRestorePetResults = {},
                    onOpenAbout = { openCount++ },
                )
            }
        }

        compose.onNode(hasTestTag("settings_about_row"))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, openCount)
    }
}
