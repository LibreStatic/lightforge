package com.librestatic.lightforge.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule val rule = createComposeRule()

    private var finished: Set<OnboardingAnalysisOption>? = null
    private var finishCalls = 0

    private fun show() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
            var analysis by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(emptySet<OnboardingAnalysisOption>()) }
            LightforgeTheme {
                OnboardingScreen(
                    step = step,
                    onStepChange = { step = it },
                    access = LibraryAccess(GrantLevel.None, GrantLevel.None, false),
                    permissions = PermissionCoordinator(context),
                    analysis = analysis,
                    onAnalysisChange = { analysis = it },
                    versionName = "test",
                    onOpenLicenses = {},
                    onPermissionResult = {},
                    onFinish = { finished = it; finishCalls++ },
                )
            }
        }
    }

    private fun next() = rule.onNodeWithTag("onboarding-primary").performClick()

    @Test fun skipIsVisibleOnEveryStepAndFinishesWithoutAnalysisChoice() {
        show()
        OnboardingStep.entries.forEach { step ->
            rule.onNodeWithTag("onboarding-step-${step.name}").assertIsDisplayed()
            rule.onNodeWithTag("onboarding-skip").assertIsDisplayed()
            if (step == OnboardingStep.Features) repeat(FeaturePageCount) { next() } else if (step != OnboardingStep.Done) next()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("onboarding-skip").performClick()
        // Finishing waits for the backdrop's exit, which completes at once with animations off.
        rule.waitUntil { finishCalls > 0 }
        assertEquals(1, finishCalls)
        assertNull(finished)
    }

    @Test fun completingReturnsChosenAnalysis() {
        show()
        next() // Welcome
        repeat(FeaturePageCount) { next() }
        next() // Permissions
        rule.onNodeWithTag("onboarding-analysis-People").performClick()
        next() // Analysis
        next() // Open source
        next() // Done -> finish
        rule.waitUntil { finishCalls > 0 }
        assertEquals(1, finishCalls)
        assertEquals(setOf(OnboardingAnalysisOption.People), finished)
    }

    @Test fun backReturnsToPreviousStep() {
        show()
        next()
        rule.onNodeWithTag("onboarding-back").performClick()
        rule.onNodeWithTag("onboarding-step-Welcome").assertIsDisplayed()
    }
}
