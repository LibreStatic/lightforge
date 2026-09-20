package com.ugallery.feature.collections

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.ugallery.core.designsystem.UGalleryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A single small edit/cancel/apply/reopen contract; persistence belongs to the caller. */
class MomentParticipantsContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editsCancelRetryApplyReopenAndReviewDeletedIdentity() {
        val saved = mutableStateOf(MomentParticipantsSnapshot("story", 1, MomentParticipantsMode.Automatic, emptySet(),
            listOf(PersonCardUi("a", "Alice", 3, null), PersonCardUi("b", "Bob", 2, null)), setOf("a")))
        val open = mutableStateOf(true)
        var attempts = 0
        compose.setContent {
            UGalleryTheme {
                if (open.value) MomentParticipantsContent(
                    snapshot = saved.value,
                    onBack = { open.value = false },
                    onApply = { request ->
                        attempts++
                        if (attempts == 1) MomentParticipantsApplyResult.Failed
                        else if (request.expectedRevision != saved.value.revision) MomentParticipantsApplyResult.Conflict
                        else {
                            saved.value = saved.value.copy(revision = saved.value.revision + 1,
                                mode = request.mode, selectedIds = request.selectedIds)
                            MomentParticipantsApplyResult.Saved
                        }
                    },
                    onApplied = { open.value = false },
                    onReload = {},
                ) else Button(onClick = { open.value = true }, modifier = Modifier.testTag("reopen")) { Text("Reopen") }
            }
        }
        click("moment-participants-manual")
        click("moment-participant-b")
        click("moment-participants-cancel")
        compose.runOnIdle { assertEquals(0, attempts); assertEquals(MomentParticipantsMode.Automatic, saved.value.mode) }
        compose.onNodeWithTag("reopen").performClick()
        compose.onNodeWithTag("moment-participants-auto").assertIsSelected()
        click("moment-participants-manual")
        click("moment-participant-a")
        click("moment-participant-b")
        click("moment-participants-apply")
        compose.onNodeWithTag("moment-participants-list").performScrollToNode(hasTestTag("moment-participants-error"))
        compose.onNodeWithTag("moment-participants-error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(MomentParticipantsMode.Automatic, saved.value.mode) }
        click("moment-participants-apply")
        compose.onNodeWithTag("reopen").performClick()
        compose.onNodeWithTag("moment-participants-manual").assertIsSelected()
        compose.onNodeWithTag("moment-participants-list").performScrollToNode(hasTestTag("moment-participant-b"))
        compose.onNodeWithTag("moment-participant-b").assertIsOn()
        compose.runOnIdle { assertEquals(setOf("b"), saved.value.selectedIds) }
        // Canonical label changes are reflected without creating a new identity or renaming it here.
        compose.runOnIdle { saved.value = saved.value.copy(people = saved.value.people.map { if (it.clusterId == "a") it.copy(displayName = "Alicia") else it }) }
        compose.onNodeWithTag("moment-participants-list").performScrollToNode(hasText("Alicia"))
        compose.onNodeWithText("Alicia").assertIsDisplayed()
        compose.runOnIdle { saved.value = saved.value.copy(revision = saved.value.revision + 1,
            people = saved.value.people.filter { it.clusterId != "b" }) }
        compose.onNodeWithTag("moment-participants-apply").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("Hidden identity is retained in persistence until explicit apply", setOf("b"), saved.value.selectedIds) }
        click("moment-participants-review")
        compose.runOnIdle { assertEquals("Review changes only the draft", setOf("b"), saved.value.selectedIds) }
        compose.onNodeWithTag("moment-participants-apply").assertIsEnabled()
        click("moment-participants-apply")
        compose.onNodeWithTag("reopen").performClick()
        compose.runOnIdle {
            assertEquals(MomentParticipantsMode.Manual, saved.value.mode)
            assertTrue(saved.value.selectedIds.isEmpty())
            saved.value = saved.value.copy(people = emptyList(), automaticIds = emptySet())
        }
        compose.onNodeWithTag("moment-participants-list").performScrollToNode(hasTestTag("moment-participants-empty"))
        compose.onNodeWithTag("moment-participants-empty").assertIsDisplayed()
        compose.onNodeWithTag("moment-participants-apply").assertIsEnabled()
        click("moment-participants-cancel")
    }

    private fun click(tag: String) {
        if (tag !in setOf("moment-participants-apply", "moment-participants-cancel"))
            compose.onNodeWithTag("moment-participants-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }
}
