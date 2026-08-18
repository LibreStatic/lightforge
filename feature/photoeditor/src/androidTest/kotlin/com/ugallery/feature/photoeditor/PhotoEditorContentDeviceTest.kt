package com.ugallery.feature.photoeditor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoEditorContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun filtersHistoryAndCopyActionAreExposed() {
        var applied: EditOperation? = null
        var undoCount = 0
        var saveCount = 0
        compose.setContent {
            UGalleryTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(canUndo = true),
                    onBack = {},
                    onSaveCopy = { saveCount++ },
                    onApply = { applied = it },
                    onUndo = { undoCount++ },
                    onRedo = {},
                )
            }
        }
        compose.onNode(hasText("Save copy")).assertIsDisplayed().performClick()
        compose.onNode(hasText("Natural")).performClick()
        compose.onNode(hasText("Undo")).performClick()
        assertEquals(1, saveCount)
        assertEquals(EditOperation.Filter("natural"), applied)
        assertEquals(1, undoCount)
    }
}
