package com.librestatic.lightforge.feature.onboarding

import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPermissionPolicyTest {
    private fun access(images: GrantLevel, videos: GrantLevel = images) = LibraryAccess(images, videos, false)

    @Test fun fullMediaIsGranted() = assertEquals(
        OnboardingPermissionStatus.Granted,
        OnboardingPermissionPolicy.media(access(GrantLevel.Full), asked = true, showRationale = false),
    )

    @Test fun selectedOrMixedMediaIsPartial() {
        assertEquals(
            OnboardingPermissionStatus.Partial,
            OnboardingPermissionPolicy.media(access(GrantLevel.Selected), asked = true, showRationale = false),
        )
        assertEquals(
            OnboardingPermissionStatus.Partial,
            OnboardingPermissionPolicy.media(access(GrantLevel.Full, GrantLevel.None), asked = true, showRationale = false),
        )
    }

    @Test fun neverAskedIsNotRequestedEvenWithoutRationale() = assertEquals(
        OnboardingPermissionStatus.NotRequested,
        OnboardingPermissionPolicy.media(access(GrantLevel.None), asked = false, showRationale = false),
    )

    @Test fun deniedOnceCanAskAgain() = assertEquals(
        OnboardingPermissionStatus.Denied,
        OnboardingPermissionPolicy.media(access(GrantLevel.None), asked = true, showRationale = true),
    )

    @Test fun deniedWithoutRationaleIsBlocked() = assertEquals(
        OnboardingPermissionStatus.Blocked,
        OnboardingPermissionPolicy.single(granted = false, asked = true, showRationale = false),
    )

    @Test fun unavailableWinsOverEverything() = assertEquals(
        OnboardingPermissionStatus.Unavailable,
        OnboardingPermissionPolicy.single(granted = true, asked = true, showRationale = true, available = false),
    )
}
