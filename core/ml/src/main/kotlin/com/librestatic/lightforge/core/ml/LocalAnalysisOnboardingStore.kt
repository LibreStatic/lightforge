package com.librestatic.lightforge.core.ml

import android.content.Context
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory

enum class LocalAnalysisOnboardingDecision {
    Pending,
    Accepted,
    Declined,
}

/** Persists the one-time, fresh-install local-analysis notice independently of task progress. */
class LocalAnalysisOnboardingStore(
    context: Context,
    private val freshInstall: () -> Boolean = { !context.hasExistingGalleryData() },
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun decision(): LocalAnalysisOnboardingDecision {
        preferences.getString(DecisionKey, null)?.let { stored ->
            return runCatching { LocalAnalysisOnboardingDecision.valueOf(stored) }
                .getOrDefault(LocalAnalysisOnboardingDecision.Pending)
        }

        // Do not surprise upgrading users by treating the newly introduced key as
        // consent. Their existing task and collection choices remain authoritative.
        return if (freshInstall()) {
            // Creating the database must not resolve an unanswered notice on the next launch.
            LocalAnalysisOnboardingDecision.Pending.also(::setDecision)
        } else {
            LocalAnalysisOnboardingDecision.Accepted.also(::setDecision)
        }
    }

    fun setDecision(decision: LocalAnalysisOnboardingDecision) {
        preferences.edit().putString(DecisionKey, decision.name).commit()
    }

    private companion object {
        const val PreferencesName = "local-analysis-onboarding"
        const val DecisionKey = "decision"
    }
}

/**
 * App data, rather than package timestamps, is the source of truth here. Android keeps
 * firstInstallTime when the user clears storage, so timestamp checks incorrectly classify a cleared
 * app as an upgrade. The gallery database exists before every genuine in-place upgrade but is
 * removed by both uninstall and Clear storage.
 */
private fun Context.hasExistingGalleryData(): Boolean =
    getDatabasePath(GalleryDatabaseFactory.DatabaseName).exists()
