package com.librestatic.lightforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.librestatic.lightforge.core.ml.LocalAnalysisFeature
import com.librestatic.lightforge.feature.onboarding.OnboardingAnalysisOption
import com.librestatic.lightforge.feature.onboarding.OnboardingScreen
import com.librestatic.lightforge.feature.onboarding.OnboardingStep
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator

internal fun OnboardingAnalysisOption.toFeature(): LocalAnalysisFeature = when (this) {
    OnboardingAnalysisOption.People -> LocalAnalysisFeature.People
    OnboardingAnalysisOption.Content -> LocalAnalysisFeature.Content
    OnboardingAnalysisOption.Pets -> LocalAnalysisFeature.Pets
    OnboardingAnalysisOption.Semantic -> LocalAnalysisFeature.Semantic
}

/** Hosts the first-run wizard plus its one detour, the open-source license list. */
@Composable
internal fun OnboardingHost(viewModel: GalleryViewModel, permissions: PermissionCoordinator) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val switches by viewModel.localAnalysisSwitches.collectAsState()
    // Starts from what is already running, so replaying the wizard never silently turns things off.
    var analysis by rememberSaveable {
        mutableStateOf(
            OnboardingAnalysisOption.entries.filter { switches.isActive(it.toFeature()) }.toSet(),
        )
    }
    val access by viewModel.access.collectAsState()

    if (showLicenses) {
        AboutContent(
            versionName = BuildConfig.VERSION_NAME,
            onBack = { showLicenses = false },
            startInLicenses = true,
        )
        return
    }
    OnboardingScreen(
        step = step,
        onStepChange = { step = it },
        access = access,
        permissions = permissions,
        analysis = analysis,
        onAnalysisChange = { analysis = it },
        versionName = BuildConfig.VERSION_NAME,
        onOpenLicenses = { showLicenses = true },
        onPermissionResult = viewModel::onPermissionRequestResult,
        onFinish = { chosen ->
            viewModel.completeOnboarding(chosen?.mapTo(mutableSetOf()) { it.toFeature() })
        },
    )
}
