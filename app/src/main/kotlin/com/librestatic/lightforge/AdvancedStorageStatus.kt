package com.librestatic.lightforge

import android.content.Context
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the whole app may do with the advanced storage mode (ADR-046) right now. [active] is the only
 * capability gate: supported build, the user's opt-in AND the system permission.
 */
data class AdvancedStatus(
    val active: Boolean = false,
    /** Hidden items are listed: [active] and either the saved setting or [temporaryShowHidden]. */
    val showHidden: Boolean = false,
    /** Session-only show-hidden; never persisted or exported, cleared 5 minutes after leaving the app. */
    val temporaryShowHidden: Boolean = false,
    /** Folders this app hid that it cannot unhide while the permission is missing (mode on, not granted). */
    val revokedWithHidden: Int = 0,
    val deleteEmptyFolders: Boolean = false,
)

/** Remembers the hidden folders the user chose to keep hidden, so the launch prompt does not repeat for them. */
interface KeptHiddenStore {
    fun load(): Set<String>
    fun save(paths: Set<String>)
}

/** Process-only memory; the default for tests. */
class InMemoryKeptHiddenStore : KeptHiddenStore {
    private var paths: Set<String> = emptySet()
    override fun load() = paths
    override fun save(paths: Set<String>) { this.paths = paths }
}

private class PreferencesKeptHiddenStore(context: Context) : KeptHiddenStore {
    private val preferences = context.getSharedPreferences("advanced_storage_prompt", Context.MODE_PRIVATE)
    override fun load(): Set<String> = preferences.getStringSet("kept_hidden", emptySet()).orEmpty().toSet()
    override fun save(paths: Set<String>) { preferences.edit().putStringSet("kept_hidden", paths).apply() }
}

/**
 * The single capability source for advanced storage. With the build flag off the injected
 * [AdvancedStorage] is recovery-only (unsupported), so [status] is always inactive and default behaviour is unchanged.
 */
@Singleton
class AdvancedStorageStatus internal constructor(
    private val advanced: AdvancedStorage,
    settings: kotlinx.coroutines.flow.Flow<GallerySettings>,
    private val scope: CoroutineScope,
    private val kept: KeptHiddenStore = InMemoryKeptHiddenStore(),
) {
    @Inject
    constructor(advanced: AdvancedStorage, repository: GallerySettingsRepository, @ApplicationContext context: Context) :
        this(advanced, repository.settings, CoroutineScope(SupervisorJob() + Dispatchers.Default), PreferencesKeptHiddenStore(context))

    private data class Wish(val enabled: Boolean, val showHidden: Boolean, val deleteEmpty: Boolean)

    private val granted = MutableStateFlow(readGranted())
    private val hiddenCount = MutableStateFlow(0)
    private val temporary = MutableStateFlow(false)
    private val mutablePendingUnhide = MutableStateFlow(0)
    @Volatile private var promptPaths: Set<String> = emptySet()
    @Volatile private var backgroundedAt: Long? = null
    @Volatile private var localChange: Boolean? = null

    val status: StateFlow<AdvancedStatus> = combine(
        settings.map { Wish(it.operations.advancedStorageEnabled, it.operations.advancedShowHidden, it.operations.advancedDeleteEmptyFolders) }
            .distinctUntilChanged(),
        granted,
        temporary,
        hiddenCount,
    ) { wish, isGranted, isTemporary, hidden ->
        val active = advanced.isSupported && wish.enabled && isGranted
        AdvancedStatus(
            active = active,
            showHidden = active && (wish.showHidden || isTemporary),
            temporaryShowHidden = active && isTemporary,
            revokedWithHidden = if (advanced.isSupported && wish.enabled && !isGranted) hidden else 0,
            deleteEmptyFolders = active && wish.deleteEmpty,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AdvancedStatus())

    /**
     * Folders to offer to unhide after an import or reset turned the mode off while this app still
     * hides some; 0 when there is no such prompt. Changes made on the Storage access page never raise it.
     */
    val pendingUnhide: StateFlow<Int> = mutablePendingUnhide.asStateFlow()

    init {
        scope.launch {
            var previous: Boolean? = null
            settings.map { it.operations.advancedStorageEnabled }.distinctUntilChanged().collect { enabled ->
                val paths = advanced.hiddenByApp().map { it.absolutePath }.toSet()
                val count = paths.size
                hiddenCount.value = count
                val own = localChange == enabled
                if (own) localChange = null
                if (previous == true && !enabled && !own && count > 0) raisePrompt(paths)
                // A build without the feature (flag off) cannot see hidden folders any other way: offer it at
                // launch, but only for folders the user has not already chosen to keep hidden.
                if (previous == null && !advanced.isSupported && count > 0 && !kept.load().containsAll(paths)) raisePrompt(paths)
                previous = enabled
            }
        }
        scope.launch {
            status.collect { if (!it.active && temporary.value) temporary.value = false }
        }
    }

    private fun readGranted() = advanced.isSupported && advanced.isGranted()

    /** Re-reads the system permission and the hide registry; call on every resume. */
    fun refresh() {
        granted.value = readGranted()
        scope.launch { hiddenCount.value = advanced.hiddenByApp().size }
    }

    /** The Storage access page is about to write [enabled] itself; that change raises no unhide prompt. */
    fun expectLocalChange(enabled: Boolean) {
        localChange = enabled
    }

    private fun raisePrompt(paths: Set<String>) {
        promptPaths = paths
        mutablePendingUnhide.value = paths.size
    }

    fun dismissPendingUnhide() {
        mutablePendingUnhide.value = 0
    }

    /** The user chose "Keep hidden": the prompt closes and does not return for these folders on later launches. */
    fun keepHidden() {
        kept.save(kept.load() + promptPaths)
        dismissPendingUnhide()
    }

    fun setTemporaryShowHidden(enabled: Boolean) {
        temporary.value = enabled && status.value.active
    }

    /** [elapsed] is a monotonic clock reading in milliseconds (`SystemClock.elapsedRealtime()`). */
    fun onBackgrounded(elapsed: Long) {
        backgroundedAt = elapsed
    }

    /** Drops the temporary show-hidden once the app has been away for [TemporaryTimeoutMillis] or more. */
    fun onForegrounded(elapsed: Long) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (elapsed - since >= TemporaryTimeoutMillis) temporary.value = false
    }

    companion object {
        const val TemporaryTimeoutMillis = 5 * 60_000L
    }
}
