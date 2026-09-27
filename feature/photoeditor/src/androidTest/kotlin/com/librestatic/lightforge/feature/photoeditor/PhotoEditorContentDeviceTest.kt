package com.librestatic.lightforge.feature.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.editing.image.PhotoAutoEnhancementSuggestions
import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            LightforgeTheme {
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
        compose.onNode(hasText("Filters")).performClick()
        compose.onNode(hasText("Natural")).performClick()
        compose.onNode(hasContentDescription("Undo")).performClick()
        assertEquals(1, saveCount)
        assertEquals(EditOperation.Filter("natural"), applied)
        assertEquals(1, undoCount)
    }

    @Test
    fun automaticLandingShowsCalculatedSuggestionsAndKeepsCropAdjacent() {
        val preview = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val enhance = EditOperation.Tone(brightness = 0.05f, contrast = 1.1f, saturation = 1.05f)
        val dynamic = EditOperation.Tone(brightness = 0.04f, contrast = 1.25f, saturation = 1.2f)
        var selected: EditOperation.Tone? = null
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(
                        preview = preview,
                        originalPreview = preview,
                        autoEnhancementSuggestions = PhotoAutoEnhancementSuggestions(enhance, dynamic),
                    ),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    onApplyAutoSuggestion = { selected = it },
                )
            }
        }

        val automaticBounds = compose.onNode(hasTestTag("photo-editor-tool-automatic")).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val cropBounds = compose.onNode(hasTestTag("photo-editor-tool-crop")).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        compose.onNode(hasText("Original")).assertIsDisplayed()
        compose.onNode(hasText("Enhance")).assertIsDisplayed()
        compose.onNode(hasText("Dynamic")).assertIsDisplayed()
        compose.onNode(hasTestTag("photo-editor-auto-enhance")).assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertTrue("Crop must be immediately after Automatic", cropBounds.left >= automaticBounds.right)
            assertEquals(enhance, selected)
        }
    }

    @Test
    fun cropActionsRemainHorizontalOnExpandedLayouts() {
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    modifier = Modifier.requiredSize(width = 900.dp, height = 700.dp),
                )
            }
        }

        compose.onNode(hasText("Crop")).performClick()
        val applyBounds = compose.onNode(hasText("Apply")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue("Apply must not collapse into a vertical pill", applyBounds.width > applyBounds.height)
    }

    @Test
    fun saveCopyCommitsActiveCropBeforeSaving() {
        val events = mutableListOf<String>()
        val operations = mutableListOf<EditOperation>()
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = {},
                    onSaveCopy = { events += "save" },
                    onApply = {
                        operations += it
                        events += "apply"
                    },
                    onUndo = {},
                    onRedo = {},
                )
            }
        }

        compose.onNode(hasText("Crop")).assertIsDisplayed().performClick()
        compose.onNode(hasText("Save copy")).performClick()

        compose.runOnIdle {
            assertEquals(listOf("apply", "apply", "save"), events)
            assertTrue(operations[0] is EditOperation.Crop)
            assertTrue(operations[1] is EditOperation.Straighten)
        }
    }

    @Test
    fun leavingCropCommitsDraftAndGeometryActionsStayInsideCrop() {
        val operations = mutableListOf<EditOperation>()
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = { operations += it },
                    onUndo = {},
                    onRedo = {},
                )
            }
        }

        compose.onNode(hasText("Crop")).performClick()
        compose.onNode(hasText("Rotate")).assertIsDisplayed()
        compose.onNode(hasText("Flip")).assertIsDisplayed()
        compose.onNode(hasText("Adjust")).performClick()

        compose.runOnIdle {
            assertTrue(operations[0] is EditOperation.Crop)
            assertTrue(operations[1] is EditOperation.Straighten)
        }
    }

    @Test
    fun backCancelsCropWithoutLeavingTheEditor() {
        var backCount = 0
        val operations = mutableListOf<EditOperation>()
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = { backCount++ },
                    onSaveCopy = {},
                    onApply = { operations += it },
                    onUndo = {},
                    onRedo = {},
                )
            }
        }

        compose.onNode(hasTestTag("photo-editor-tool-crop")).performClick()
        compose.onNode(hasContentDescription("Cancel")).performClick()
        compose.runOnIdle {
            assertEquals(0, backCount)
            assertTrue(operations.isEmpty())
        }
    }

    @Test
    fun foldablePortraitPlacesToolsBelowPreview() {
        val preview = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(preview = preview),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    modifier = Modifier.requiredSize(width = 900.dp, height = 1_000.dp),
                )
            }
        }

        val previewBounds = compose.onNode(hasContentDescription("Photo edit preview"))
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val toolsBounds = compose.onNode(hasTestTag("photo-editor-tool-crop"))
            .fetchSemanticsNode().boundsInRoot

        assertTrue("Foldable portrait tools must be below the preview", toolsBounds.top >= previewBounds.bottom)
    }

    @Test
    fun toneSliderDispatchesPreviewBeforeCommit() {
        var tone by mutableStateOf(EditOperation.Tone())
        var previewCount = 0
        var commitCount = 0
        val events = mutableListOf<String>()
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(tone = tone),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    onTonePreview = {
                        previewCount++
                        events += "preview"
                        tone = it
                    },
                    onToneChangeFinished = {
                        commitCount++
                        events += "commit"
                    },
                )
            }
        }

        compose.onNode(hasTestTag("photo-editor-tool-adjust")).performClick()
        val slider = compose.onNode(hasContentDescription("Brightness"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        compose.runOnIdle {
            assertEquals(1, previewCount)
            assertEquals(1, commitCount)
            assertEquals("preview", events.first())
            assertEquals("commit", events.last())
            assertTrue(tone.brightness > 0f)
        }
    }
}
