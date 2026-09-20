package com.ugallery.core.mediastore

/** Captured before document creation; the destination is attached durably before any write. */
data class MoveCopyDraft(
    val id: String,
    val targetVolume: String,
    val targetId: Long,
    val targetKind: String,
    val sourceUri: String,
    val treeUri: String,
    val name: String,
    val mime: String,
    val lastModifiedMillis: Long?,
    val bytes: Long,
    val sha256: String,
    val generationAdded: Long,
    val generationModified: Long,
    val destinationUri: String? = null,
    val grantReadAcquired: Boolean = false,
) {
    fun validated(): MoveCopyDraft {
        require(name.length in 1..255 && name == name.trim() && name != "." && name != "..")
        require(name.none { it == '/' || it == '\\' || it.isISOControl() })
        require(mime.length in 3..255 && mime.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")))
        require(lastModifiedMillis == null || lastModifiedMillis >= 0L)
        // Reuse the exact proof identity checks without inventing a usable destination.
        // This validation-only URI never escapes this method or opens a provider.
        proof(destinationUri ?: "$treeUri/document/$id").validated()
        return this
    }

    /** Caller must complete full source/destination readback before publishing this proof. */
    fun toProof(): VerifiedMoveProof {
        validated()
        return proof(requireNotNull(destinationUri) { "Copy destination has not been recorded" }).validated()
    }

    private fun proof(destination: String) = VerifiedMoveProof(id, targetVolume, targetId, targetKind,
        sourceUri, destination, treeUri, bytes, sha256, generationAdded, generationModified, grantReadAcquired)
}
