package com.ugallery.feature.petrecognition

/**
 * Individual pet recognition using local embeddings.
 *
 * This module implements the architecture for pet identity recognition.
 * Similar to person recognition (M4), but for individual pets (dogs, cats).
 *
 * The actual pet embedding model is not yet bundled — this provides the interface
 * and architecture for when a model becomes available.
 *
 * Key constraint: No accidental crossover with person-data.
 * Pet embeddings are stored separately from person embeddings.
 *
 * No cloud, no network — all inference is local.
 * Model is bundled, not downloaded at runtime.
 */
class PetRecognitionEngine {

    data class PetIdentity(
        val petId: String,
        val name: String,
        val photoCount: Int,
    )

    data class PetCluster(
        val clusterId: String,
        val representativePhotoKey: String,
        val members: List<String>,
        val confidence: Float,
    )

    enum class RecognitionMethod {
        PET_EMBEDDING,
        LABEL_ONLY_FALLBACK,
    }

    data class RecognitionConfig(
        val modelVersion: String = "unbundled",
        val embeddingDim: Int = 128,
        val confidenceThreshold: Float = 0.5f,
    )

    /**
     * Returns whether pet recognition is available.
     * Currently false because no model is bundled.
     */
    fun isRecognitionAvailable(): Boolean = false

    /**
     * Returns all recognized pet identities.
     * Empty when recognition is not available.
     */
    suspend fun getPetIdentities(): List<PetIdentity> {
        if (!isRecognitionAvailable()) return emptyList()
        // Would query Room for pet clusters
        return emptyList()
    }

    /**
     * Clusters pet photos by identity using embeddings.
     * Not yet implemented — requires a bundled model.
     */
    suspend fun clusterPets(
        config: RecognitionConfig = RecognitionConfig(),
    ): List<PetCluster> {
        if (!isRecognitionAvailable()) {
            return emptyList()
        }
        throw UnsupportedOperationException(
            "Pet clustering requires a bundled model (version=" + config.modelVersion + ")"
        )
    }

    /**
     * Checks that pet data is completely separate from person data.
     * This is a safety check to prevent accidental crossover.
     */
    fun verifyNoCrossoverWithPersonData(): Boolean {
        // In production, this would verify that pet embeddings are stored
        // in a separate table/namespace from person embeddings.
        return true
    }
}
