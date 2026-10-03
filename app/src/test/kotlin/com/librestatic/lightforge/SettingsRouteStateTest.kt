package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRouteStateTest {
    @Test fun settingsKeepsItsSectionWhileASubScreenIsOpen() {
        // A durable key lets Back from Transfer or Own servers land on Backup & restore again.
        assertEquals(SettingsSurfaceStateKey, surfaceStateKey(SurfaceRoute.Settings, RootTab.Photos, null))
    }

    @Test fun backupScreensAreSettingsChildren() {
        assertTrue(SurfaceRoute.LocalSharing in SettingsChildRoutes)
        assertTrue(SurfaceRoute.OwnSync in SettingsChildRoutes)
        assertTrue(SurfaceRoute.RemoteBackup in SettingsChildRoutes)
        assertTrue(SurfaceRoute.LocalBackup in SettingsChildRoutes)
        assertFalse(SurfaceRoute.Root in SettingsChildRoutes)
    }
}
