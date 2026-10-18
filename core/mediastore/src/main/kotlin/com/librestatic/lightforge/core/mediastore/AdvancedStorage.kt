package com.librestatic.lightforge.core.mediastore

import android.content.Intent

/**
 * Optional "advanced storage" capabilities backed by All files access (MANAGE_EXTERNAL_STORAGE).
 * The rest of the app only sees this interface; the implementation lives in the isolated
 * `:core:storage-advanced` module and is bound through `BuildConfig.ALL_FILES_ACCESS` (ADR-046).
 * Builds without the module, or with the flag off, use [NoOpAdvancedStorage].
 */
interface AdvancedStorage {
    /** Compiled into this build (BuildConfig flag on and module present). */
    val isSupported: Boolean

    /** Supported AND the user granted All files access. Re-read on every call; the user can revoke anytime. */
    fun isGranted(): Boolean

    /** Intent to the system "All files access" screen for this package, or null when unsupported. */
    fun permissionIntent(): Intent?

    suspend fun hideFolder(absolutePath: String): AdvancedResult

    suspend fun unhideFolder(absolutePath: String): AdvancedResult

    /** Every hide this app performed and has not undone; readable even after the permission is revoked. */
    suspend fun hiddenByApp(): List<HiddenEntry>

    /** Tries to undo every recorded hide; returns the entries that could not be undone. */
    suspend fun unhideAllRecorded(): List<HiddenEntry>

    /**
     * Same-volume rename of a media file followed by a MediaStore rescan. Refuses cross-volume moves
     * and never overwrites an existing target; callers fall back to the verified copy path on any
     * result other than [AdvancedResult.Done].
     */
    suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult

    /** Deletes the directory only if it exists and is empty. */
    suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult
}

data class HiddenEntry(val absolutePath: String, val kind: HiddenKind, val createdAtMillis: Long)

/** Only [NoMediaFile] is implemented; [DotPrefix] is reserved. */
enum class HiddenKind { NoMediaFile, DotPrefix }

sealed interface AdvancedResult {
    data object Done : AdvancedResult
    data object Unsupported : AdvancedResult
    data object NotGranted : AdvancedResult
    data class Failed(val reason: String) : AdvancedResult
}

/** Default binding: no advanced storage in this build. */
object NoOpAdvancedStorage : AdvancedStorage {
    override val isSupported: Boolean = false
    override fun isGranted(): Boolean = false
    override fun permissionIntent(): Intent? = null
    override suspend fun hideFolder(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported
    override suspend fun unhideFolder(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported
    override suspend fun hiddenByApp(): List<HiddenEntry> = emptyList()
    override suspend fun unhideAllRecorded(): List<HiddenEntry> = emptyList()
    override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult =
        AdvancedResult.Unsupported
    override suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported
}
