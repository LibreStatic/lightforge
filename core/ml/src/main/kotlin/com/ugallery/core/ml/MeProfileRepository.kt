package com.ugallery.core.ml

import androidx.room.withTransaction
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MeMatchEntity
import com.ugallery.core.database.MeProfileEntity
import com.ugallery.core.database.MeReferenceEntity

class MeProfileRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.personDao()

    suspend fun selectReferences(references: List<FaceIdentityKey>) = database.withTransaction {
        val unique = references.distinct()
        require(unique.size in 1..MaximumReferences)
        val embeddings = unique.map { key ->
            requireNotNull(dao.embedding(key.volumeName, key.mediaStoreId, key.faceOrdinal))
        }
        val modelVersion = embeddings.map { it.embeddingModelVersion }.distinct().single()
        val sums = FloatArray(CompactFaceEmbedding.Dimensions)
        embeddings.forEach { embedding ->
            embedding.quantizedVector.forEachIndexed { index, value -> sums[index] += value.toInt() }
        }
        val centroid = CompactFaceEmbedding.quantize(sums)
        val now = nowMillis()
        dao.deleteMeProfile(ProfileId)
        dao.upsertMeProfile(
            MeProfileEntity(
                ProfileId, modelVersion, centroid, PersonClusteringMlEngine.MinimumCosine,
                unique.size, Rebuilding, null, null, null, now,
            ),
        )
        dao.upsertMeReferences(unique.map { key ->
            MeReferenceEntity(ProfileId, key.volumeName, key.mediaStoreId, key.faceOrdinal, now)
        })
    }

    suspend fun rebuildMatchesChunk(limit: Int): Boolean {
        require(limit in 1..500)
        val profile = dao.meProfile(ProfileId) ?: return true
        if (profile.state == Ready) return true
        if (dao.meReferenceCount(ProfileId) == 0) {
            reset()
            return true
        }
        val page = dao.embeddingPage(
            profile.embeddingModelVersion,
            profile.afterVolumeName,
            profile.afterMediaStoreId ?: Long.MIN_VALUE,
            profile.afterFaceOrdinal ?: Int.MIN_VALUE,
            limit,
        )
        val now = nowMillis()
        val matches = page.mapNotNull { embedding ->
            val similarity = CompactFaceEmbedding.cosine(profile.centroidVector, embedding.quantizedVector)
            if (similarity >= profile.matchThreshold) {
                MeMatchEntity(ProfileId, embedding.volumeName, embedding.mediaStoreId, embedding.faceOrdinal, similarity, now)
            } else null
        }
        database.withTransaction {
            if (matches.isNotEmpty()) dao.upsertMeMatches(matches)
            val last = page.lastOrNull()
            dao.upsertMeProfile(
                profile.copy(
                    state = if (page.size < limit) Ready else Rebuilding,
                    afterVolumeName = last?.volumeName ?: profile.afterVolumeName,
                    afterMediaStoreId = last?.mediaStoreId ?: profile.afterMediaStoreId,
                    afterFaceOrdinal = last?.faceOrdinal ?: profile.afterFaceOrdinal,
                    updatedAtMillis = now,
                ),
            )
        }
        return page.size < limit
    }

    suspend fun reset() = database.withTransaction {
        dao.deleteMeProfile(ProfileId)
    }

    suspend fun state(): MeProfileState? {
        val profile = dao.meProfile(ProfileId) ?: return null
        if (dao.meReferenceCount(ProfileId) != profile.referenceCount) {
            reset()
            return null
        }
        return MeProfileState(profile.referenceCount, profile.state == Ready, dao.meMatchCount(ProfileId))
    }

    data class MeProfileState(val referenceCount: Int, val isReady: Boolean, val matchCount: Int)

    companion object {
        const val ProfileId = 0
        const val MaximumReferences = 20
        const val Rebuilding = "REBUILDING"
        const val Ready = "READY"
    }
}
