package com.librestatic.lightforge.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessStateTest {
    @Test fun `the mode is active only when enabled and granted`() {
        assertEquals(AdvancedStatus.Off, advancedStatusOf(enabled = false, granted = false))
        assertEquals(AdvancedStatus.Off, advancedStatusOf(enabled = false, granted = true))
        assertEquals(AdvancedStatus.WaitingForPermission, advancedStatusOf(enabled = true, granted = false))
        assertEquals(AdvancedStatus.Active, advancedStatusOf(enabled = true, granted = true))
    }

    @Test fun `turning off asks only when folders are hidden`() {
        assertEquals(TurnOffAction.TurnOffDirectly, turnOffAction(0))
        assertEquals(TurnOffAction.AskAboutHiddenFolders, turnOffAction(1))
        assertEquals(TurnOffAction.AskAboutHiddenFolders, turnOffAction(7))
    }

    @Test fun `the revoked warning needs opt-in, no permission and hidden folders`() {
        val hidden = listOf(HiddenFolderUi("/storage/emulated/0/A", 1L))
        val base = StorageAccessUiState(advancedSupported = true, advancedEnabled = true, allFilesGranted = false, hidden = hidden)
        assertTrue(base.permissionRevoked)
        assertFalse(base.copy(allFilesGranted = true).permissionRevoked)
        assertFalse(base.copy(advancedEnabled = false).permissionRevoked)
        assertFalse(base.copy(hidden = emptyList()).permissionRevoked)
        assertFalse(base.copy(advancedSupported = false).permissionRevoked)
    }

    @Test fun `the page has content only when something can be shown`() {
        assertFalse(StorageAccessUiState().hasContent)
        assertTrue(StorageAccessUiState(manageMediaSupported = true).hasContent)
        assertTrue(StorageAccessUiState(advancedSupported = true).hasContent)
        assertTrue(StorageAccessUiState(hidden = listOf(HiddenFolderUi("/x", 0L))).hasContent)
    }

    @Test fun `hidden paths are shortened to a readable form`() {
        assertEquals("Pictures/Trip", readableHiddenPath("/storage/emulated/0/Pictures/Trip"))
        assertEquals("Pictures/Trip", readableHiddenPath("/storage/emulated/10/Pictures/Trip/"))
        assertEquals("DCIM", readableHiddenPath("/sdcard/DCIM"))
        assertEquals("1A2B-3C4D/DCIM/Old", readableHiddenPath("/storage/1A2B-3C4D/DCIM/Old"))
        assertEquals("/data/other", readableHiddenPath("/data/other"))
    }
}
