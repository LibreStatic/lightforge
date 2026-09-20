package com.ugallery.app

import org.junit.Assert.*
import org.junit.Test

class OwnStoragePermissionTest {
    @Test
    fun permissionDependsOnBothPlatformAndTargetSdk() {
        assertFalse(ownStorageRequiresLanPermission(30, 37))
        assertFalse(ownStorageRequiresLanPermission(35, 37))
        assertFalse(ownStorageRequiresLanPermission(37, 36))
        assertTrue(ownStorageRequiresLanPermission(37, 37))
        assertTrue(ownStorageRequiresLanPermission(38, 37))
    }
}
