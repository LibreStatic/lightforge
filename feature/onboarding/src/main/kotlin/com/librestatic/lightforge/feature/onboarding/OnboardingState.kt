package com.librestatic.lightforge.feature.onboarding

import androidx.compose.ui.geometry.Rect
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess

/** Top-level wizard steps, in order. Stable names back the saved step across process death. */
enum class OnboardingStep { Welcome, Features, Studio, Permissions, Analysis, OpenSource, Done }

/**
 * Hand-off from the system splash screen. [iconBounds] is where its star sat, in window pixels
 * (null when unknown), and [onScreen] stays true until the splash starts leaving, so the wizard's
 * intro can pick the logo up in place instead of starting behind it.
 */
data class OnboardingSplashHandoff(val onScreen: Boolean, val iconBounds: Rect? = null)

/** Opt-in on-device analysis features offered by the wizard; the host maps them to its switches. */
enum class OnboardingAnalysisOption { People, Content, Pets, Semantic }

/** What a permission row shows. Never rendered as an error: every state keeps the app usable. */
enum class OnboardingPermissionStatus {
    /** Not asked yet in this install. */
    NotRequested,

    /** Fully granted. */
    Granted,

    /** Photo picker "selected items only" access (API 34+). */
    Partial,

    /** Denied, but Android will still show its dialog again. */
    Denied,

    /** Denied with "don't ask again"; only system settings can change it. */
    Blocked,

    /** Not applicable on this Android version (for example notifications before API 33). */
    Unavailable,
}

object OnboardingPermissionPolicy {
    /**
     * Maps the live media grant to a row status. [asked] is whether this install ever launched
     * the request; [showRationale] is Android's `shouldShowRequestPermissionRationale`, which is
     * only false after a denial when the system will no longer show its dialog.
     */
    fun media(access: LibraryAccess, asked: Boolean, showRationale: Boolean): OnboardingPermissionStatus = when {
        access.images == GrantLevel.Full && access.videos == GrantLevel.Full -> OnboardingPermissionStatus.Granted
        access.images != GrantLevel.None || access.videos != GrantLevel.None -> OnboardingPermissionStatus.Partial
        else -> denial(asked, showRationale)
    }

    fun single(
        granted: Boolean,
        asked: Boolean,
        showRationale: Boolean,
        available: Boolean = true,
    ): OnboardingPermissionStatus = when {
        !available -> OnboardingPermissionStatus.Unavailable
        granted -> OnboardingPermissionStatus.Granted
        else -> denial(asked, showRationale)
    }

    private fun denial(asked: Boolean, showRationale: Boolean) = when {
        !asked -> OnboardingPermissionStatus.NotRequested
        showRationale -> OnboardingPermissionStatus.Denied
        else -> OnboardingPermissionStatus.Blocked
    }
}
