package com.librestatic.lightforge.feature.petrecognition

import android.net.Uri
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.flow.Flow

/** Independent pet namespace. No person IDs, person embeddings, or face tables belong to this port. */
enum class PetSpecies { Cat, Dog, Uncertain }

data class PetBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && right > left && bottom > top)
    }
}

data class PetMediaSource(val key: MediaKey, val generationModified: Long, val uri: Uri, val generationAdded: Long = 0) {
    init { require(generationModified >= 0 && generationAdded >= 0) }
}

data class PetSummary(
    val revision: Long,
    val enabled: Boolean,
    val modelFingerprint: String?,
    val identityCount: Long,
    val observationCount: Long,
    val unassignedCount: Long,
    val excludedCount: Long,
    val undoToken: String?,
)

data class PetIdentityCard(
    val id: String,
    val species: PetSpecies,
    val name: String?,
    val photoCount: Long,
    val representative: PetObservationCard?,
) { init { require(species != PetSpecies.Uncertain) } }

/** UI records deliberately carry no embedding arrays. source generation must still be current. */
data class PetObservationCard(
    val id: String,
    val source: PetMediaSource,
    val box: PetBox,
    val species: PetSpecies,
    val detectorScore: Float,
    val identityId: String?,
    val excluded: Boolean,
    val modelFingerprint: String,
)

data class PetReferenceEmbedding(val observationId: String, val identityId: String, val embedding: FloatArray)

data class PetAnalyzedObservation(
    /** A fresh UUID for a genuinely new observation, not a MediaStore ID reused as an identity. */
    val id: String,
    val box: PetBox,
    val species: PetSpecies,
    val detectorScore: Float,
    val embedding: FloatArray,
) {
    init {
        require(id.isNotBlank() && detectorScore.isFinite() && detectorScore in 0f..1f)
        require(embedding.size == 512 && embedding.all(Float::isFinite))
        require(kotlin.math.abs(embedding.sumOf { (it * it).toDouble() } - 1.0) <= 0.02)
    }
}

data class PetPage<T>(val items: List<T>, val nextId: String?)
data class PetSourcePage(val items: List<PetMediaSource>, val nextKey: MediaKey?)

enum class PetObservationFilter { All, Unassigned, Excluded }

sealed interface PetEdit {
    data class CreateIdentity(val species: PetSpecies, val name: String?, val observationIds: Set<String>) : PetEdit
    data class Rename(val identityId: String, val name: String?) : PetEdit
    data class Merge(val targetIdentityId: String, val otherIdentityIds: Set<String>) : PetEdit
    data class Split(val identityId: String, val observationIds: Set<String>, val name: String?) : PetEdit
    data class Exclude(val observationIds: Set<String>) : PetEdit
    data class Restore(val observationIds: Set<String>) : PetEdit
    /** Explicit review; only unassigned observations may change species, and target must be Cat or Dog. */
    data class SetSpecies(val observationIds: Set<String>, val species: PetSpecies) : PetEdit
    /** Explicit user review only: cosine similarity does not silently establish an identity. */
    data class AcceptSuggestion(val identityId: String, val observationIds: Set<String>) : PetEdit
}

data class PetEditResult(val applied: Boolean, val revision: Long, val undoToken: String? = null)

interface PetIdentityRepository {
    val summary: Flow<PetSummary>
    suspend fun currentSummary(): PetSummary
    /** Disabling after UI confirmation erases the pet namespace + undo atomically; never media/person data. */
    suspend fun setAnalysisEnabled(expectedRevision: Long, enabled: Boolean, modelFingerprint: String?): Boolean
    suspend fun identitiesPage(afterId: String? = null, limit: Int = 40): PetPage<PetIdentityCard>
    suspend fun observationsPage(
        identityId: String? = null,
        filter: PetObservationFilter = PetObservationFilter.All,
        afterId: String? = null,
        limit: Int = 40,
    ): PetPage<PetObservationCard>
    /** Keyset order (volumeName, mediaStoreId), accessible non-trash images only, limit in 1..64. */
    suspend fun eligibleSources(afterKey: MediaKey? = null, limit: Int = 64): PetSourcePage
    suspend fun isCurrent(source: PetMediaSource): Boolean
    /** True only if this exact source generation/model was already committed, including zero detections. */
    suspend fun isAnalyzed(source: PetMediaSource, modelFingerprint: String): Boolean
    /**
     * Atomic: recheck opt-in + selected fingerprint + source generation/access/not-trash.
     * Commit the completed photo (including zero detections), never partial observations.
     * Reject conflicting observation IDs. A repeat exact source/model must preserve reviewed decisions.
     */
    suspend fun commitAnalysis(source: PetMediaSource, modelFingerprint: String, observations: List<PetAnalyzedObservation>): Boolean
    /** One current, non-excluded observation in this exact model space; never a whole-library UI snapshot. */
    suspend fun observationEmbedding(observationId: String, modelFingerprint: String): FloatArray?
    /** Paginated current non-excluded embeddings for the same species and exact model space. Limit <=64. */
    suspend fun referenceEmbeddingsPage(
        species: PetSpecies,
        modelFingerprint: String,
        afterId: String? = null,
        limit: Int = 64,
    ): PetPage<PetReferenceEmbedding>
    /**
     * Revision CAS; <=2000 selected observations/identities, names trimmed <=80 characters.
     * Validate current source generations, same Cat/Dog species, and no duplicate identity in one photo.
     * Uncertain observations remain unassigned until an explicit SetSpecies edit; groups are never Uncertain.
     * Preserve names/groups on reanalysis. Save a pet-only undo record atomically with this edit.
     */
    suspend fun edit(expectedRevision: Long, edit: PetEdit): PetEditResult
    /** Only the exact latest token/revision; stale undo must not overwrite subsequent edits. */
    suspend fun undo(expectedRevision: Long, token: String): PetEditResult
}
