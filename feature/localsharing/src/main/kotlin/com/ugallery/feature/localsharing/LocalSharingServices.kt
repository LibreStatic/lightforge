package com.ugallery.feature.localsharing

import java.io.File

/** App owns selection, derivative rendering and exact-generation Gallery import receipts. */
class LocalSharingServices(
    val sourcePort: LocalSharingSourcePort,
    val importPort: LocalSharingImportPort,
    val networkAllowed: () -> Boolean,
)

interface LocalSharingSourcePort {
    /** Retain exact selected URI grants during the picker confirmation, before scheduling. */
    suspend fun retain(selection: List<String>)

    /**
     * Durable immutable copies ONLY under destination. Stable sourceId distinguishes equal-byte
     * originals. revision identifies the selected current rendition. If stripLocation, verify the
     * derivative and set sanitized=true; a sanitizer error MUST NOT return the original. Caller
     * retries into a fresh attempt subdirectory after an interrupted preparation.
     */
    suspend fun prepare(
        selection: List<String>,
        stripLocation: Boolean,
        destination: File,
        check: () -> Unit,
    ): List<LocalSharingPreparedSource>
}

data class LocalSharingPreparedSource(val entry: LocalSharingEntry, val fileName: String)

data class LocalSharingReceivedFile(val entry: LocalSharingEntry, val file: File)

enum class LocalSharingConflictChoice {
    KeepBoth,
    UseNewest,
}

enum class LocalSharingImportDisposition {
    Add,
    AlreadyReceived,
    Conflict,
}

data class LocalSharingImportItem(
    val sourceId: String,
    val disposition: LocalSharingImportDisposition,
    val existingModifiedMillis: Long? = null,
)

interface LocalSharingImportPort {
    /** Peer ID is the pinned certificate SHA256, never a supplied MediaStore ID. */
    suspend fun review(
        peerId: String,
        transferId: String,
        manifest: LocalSharingManifest,
    ): List<LocalSharingImportItem>

    /**
     * Idempotent completed Gallery publication receipt; files stay parent-owned for recovery. All
     * publication verifies exact bytes and preserves prior originals. UseNewest changes the active
     * logical version only if incoming modifiedMillis is newer; never silently overwrites.
     * AlreadyReceived means this peer/sourceId/revision was successfully imported, not SHA alone.
     */
    suspend fun enqueueOnce(
        peerId: String,
        transferId: String,
        manifest: LocalSharingManifest,
        files: List<LocalSharingReceivedFile>,
        choices: Map<String, LocalSharingConflictChoice>,
    ): String

    suspend fun existing(transferId: String): String?

    /**
     * Explicit cancellation only; verify and abort owned pending publications before input cleanup.
     */
    suspend fun cancel(transferId: String) {}
}
