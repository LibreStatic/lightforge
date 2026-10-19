package com.librestatic.lightforge

import com.librestatic.lightforge.feature.folders.FolderLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderShortcutTest {
    @Test fun aFolderShortcutNamesTheVolumeAndTheNormalisedPath() {
        assertEquals(
            FolderLocation("external_primary", "DCIM/Trips/"),
            folderLocationFromShortcut(ACTION_OPEN_FOLDER, "external_primary", "DCIM/Trips"),
        )
        assertEquals(FolderLocation("1234-ABCD", null), folderLocationFromShortcut(ACTION_OPEN_FOLDER, "1234-ABCD", ""))
    }

    @Test fun otherActionsAndMissingVolumesAreIgnored() {
        assertNull(folderLocationFromShortcut("android.intent.action.VIEW", "external_primary", "DCIM/"))
        assertNull(folderLocationFromShortcut(null, "external_primary", "DCIM/"))
        assertNull(folderLocationFromShortcut(ACTION_OPEN_FOLDER, null, "DCIM/"))
        assertNull(folderLocationFromShortcut(ACTION_OPEN_FOLDER, " ", "DCIM/"))
    }

    @Test fun absurdlyLongFieldsAreRefused() {
        assertNull(folderLocationFromShortcut(ACTION_OPEN_FOLDER, "x".repeat(5000), "DCIM/"))
        assertEquals(
            FolderLocation("external_primary", null),
            folderLocationFromShortcut(ACTION_OPEN_FOLDER, "external_primary", "y".repeat(5000)),
        )
    }
}
