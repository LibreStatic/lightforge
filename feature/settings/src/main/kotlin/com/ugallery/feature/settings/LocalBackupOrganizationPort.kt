package com.ugallery.feature.settings

import java.io.InputStream

/**
 * App adapter supplies current-generation metadata; source URIs never enter the portable sidecar.
 */
data class LocalBackupSourceRef(val entry: BackupManifest.Entry, val sourceUri: String?)

data class LocalBackupSidecar(val bytes: ByteArray, val schemaVersion: Int = 1)

enum class LocalBackupOrganizationKind {
    Albums,
    Memories,
    Stacks,
    SmartAlbums,
    Documents,
    Archived,
    DateRules,
    PersonRules,
    Favorites,
    Preferences,
    AutomaticRules,
    PhotoEdits,
}

data class LocalBackupOrganizationCount(val kind: LocalBackupOrganizationKind, val count: Int)

data class LocalBackupOrganizationReview(
    val counts: List<LocalBackupOrganizationCount>,
    val sourceCount: Int,
    val omitted: Int,
    val unresolved: Int,
    val globalRuleCount: Int,
    val canRestore: Boolean,
    val totalSources: Int = sourceCount,
    val hasPreferences: Boolean = false,
    val hasAutomaticRules: Boolean = false,
) {
    val partial: Boolean
        get() = omitted > 0 || sourceCount < totalSources
}

data class LocalRestoreOrganizationOptions(
    val importGlobalRules: Boolean = false,
    val allowPartial: Boolean = false,
)

data class LocalRestoreGalleryResult(
    val files: Int,
    val importedObjects: Int,
    val skippedObjects: Int = 0,
)

interface LocalBackupOrganizationPort {
    suspend fun export(sources: List<LocalBackupSourceRef>): LocalBackupSidecar

    /** A separate explicit preference review, never an implicit part of media restoration. */
    suspend fun preferencesForReview(sidecar: ByteArray, manifest: BackupManifest): ByteArray? = null

    suspend fun review(sidecar: ByteArray, manifest: BackupManifest): LocalBackupOrganizationReview

    suspend fun beginRestore(
        sidecar: ByteArray,
        manifest: BackupManifest,
        options: LocalRestoreOrganizationOptions,
    ): LocalRestoreGallerySession
}

/**
 * Created by the app adapter. It owns only newly inserted MediaStore copies and newly imported
 * rows. stage must consume input synchronously within this call, never retain/close it, and verify
 * the created destination bytes before returning. commit applies reviewed metadata using the exact
 * new sourceId -> generation-bound MediaKey mapping. abort is idempotent and must preserve cleanup
 * errors.
 */
interface LocalRestoreGallerySession {
    suspend fun stage(entry: BackupManifest.Entry, input: InputStream)

    suspend fun commit(): LocalRestoreGalleryResult

    suspend fun abort()

    /**
     * Optional source-bound validation for durable partial cleanup; never creates a destination.
     */
    suspend fun verifyBeforeAbort(entry: BackupManifest.Entry, input: InputStream) {}

    /** Release process-local ownership without discarding durable verified destinations. */
    suspend fun pause() {
        abort()
    }
}

/** Task UUID is stable across process death and identifies one metadata transaction receipt. */
interface LocalBackupDurableOrganizationPort : LocalBackupOrganizationPort {
    suspend fun committedResult(operationId: String): LocalRestoreGalleryResult?

    suspend fun resumeRestore(
        operationId: String,
        sidecar: ByteArray,
        manifest: BackupManifest,
        options: LocalRestoreOrganizationOptions,
    ): LocalRestoreGallerySession
}

/** An interrupted partial was verified and removed; reopen this archive entry once. */
class LocalBackupTaskRetryEntry : java.io.IOException("Reopen verified interrupted entry")
