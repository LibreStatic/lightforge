package com.ugallery.core.mediastore

import java.net.URI
import java.util.UUID

/** Immutable identity of a verified copy. This record is not authorization to delete a source. */
data class VerifiedMoveProof(
    val id: String,
    val targetVolume: String,
    val targetId: Long,
    /** Image or Video; independent of Android/model classes for durable decoding. */
    val targetKind: String,
    val sourceUri: String,
    val destinationUri: String,
    val treeUri: String,
    val bytes: Long,
    val sha256: String,
    val generationAdded: Long,
    val generationModified: Long,
    val grantReadAcquired: Boolean = false,
) {
    fun validated(): VerifiedMoveProof {
        require(UUID.fromString(id).toString() == id)
        require(targetVolume.matches(Regex("[a-z0-9][a-z0-9_-]{0,127}")))
        require(targetId > 0L && targetKind in setOf("Image", "Video"))
        require(bytes >= 0L && sha256.matches(Regex("[0-9a-f]{64}")))
        require(generationAdded >= 0L && generationModified >= generationAdded)
        val source = contentUri(sourceUri)
        val destination = contentUri(destinationUri)
        val tree = contentUri(treeUri)
        val kindPath = if (targetKind == "Image") "images" else "video"
        require(sourceUri == "content://media/$targetVolume/$kindPath/media/$targetId")
        require(source != destination)
        require(tree.rawAuthority == destination.rawAuthority)
        val treeParts = tree.rawPath.split('/')
        require(treeParts.size == 3 && treeParts[1] == "tree" && treeParts[2].isNotEmpty())
        val destinationParts = destination.rawPath.split('/')
        require((destinationParts.size == 3 && destinationParts[1] == "document" && destinationParts[2].isNotEmpty()) ||
            (destinationParts.size == 5 && destinationParts[1] == "tree" && destinationParts[2] == treeParts[2] &&
                destinationParts[3] == "document" && destinationParts[4].isNotEmpty()))
        return this
    }

    private fun contentUri(value: String): URI {
        require(value.length in 1..8_192 && value.none { it.isISOControl() })
        return URI(value).also {
            require(it.scheme == "content" && !it.rawAuthority.isNullOrEmpty())
            require(it.rawUserInfo == null && it.port == -1 && it.rawQuery == null && it.rawFragment == null)
            require(it.rawPath != null && it.normalize() == it)
        }
    }
}
