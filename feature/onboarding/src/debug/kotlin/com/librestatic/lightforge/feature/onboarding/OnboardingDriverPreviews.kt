package com.librestatic.lightforge.feature.onboarding

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator

// Debug-only, zero-argument entry points for tools/compose-driver. Each one opens the real
// OnboardingScreen on one step; the primary and back buttons move between steps as usual.

/** The first page. */
@Composable
fun OnboardingWelcomePreview() = OnboardingFrame(OnboardingStep.Welcome)

/** The permissions page: the longest list, so the footer overlaps it in short windows. */
@Composable
fun OnboardingPermissionsPreview() = OnboardingFrame(OnboardingStep.Permissions)

/** The last page, which has no Skip. */
@Composable
fun OnboardingDonePreview() = OnboardingFrame(OnboardingStep.Done)

@Composable
private fun OnboardingFrame(initial: OnboardingStep) {
    val context = LocalContext.current
    // The backdrop and samples animate on an endless frame loop that never lets the headless
    // renderer go idle; with animations off (as on the test emulator) they render their still frame.
    remember(context) {
        listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
            .onEach { Settings.Global.putFloat(context.contentResolver, it, 0f) }
    }
    val permissions = remember(context) { PermissionCoordinator(context) }
    var step by remember { mutableStateOf(initial) }
    var analysis by remember { mutableStateOf(emptySet<OnboardingAnalysisOption>()) }
    LightforgeTheme {
        OnboardingScreen(
            step = step,
            onStepChange = { step = it },
            access = LibraryAccess(GrantLevel.None, GrantLevel.None, false),
            permissions = permissions,
            analysis = analysis,
            onAnalysisChange = { analysis = it },
            versionName = "1.0",
            onOpenLicenses = {},
            onPermissionResult = {},
            onFinish = {},
        )
    }
}
