package com.librestatic.lightforge.feature.petrecognition

/** One unassigned observation as seen by the grouping pass. [photoKey] identifies the source photo. */
class PetGroupCandidate(val id: String, val photoKey: String, val species: PetSpecies, val embedding: FloatArray) {
    init { require(id.isNotBlank() && species != PetSpecies.Uncertain) }
}

/** A possible pet: same-species observations that look alike, ordered as found. Never an identity claim. */
data class PetGroupSuggestion(val species: PetSpecies, val observationIds: List<String>, val covers: List<PetObservationCard> = emptyList()) {
    init { require(species != PetSpecies.Uncertain && observationIds.size >= PetGroupSuggestions.MinClusterSize) }
    val key: String get() = observationIds.first()
}

/**
 * Suggests groups of unassigned observations by greedy leader clustering on cosine similarity.
 * Each observation joins the most similar existing leader at or above [SimilarityThreshold], else starts
 * a new cluster. It is a ranking aid for the user's review, not an identity decision: nothing is created
 * until the user taps "Create group", so there is no automatic identity claim.
 */
object PetGroupSuggestions {
    /**
     * Deliberately conservative and uncalibrated: the re-identification embedding has no validated
     * acceptance threshold, so a high value favors missed suggestions over wrong ones, which the user
     * can still group by hand from the review tab.
     */
    const val SimilarityThreshold = 0.8f
    const val MinClusterSize = 2
    /** Bounds time and memory: at most this many embeddings per species are loaded and clustered. */
    const val MaxObservationsPerSpecies = 2000
    private const val PageSize = 64

    /** Pure and deterministic for a given input order. Uncertain species are never grouped. */
    fun cluster(candidates: List<PetGroupCandidate>, threshold: Float = SimilarityThreshold): List<PetGroupSuggestion> =
        listOf(PetSpecies.Cat, PetSpecies.Dog).flatMap { species ->
            val leaders = ArrayList<FloatArray>()
            val members = ArrayList<MutableList<PetGroupCandidate>>()
            val photos = ArrayList<MutableSet<String>>()
            for (candidate in candidates.asSequence().filter { it.species == species }.take(MaxObservationsPerSpecies)) {
                var best = -1
                var bestScore = threshold
                for (index in leaders.indices) {
                    // A pet cannot appear twice in one photo, so such a cluster could not become a group.
                    if (candidate.photoKey in photos[index]) continue
                    val score = PetSimilarityRanking.cosine(candidate.embedding, leaders[index])
                    if (score >= bestScore) { best = index; bestScore = score }
                }
                if (best < 0) { leaders += candidate.embedding; members += mutableListOf(candidate); photos += mutableSetOf(candidate.photoKey) }
                else { members[best] += candidate; photos[best] += candidate.photoKey }
            }
            members.filter { it.size >= MinClusterSize }.map { PetGroupSuggestion(species, it.map(PetGroupCandidate::id)) }
        }.sortedByDescending { it.observationIds.size }

    /** Pages the repository's unassigned embeddings (bounded) and clusters them. Call off the main thread. */
    suspend fun load(repository: PetIdentityRepository, modelFingerprint: String): List<PetGroupSuggestion> {
        val cards = HashMap<String, PetObservationCard>()
        return cluster(
            listOf(PetSpecies.Cat, PetSpecies.Dog).flatMap { species ->
                val loaded = ArrayList<PetGroupCandidate>()
                var after: String? = null
                do {
                    val page = repository.unassignedEmbeddingsPage(species, modelFingerprint, after, PageSize)
                    require(page.items.size <= PageSize)
                    page.items.forEach { cards[it.observation.id] = it.observation; loaded += PetGroupCandidate(it.observation.id, "${it.observation.source.key}:${it.observation.source.generationAdded}", it.observation.species, it.embedding) }
                    check(page.nextId == null || page.nextId != after) { "Pet embedding cursor did not advance" }
                    after = page.nextId
                } while (after != null && loaded.size < MaxObservationsPerSpecies)
                loaded
            },
        ).map { suggestion -> suggestion.copy(covers = suggestion.observationIds.take(CoverCount).mapNotNull(cards::get)) }
    }
    const val CoverCount = 3
}
