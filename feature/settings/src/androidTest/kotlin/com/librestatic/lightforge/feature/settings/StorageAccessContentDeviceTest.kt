package com.librestatic.lightforge.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.preferences.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StorageAccessContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: StorageAccessUiState, actions: StorageAccessActions = StorageAccessActions()) {
        compose.setContent { LightforgeTheme(AppearanceSettings()) { StorageAccessSettingsContent(state, actions) } }
    }

    private val hidden = listOf(HiddenFolderUi("/storage/emulated/0/Pictures/Old", 1L))

    @Test fun unsupported_build_has_no_switch_status_or_hidden_rows() {
        show(StorageAccessUiState(manageMediaSupported = true, advancedSupported = false, advancedEnabled = true, allFilesGranted = true, hidden = emptyList()))
        for (tag in listOf("storage-access-advanced-switch", "storage-access-advanced-status", "storage-access-hidden-row", "storage-access-show-hidden-switch")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test fun active_mode_shows_the_two_switches() {
        show(StorageAccessUiState(advancedSupported = true, advancedEnabled = true, allFilesGranted = true))
        compose.onNodeWithTag("storage-access-show-hidden-switch").assertIsDisplayed()
        compose.onNodeWithTag("storage-access-delete-empty-switch").assertIsDisplayed()
    }

    @Test fun inactive_mode_hides_the_two_switches() {
        show(StorageAccessUiState(advancedSupported = true, advancedEnabled = true, allFilesGranted = false))
        compose.onAllNodesWithTag("storage-access-show-hidden-switch").assertCountEquals(0)
        compose.onAllNodesWithTag("storage-access-delete-empty-switch").assertCountEquals(0)
    }

    @Test fun protected_list_stays_closed_until_the_unlock_succeeds() {
        var unlocks = 0
        val actions = StorageAccessActions(requireUnlock = { unlocks++; it() })
        show(
            StorageAccessUiState(
                advancedSupported = true, advancedEnabled = true, allFilesGranted = true,
                appLockEnabled = true, protectHiddenItems = true, hidden = hidden,
            ),
            actions,
        )
        compose.onAllNodesWithTag("storage-access-hidden-row").assertCountEquals(0)
        compose.onNodeWithTag("storage-access-hidden-show-list").performClick()
        compose.onAllNodesWithTag("storage-access-hidden-row").assertCountEquals(1)
        assertEquals(1, unlocks)
    }
}
