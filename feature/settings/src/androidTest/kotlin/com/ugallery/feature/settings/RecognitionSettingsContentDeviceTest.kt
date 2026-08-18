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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionSettingsContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

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
                    onPetCollectionsEnabledChange = { pets = it },
                    onHideDogResults = {},
                    onHideCatResults = {},
                    onRestorePetResults = {},
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }

        compose.onNode(hasText(context.getString(R.string.face_analysis_no_identity))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.face_analysis_enable))).performClick()
        compose.onNode(hasText(context.getString(R.string.face_analysis_progress, 12))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.face_analysis_delete))).performClick()
        compose.onNode(hasText(context.getString(R.string.face_analysis_delete_body))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.face_analysis_delete_confirm))).performClick()
        assertEquals(1, deleteCount)

        compose.onNode(hasContentDescription(context.getString(R.string.pet_collections_enable))).assertIsDisplayed()
        compose.onNode(hasTestTag("pet_collections_switch")).performClick()
        compose.onNode(hasText(context.getString(R.string.pet_hide_dog))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.pet_hide_cat))).assertIsDisplayed()
    }
}
