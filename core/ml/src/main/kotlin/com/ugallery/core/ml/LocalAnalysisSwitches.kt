package com.ugallery.core.ml

import android.content.Context

/** Features grouped under the "Use local analysis" master switch. */
enum class LocalAnalysisFeature {
    People,
    Content,
    Cleanup,
    Pets,
    Semantic,
}

/**
 * Master switch plus each child's own remembered choice. A child runs only while both are on:
 * turning the master off pauses every child without forgetting its choice, turning it back on
 * restores those choices, and turning a child on while the master is off also turns the master on.
 */
data class LocalAnalysisSwitches(
    val master: Boolean = false,
    val remembered: Set<LocalAnalysisFeature> = emptySet(),
) {
    fun isActive(feature: LocalAnalysisFeature): Boolean = master && feature in remembered

    /** An explicit master "on" with nothing remembered opts into every feature, as before. */
    fun withMaster(enabled: Boolean): LocalAnalysisSwitches = when {
        !enabled -> copy(master = false)
        remembered.isEmpty() -> LocalAnalysisSwitches(master = true, remembered = LocalAnalysisFeature.entries.toSet())
        else -> copy(master = true)
    }

    fun withFeature(feature: LocalAnalysisFeature, enabled: Boolean): LocalAnalysisSwitches =
        if (enabled) LocalAnalysisSwitches(master = true, remembered = remembered + feature)
        else copy(remembered = remembered - feature)

    /** Features whose effective (running) state differs between [this] and [next]. */
    fun changedTo(next: LocalAnalysisSwitches): Map<LocalAnalysisFeature, Boolean> =
        LocalAnalysisFeature.entries
            .filter { isActive(it) != next.isActive(it) }
            .associateWith(next::isActive)

    companion object {
        val AllOn = LocalAnalysisSwitches(master = true, remembered = LocalAnalysisFeature.entries.toSet())
        val AllOff = LocalAnalysisSwitches()
    }
}

/** Persists [LocalAnalysisSwitches]; upgrades seed it from the features that are currently running. */
class LocalAnalysisSwitchStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun load(currentlyActive: () -> Set<LocalAnalysisFeature>): LocalAnalysisSwitches {
        if (!preferences.contains(MasterKey)) {
            val active = currentlyActive()
            return LocalAnalysisSwitches(master = active.isNotEmpty(), remembered = active).also(::save)
        }
        val remembered = preferences.getStringSet(RememberedKey, emptySet()).orEmpty()
            .mapNotNull { name -> runCatching { LocalAnalysisFeature.valueOf(name) }.getOrNull() }
            .toSet()
        return LocalAnalysisSwitches(preferences.getBoolean(MasterKey, false), remembered)
    }

    fun save(switches: LocalAnalysisSwitches) {
        preferences.edit()
            .putBoolean(MasterKey, switches.master)
            .putStringSet(RememberedKey, switches.remembered.map { it.name }.toSet())
            .commit()
    }

    private companion object {
        const val PreferencesName = "local-analysis-switches"
        const val MasterKey = "master"
        const val RememberedKey = "remembered"
    }
}
