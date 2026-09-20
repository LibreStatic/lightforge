package com.ugallery.core.remotestorage

/** Scoped directory views borrow their parent's connection; callers close children before parent. */
interface RemoteManagedConnection : RemoteConnection {
    /** Every segment is one portable child; no paths, parent traversal, symlinks or reparse points. */
    fun directory(segments: List<String>, create: Boolean = false): RemoteManagedConnection

    /**
     * Move an OWNED, previously verified file to a write-ahead-journaled sibling without replacing.
     * Used in both directions for recoverable mirror quarantine, never permanent deletion.
     * The caller must persist the exact pair/expected digest before calling, including retries.
     * A path-based protocol may detect a concurrent replacement only AFTER its rename: preserve
     * all bytes, attempt no-replace restoration, and return an explicit conflict, not success.
     */
    fun moveManagedNoReplace(source: String, destination: String, expected: RemoteDigest): RemoteManagedMove
}

enum class RemoteManagedMoveState {
    VerifiedMoved,
    AlreadyMoved,
    SourceChanged,
    TargetOccupied,
    ConflictRestored,
    RetainedAmbiguous,
}

data class RemoteManagedMove(
    val state: RemoteManagedMoveState,
    val source: String,
    val destination: String,
    val observedDestination: RemoteDigest? = null,
) {
    val verified: Boolean
        get() = state == RemoteManagedMoveState.VerifiedMoved || state == RemoteManagedMoveState.AlreadyMoved
}
