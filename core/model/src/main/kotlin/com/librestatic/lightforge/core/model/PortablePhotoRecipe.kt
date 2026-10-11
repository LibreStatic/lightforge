package com.librestatic.lightforge.core.model

/**
 * A recipe for one verified source instance; destination keys and recipe IDs are never portable.
 */
data class PortablePhotoRecipe(
    val sourceId: String,
    val revision: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val operations: List<String>,
) {
    fun validate(): PortablePhotoRecipe = apply {
        PortableOrganizationSnapshot.uuid(sourceId)
        require(revision >= 0 && createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis)
        require(operations.size <= MaximumOperations)
        operations.forEach { encoded ->
            require(
                encoded.length <= MaximumOperationBytes &&
                    encoded.toByteArray(Charsets.UTF_8).size <= MaximumOperationBytes
            )
            // The local persistence decoder tolerates old or extra tokens. Portable input must
            // be exactly a current known operation, including flags, raw version and float form.
            val decoded = EditOperationCodec.decode(encoded)
            val canonical = EditOperationCodec.encode(decoded)
            // RAW operations exported before camera framing existed lack the trailing flag, which
            // means framing on; they stay importable.
            val legacyRaw = decoded is EditOperation.RawDevelop && decoded.settings.cameraFraming &&
                canonical.removeSuffix(",1") == encoded
            require(canonical == encoded || legacyRaw) {
                "Noncanonical photo operation"
            }
        }
    }

    companion object {
        const val MaximumOperations = 500
        const val MaximumOperationBytes = 1024
    }
}
