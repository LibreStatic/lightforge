package com.librestatic.lightforge.feature.photoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.editing.image.PhotoAutoEnhancementSuggestions
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.ColorMixHue
import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoEditorContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val adjusted = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Adjusted")

    /** Tabs and tool carousels are lazy rows, so off-screen items must be scrolled into view first. */
    private fun scrolledTo(tag: String): SemanticsNodeInteraction {
        val target = hasTestTag(tag)
        val containers = compose.onAllNodes(hasScrollToNodeAction())
        for (index in containers.fetchSemanticsNodes().indices) {
            if (runCatching { containers[index].performScrollToNode(target) }.isSuccess) break
        }
        return compose.onNode(target)
    }

    @Test
    fun filtersHistoryAndCopyActionAreExposed() {
        var applied: EditOperation.Look? = null
        var undoCount = 0
        var saveCount = 0
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(canUndo = true),
                    onBack = {},
                    onSaveCopy = { saveCount++ },
                    onApply = {},
                    onUndo = { undoCount++ },
                    onRedo = {},
                    onLookPreview = { applied = it },
                )
            }
        }
        compose.onNode(hasText("Save copy")).assertIsDisplayed().performClick()
        scrolledTo("photo-editor-tab-filters").performClick()
        scrolledTo("photo-look-builtin:natural").performClick()
        compose.onNode(hasContentDescription("Undo")).performClick()
        assertEquals(1, saveCount)
        assertEquals(EditOperation.Look("builtin:natural", 1f), applied)
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

        val automaticBounds = compose.onNode(hasTestTag("photo-editor-tab-automatic")).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val cropBounds = compose.onNode(hasTestTag("photo-editor-tab-crop")).assertIsDisplayed()
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
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(900.dp, 700.dp))) {
                LightforgeTheme {
                    PhotoEditorContent(
                        state = PhotoEditorContentState(),
                        onBack = {},
                        onSaveCopy = {},
                        onApply = {},
                        onUndo = {},
                        onRedo = {},
                    )
                }
            }
        }

        compose.onNode(hasTestTag("photo-editor-tab-crop")).performClick()
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

        compose.onNode(hasTestTag("photo-editor-tab-crop")).assertIsDisplayed().performClick()
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

        compose.onNode(hasTestTag("photo-editor-tab-crop")).performClick()
        compose.onNode(hasTestTag("photo-crop-rotate")).assertIsDisplayed()
        scrolledTo("photo-crop-flip").assertIsDisplayed()
        compose.onNode(hasTestTag("photo-editor-tab-adjust")).performClick()

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

        compose.onNode(hasTestTag("photo-editor-tab-crop")).performClick()
        compose.onNode(hasContentDescription("Cancel")).performClick()
        compose.runOnIdle {
            assertEquals(0, backCount)
            assertTrue(operations.isEmpty())
        }
    }

    @Test
    fun squareFoldablePlacesToolsBesidePreviewInEitherOrientation() {
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
        val toolsBounds = compose.onNode(hasTestTag("photo-editor-tab-crop"))
            .fetchSemanticsNode().boundsInRoot

        // A near-square window gets media | inspector whichever way it is held (bug 23).
        assertTrue("Square foldable tools must sit beside the preview", toolsBounds.left >= previewBounds.right)
    }

    @Test
    fun adjustSliderDispatchesPreviewBeforeCommit() {
        var adjust by mutableStateOf(EditOperation.Adjust(emptyMap()))
        var previewCount = 0
        var commitCount = 0
        val events = mutableListOf<String>()
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(adjust = adjust),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    onAdjustPreview = {
                        previewCount++
                        events += "preview"
                        adjust = it
                    },
                    onAdjustChangeFinished = {
                        commitCount++
                        events += "commit"
                    },
                )
            }
        }

        compose.onNode(hasTestTag("photo-editor-tab-adjust")).performClick()
        val slider = compose.onNode(hasContentDescription("Brightness"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }

        compose.runOnIdle {
            assertEquals(1, previewCount)
            assertEquals(1, commitCount)
            assertEquals("preview", events.first())
            assertEquals("commit", events.last())
            assertEquals(0.4f, adjust[AdjustKey.Brightness], 0.011f)
        }
    }

    @Test
    fun adjustTilesSwitchTheSliderAndMarkEditedTiles() {
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(
                        adjust = EditOperation.Adjust(mapOf(AdjustKey.Contrast to 0.3f)),
                    ),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                )
            }
        }

        compose.onNode(hasTestTag("photo-editor-tab-adjust")).performClick()
        compose.onNode(hasTestTag("photo-adjust-contrast")).performClick()
        compose.onNode(hasContentDescription("Contrast")).assertIsDisplayed()
        compose.onNode(hasTestTag("photo-adjust-contrast")).assertIsDisplayed()
        compose.onNode(hasTestTag("photo-adjust-contrast") and adjusted).assertExists()
        compose.onNode(hasTestTag("photo-adjust-brightness") and adjusted).assertDoesNotExist()
    }

    @Test
    fun colorMixSwatchesEditTheSelectedBand() {
        var mix: EditOperation.ColorMix? = null
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    onColorMixPreview = { mix = it },
                )
            }
        }

        compose.onNode(hasTestTag("photo-editor-tab-adjust")).performClick()
        scrolledTo("photo-adjust-colormix").performClick()
        scrolledTo("photo-colormix-blue").performClick()
        compose.onNode(hasContentDescription("Saturation"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }

        compose.runOnIdle {
            assertEquals(0.5f, mix!![ColorMixHue.Blue].saturation, 0.011f)
            assertTrue(mix!![ColorMixHue.Red].isNeutral)
        }
    }

    @Test
    fun toolSearchFiltersByNameAndNavigatesToTheTool() {
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                )
            }
        }

        compose.onNode(hasTestTag("photo-tool-search")).performClick()
        compose.onNode(hasTestTag("photo-tool-search-field")).performTextInput("vign")
        compose.onNode(hasTestTag("photo-tool-result-adjust-vignette")).assertIsDisplayed()
        compose.onNode(hasTestTag("photo-tool-result-adjust-contrast")).assertDoesNotExist()
        compose.onNode(hasTestTag("photo-tool-result-adjust-vignette")).performClick()

        compose.onNode(hasContentDescription("Vignette")).assertIsDisplayed()
        compose.onNode(hasTestTag("photo-tool-search-sheet")).assertDoesNotExist()
    }

    @Test
    fun filtersOfferNoneIntensityAndMyFiltersActions() {
        val previews = mutableListOf<EditOperation.Look?>()
        var created = 0
        var renamed: Pair<String, String>? = null
        var deleted: String? = null
        val custom = "custom:123e4567-e89b-12d3-a456-426614174000"
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(
                        look = EditOperation.Look("builtin:vivid", 0.6f),
                        customLooks = listOf(PhotoCustomLook(custom, "Sunset")),
                    ),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    onLookPreview = { previews += it },
                    onCreateCustomLook = { created++ },
                    onRenameCustomLook = { id, name -> renamed = id to name },
                    onDeleteCustomLook = { deleted = it },
                )
            }
        }

        scrolledTo("photo-editor-tab-filters").performClick()
        compose.onNode(hasTestTag("photo-look-intensity")).assertIsDisplayed()
        scrolledTo("photo-look-none").performClick()
        compose.onNode(hasTestTag("photo-looks-tab-mine")).performClick()
        compose.onNode(hasTestTag("photo-look-create")).performClick()
        compose.onNode(hasTestTag("photo-look-menu-$custom")).performClick()
        compose.onNode(hasTestTag("photo-look-rename")).performClick()
        compose.onNode(hasTestTag("photo-look-name-field")).performTextReplacement("Dusk")
        compose.onNode(hasTestTag("photo-look-rename-confirm")).performClick()
        compose.onNode(hasTestTag("photo-look-menu-$custom")).performClick()
        compose.onNode(hasTestTag("photo-look-delete")).performClick()
        compose.onNode(hasTestTag("photo-look-delete-confirm")).performClick()

        compose.runOnIdle {
            assertEquals(listOf<EditOperation.Look?>(null), previews)
            assertEquals(1, created)
            assertEquals(custom to "Dusk", renamed)
            assertEquals(custom, deleted)
        }
    }

    @Test
    fun cropOffersEveryToolAndPerspectiveSlidersMoveTheCorners() {
        val operations = mutableListOf<EditOperation>()
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(preview = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = { operations += it },
                    onUndo = {},
                    onRedo = {},
                )
            }
        }

        compose.onNode(hasTestTag("photo-editor-tab-crop")).performClick()
        listOf("crop", "straighten", "rotate", "perspective", "flip").forEach {
            scrolledTo("photo-crop-$it").assertIsDisplayed()
        }
        scrolledTo("photo-crop-perspective").performClick()
        compose.onNode(hasContentDescription("Vertical"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        // Apply stays pinned below the scrolling panel even with both keystone sliders showing.
        compose.onNode(hasText("Apply")).assertIsDisplayed().performClick()

        compose.runOnIdle {
            val perspective = operations.filterIsInstance<EditOperation.Perspective>().single()
            assertTrue(perspective.corners[0].x > 0f)
            assertTrue(operations.indexOf(perspective) < operations.indexOfFirst { it is EditOperation.Crop })
        }
    }

    @Test
    fun moreTabOffersOpenWithOnlyWhenTheHostProvidesIt() {
        var opened = 0
        var withHost by mutableStateOf(false)
        compose.setContent {
            LightforgeTheme {
                PhotoEditorContent(
                    state = PhotoEditorContentState(),
                    onBack = {},
                    onSaveCopy = {},
                    onApply = {},
                    onUndo = {},
                    onRedo = {},
                    onOpenWith = if (withHost) ({ opened++ }) else null,
                )
            }
        }

        scrolledTo("photo-editor-tab-filters").assertIsDisplayed()
        compose.onNode(hasTestTag("photo-editor-tab-more")).assertDoesNotExist()
        withHost = true
        scrolledTo("photo-editor-tab-more").performClick()
        compose.onNode(hasTestTag("photo-editor-open-with")).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
