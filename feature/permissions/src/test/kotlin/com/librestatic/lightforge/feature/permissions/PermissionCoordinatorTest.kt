package com.librestatic.lightforge.feature.permissions

import android.Manifest
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PermissionCoordinatorTest {
    @Test
    fun api30To32UsesSingleLegacyGrantForBothMediaKinds() {
        assertEquals(
            LibraryAccess(GrantLevel.Full, GrantLevel.Full, false),
            coordinator(32, Manifest.permission.READ_EXTERNAL_STORAGE).access.value,
        )
        assertEquals(
            LibraryAccess(GrantLevel.None, GrantLevel.None, false),
            coordinator(30).access.value,
        )
    }

    @Test
    fun api33EvaluatesImagesAndVideosIndependently() {
        assertEquals(
            LibraryAccess(GrantLevel.Full, GrantLevel.None, false),
            coordinator(33, Manifest.permission.READ_MEDIA_IMAGES).access.value,
        )
        assertEquals(
            LibraryAccess(GrantLevel.None, GrantLevel.Full, false),
            coordinator(33, Manifest.permission.READ_MEDIA_VIDEO).access.value,
        )
    }

    @Test
    fun api34To37FallsBackToSelectedWithoutClaimingFullAccess() {
        listOf(34, 35, 36, 37).forEach { api ->
            assertEquals(
                LibraryAccess(GrantLevel.Selected, GrantLevel.Selected, false),
                coordinator(api, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED).access.value,
            )
        }
    }

    @Test
    fun api34SupportsMixedFullSelectedAndNoneStates() {
        assertEquals(
            LibraryAccess(GrantLevel.Full, GrantLevel.Selected, false),
            coordinator(
                34,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ).access.value,
        )
        assertEquals(
            LibraryAccess(GrantLevel.Full, GrantLevel.None, false),
            coordinator(36, Manifest.permission.READ_MEDIA_IMAGES).access.value,
        )
    }

    @Test
    fun locationMetadataIsIndependentFromLibraryAccess() {
        assertEquals(
            LibraryAccess(GrantLevel.None, GrantLevel.None, true),
            coordinator(36, Manifest.permission.ACCESS_MEDIA_LOCATION).access.value,
        )
    }

    @Test
    fun revalidateReadsFreshGrantsInsteadOfPersistingThem() {
        val grants = mutableSetOf(Manifest.permission.READ_MEDIA_IMAGES)
        val coordinator = PermissionCoordinator(36, PermissionGrantReader(grants::contains))
        assertEquals(GrantLevel.Full, coordinator.access.value.images)

        grants.clear()
        assertEquals(
            LibraryAccess(GrantLevel.None, GrantLevel.None, false),
            coordinator.revalidate(),
        )
        assertEquals(GrantLevel.None, coordinator.access.value.images)
    }

    @Test
    fun requestPlansMatchApiMatrixAndKeepLocationExplicit() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            coordinator(32).initialMediaRequest().permissions,
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            coordinator(33).initialMediaRequest(includeVideos = false).permissions,
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            ),
            coordinator(36).initialMediaRequest(
                includeImages = false,
                includeLocationMetadata = true,
            ).permissions,
        )
    }

    @Test
    fun reselectionIsOnlyAvailableOnApi34AndNewer() {
        assertThrows(IllegalArgumentException::class.java) {
            coordinator(33).reselectionRequest()
        }
        assertEquals(
            PermissionRequestPurpose.ReselectMedia,
            coordinator(34).reselectionRequest().purpose,
        )
    }

    private fun coordinator(api: Int, vararg grants: String): PermissionCoordinator =
        PermissionCoordinator(api, PermissionGrantReader(grants.toSet()::contains))
}
