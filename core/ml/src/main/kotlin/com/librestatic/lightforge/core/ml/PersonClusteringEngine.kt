package com.librestatic.lightforge.core.ml

import androidx.room.withTransaction
import com.librestatic.lightforge.core.database.FaceEmbeddingEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.PersonClusterEntity
import com.librestatic.lightforge.core.database.PersonClusterProjectionEntity
import com.librestatic.lightforge.core.database.PersonConstraintEntity
import com.librestatic.lightforge.core.database.PersonFaceOverrideEntity
import com.librestatic.lightforge.core.database.PersonMembershipEntity
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.roundToInt

data class FaceIdentityKey(val volumeName: String, val mediaStoreId: Long, val faceOrdinal: Int) : Comparable<FaceIdentityKey> {
    override fun compareTo(other: FaceIdentityKey): Int =
        compareValuesBy(this, other, FaceIdentityKey::volumeName, FaceIdentityKey::mediaStoreId, FaceIdentityKey::faceOrdinal)
}

class PersonClusteringMlEngine(
    private val database: GalleryDatabase,
    private val permission: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MlTaskEngine {
    private val dao = database.personDao()
    override val task = MlTaskType.PersonClustering
    override val modelVersion = AlgorithmVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome = withContext(Dispatchers.IO) {
        require(limit in 1..250)
        val media = dao.pendingPersonClusterMedia(
            SFaceLiteRtEmbeddingInference.ModelVersion,
            AlgorithmVersion,
            limit,
        )
        if (media.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
        var processed = 0
        for (item in media) {
            if (!permission()) return@withContext MlChunkOutcome.PermissionLost
            dao.mediaEmbeddings(item.volumeName, item.mediaStoreId).forEach { embedding ->
                database.withTransaction { attach(embedding) }
            }
            processed += 1
        }
        if (media.size == limit) MlChunkOutcome.More(MediaKey(media.last().volumeName, media.last().mediaStoreId), processed)
        else MlChunkOutcome.Complete(processed)
    }

    private suspend fun attach(embedding: FaceEmbeddingEntity) {
        val key = embedding.key()
        val constraints = dao.constraintsForFace(key.volumeName, key.mediaStoreId, key.faceOrdinal)
        val forbidden = constraints.filter { it.relation == CannotLink }.mapNotNull { it.otherClusterId }.toSet()
        val preferred = dao.forcedClusterId(key.volumeName, key.mediaStoreId, key.faceOrdinal) ?: constraints.filter { it.relation == MustLink }
            .flatMap { listOfNotNull(it.preferredClusterId, it.otherClusterId) }
            .firstOrNull { it !in forbidden }
        val candidateMap = linkedMapOf<String, PersonClusterEntity>()
        preferred?.let { dao.cluster(it)?.also { cluster -> candidateMap[cluster.clusterId] = cluster } }
        PersonProjectionIndex.projections(embedding.quantizedVector).forEach { projection ->
            dao.projectionCandidates(
                AlgorithmVersion,
                projection.band,
                projection.q0 - ProjectionTolerance, projection.q0 + ProjectionTolerance,
                projection.q1 - ProjectionTolerance, projection.q1 + ProjectionTolerance,
                projection.q2 - ProjectionTolerance, projection.q2 + ProjectionTolerance,
                projection.q3 - ProjectionTolerance, projection.q3 + ProjectionTolerance,
                projection.q4 - ProjectionTolerance, projection.q4 + ProjectionTolerance,
                projection.q5 - ProjectionTolerance, projection.q5 + ProjectionTolerance,
                CandidatesPerBand,
            ).forEach { candidateMap.putIfAbsent(it.clusterId, it) }
        }
        val selected = if (preferred != null) {
            candidateMap[preferred]?.let { it to 1f }
        } else {
            val scored = mutableListOf<Pair<PersonClusterEntity, Float>>()
            candidateMap.values.filter { it.clusterId !in forbidden }.take(MaxCandidates).forEach { cluster ->
                if (dao.clusterMembershipCount(cluster.clusterId) == 0) return@forEach
                val centroidScore = CompactFaceEmbedding.cosine(embedding.quantizedVector, cluster.centroidVector)
                if (centroidScore >= MinimumCosine) {
                    val boundary = dao.boundaryEmbeddings(cluster.clusterId, BoundarySamples)
                    val minimum = boundary.minOfOrNull {
                        CompactFaceEmbedding.cosine(embedding.quantizedVector, it.quantizedVector)
                    } ?: centroidScore
                    if (minimum >= MinimumCosine) scored += cluster to minOf(centroidScore, minimum)
                }
            }
            scored.maxByOrNull { it.second }
        }
        val now = nowMillis()
        val stableId = key.stableClusterId()
        val cluster = selected?.first ?: dao.cluster(stableId) ?: PersonClusterEntity(
            clusterId = stableId, algorithmVersion = AlgorithmVersion,
            centroidVector = embedding.quantizedVector, memberCount = 0,
            displayName = null, isHidden = false, isUserEdited = false,
            createdAtMillis = now, updatedAtMillis = now,
        )
        val actualCount = dao.clusterMembershipCount(cluster.clusterId)
        val centroid = if (actualCount == 0) embedding.quantizedVector else {
            updatedCentroid(cluster.centroidVector, actualCount, embedding.quantizedVector)
        }
        val updated = cluster.copy(
            centroidVector = centroid,
            memberCount = actualCount + 1,
            updatedAtMillis = now,
        )
        dao.upsertCluster(updated)
        dao.upsertProjections(PersonProjectionIndex.entities(updated.clusterId, centroid))
        dao.upsertMembership(
            PersonMembershipEntity(
                key.volumeName, key.mediaStoreId, key.faceOrdinal, updated.clusterId,
                AlgorithmVersion, if (preferred == null) Auto else Manual,
                selected?.second ?: 1f, now,
            ),
        )
    }

    override suspend fun purgeDerivedData() {
        database.withTransaction {
            dao.deleteMeProfile()
            dao.purgeMemberships()
            dao.purgeClusters()
            dao.purgeConstraints()
            dao.purgeFaceOverrides()
        }
    }

    companion object {
        const val AlgorithmVersion = "sface-complete-link-projection-v1"
        const val MinimumCosine = 0.47f
        const val ProjectionTolerance = 3
        const val CandidatesPerBand = 256
        const val MaxCandidates = 1_024
        const val BoundarySamples = 8
        const val Auto = "AUTO"
        const val Manual = "MANUAL"
        const val MustLink = "MUST_LINK"
        const val CannotLink = "CANNOT_LINK"
    }
}

class PersonCorrectionRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.personDao()

    suspend fun rename(clusterId: String, name: String?) {
        val normalized = name?.trim()?.takeIf(String::isNotEmpty)?.take(80)
        check(dao.rename(clusterId, normalized, nowMillis()) == 1)
    }

    suspend fun hide(clusterId: String, hidden: Boolean) {
        check(dao.setHidden(clusterId, hidden, nowMillis()) == 1)
    }

    suspend fun merge(targetClusterId: String, sourceClusterIds: List<String>) = database.withTransaction {
        val sources = sourceClusterIds.distinct().filter { it != targetClusterId }
        require(sources.isNotEmpty() && sources.size <= 100)
        val targetAnchor = requireNotNull(dao.clusterAnchor(targetClusterId)).key()
        val constraints = sources.map { source ->
            val anchor = requireNotNull(dao.clusterAnchor(source)).key()
            constraint(targetAnchor, anchor, PersonClusteringMlEngine.MustLink, targetClusterId, nowMillis())
        }
        dao.upsertConstraints(constraints)
        upsertOverridesForCluster(targetClusterId, targetClusterId)
        sources.forEach { source ->
            upsertOverridesForCluster(source, targetClusterId)
            dao.moveClusterMemberships(source, targetClusterId)
            dao.deleteCluster(source)
        }
        dao.markEdited(targetClusterId, nowMillis())
        rebuildCluster(targetClusterId)
    }

    suspend fun split(clusterId: String, movedFaces: List<FaceIdentityKey>): String = database.withTransaction {
        val moved = movedFaces.distinct().sorted()
        require(moved.isNotEmpty() && moved.size <= 500)
        val current = requireNotNull(dao.cluster(clusterId))
        val retainedAnchor = dao.clusterMemberships(clusterId, 501).asSequence()
            .map(PersonMembershipEntity::key).firstOrNull { it !in moved }
            ?: error("A split must retain at least one face")
        moved.forEach { face -> check(dao.membership(face.volumeName, face.mediaStoreId, face.faceOrdinal)?.clusterId == clusterId) }
        val newClusterId = moved.manualClusterId()
        val firstEmbedding = requireNotNull(dao.mediaEmbeddings(moved.first().volumeName, moved.first().mediaStoreId)
            .firstOrNull { it.faceOrdinal == moved.first().faceOrdinal })
        val now = nowMillis()
        dao.upsertCluster(
            PersonClusterEntity(newClusterId, PersonClusteringMlEngine.AlgorithmVersion, firstEmbedding.quantizedVector, 0, null, false, true, now, now),
        )
        val relations = moved.map { face ->
            constraint(face, retainedAnchor, PersonClusteringMlEngine.CannotLink, null, now)
        } + moved.drop(1).map { face ->
            constraint(moved.first(), face, PersonClusteringMlEngine.MustLink, newClusterId, now)
        }
        dao.upsertConstraints(relations.distinctBy { it.canonicalId() })
        dao.upsertFaceOverrides(moved.map { face ->
            PersonFaceOverrideEntity(face.volumeName, face.mediaStoreId, face.faceOrdinal, newClusterId, now)
        })
        moved.forEach { face -> dao.moveMembership(face.volumeName, face.mediaStoreId, face.faceOrdinal, newClusterId) }
        dao.markEdited(clusterId, now)
        rebuildCluster(clusterId)
        rebuildCluster(newClusterId)
        newClusterId
    }

    private suspend fun rebuildCluster(clusterId: String) {
        val previous = requireNotNull(dao.cluster(clusterId))
        val sums = LongArray(CompactFaceEmbedding.Dimensions)
        var count = 0
        var after: FaceIdentityKey? = null
        do {
            val page = dao.clusterEmbeddingPage(
                clusterId, after?.volumeName, after?.mediaStoreId ?: Long.MIN_VALUE,
                after?.faceOrdinal ?: Int.MIN_VALUE, RebuildPage,
            )
            page.forEach { embedding ->
                embedding.quantizedVector.forEachIndexed { index, value -> sums[index] += value.toInt() }
                count += 1
            }
            after = page.lastOrNull()?.key()
        } while (page.size == RebuildPage)
        check(count > 0)
        val centroid = CompactFaceEmbedding.quantize(FloatArray(sums.size) { sums[it].toFloat() / count })
        dao.upsertCluster(previous.copy(centroidVector = centroid, memberCount = count, updatedAtMillis = nowMillis()))
        dao.deleteProjections(clusterId)
        dao.upsertProjections(PersonProjectionIndex.entities(clusterId, centroid))
    }

    private suspend fun upsertOverridesForCluster(sourceClusterId: String, targetClusterId: String) {
        var after: FaceIdentityKey? = null
        do {
            val page = dao.clusterMembershipPage(
                sourceClusterId, after?.volumeName, after?.mediaStoreId ?: Long.MIN_VALUE,
                after?.faceOrdinal ?: Int.MIN_VALUE, RebuildPage,
            )
            if (page.isNotEmpty()) {
                val now = nowMillis()
                dao.upsertFaceOverrides(page.map { membership ->
                    PersonFaceOverrideEntity(
                        membership.volumeName, membership.mediaStoreId, membership.faceOrdinal,
                        targetClusterId, now,
                    )
                })
            }
            after = page.lastOrNull()?.key()
        } while (page.size == RebuildPage)
    }

    private companion object { const val RebuildPage = 250 }
}

internal object PersonProjectionIndex {
    fun entities(clusterId: String, vector: ByteArray): List<PersonClusterProjectionEntity> =
        projections(vector).map { it.entity(clusterId) }

    fun projections(vector: ByteArray): List<Projection> {
        require(vector.size == CompactFaceEmbedding.Dimensions)
        return List(Bands) { band ->
            val values = IntArray(ValuesPerBand) { projection ->
                var state = seed(band, projection)
                var dot = 0L
                vector.forEach { value ->
                    state = xorshift(state)
                    dot += if (state and 1L == 0L) value.toInt() else -value.toInt()
                }
                ((dot + ProjectionRange) / BinWidth).toInt().coerceIn(0, 15)
            }
            Projection(band, values[0], values[1], values[2], values[3], values[4], values[5])
        }
    }

    data class Projection(val band: Int, val q0: Int, val q1: Int, val q2: Int, val q3: Int, val q4: Int, val q5: Int) {
        fun entity(clusterId: String) = PersonClusterProjectionEntity(clusterId, band, q0, q1, q2, q3, q4, q5)
    }

    private fun seed(band: Int, projection: Int) = -7046029254386353131L xor (band * 31L + projection * 131L)
    private fun xorshift(input: Long): Long {
        var value = input
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        return value xor (value shl 17)
    }

    private const val Bands = 4
    private const val ValuesPerBand = 6
    private const val ProjectionRange = 4_500L
    private const val BinWidth = 563L
}

private fun updatedCentroid(existing: ByteArray, count: Int, incoming: ByteArray): ByteArray =
    CompactFaceEmbedding.quantize(FloatArray(existing.size) { index ->
        (existing[index].toInt() * count + incoming[index].toInt()).toFloat()
    })

private fun FaceEmbeddingEntity.key() = FaceIdentityKey(volumeName, mediaStoreId, faceOrdinal)
private fun PersonMembershipEntity.key() = FaceIdentityKey(volumeName, mediaStoreId, faceOrdinal)

private fun FaceIdentityKey.stableClusterId(): String {
    val raw = "$volumeName:$mediaStoreId:$faceOrdinal".encodeToByteArray()
    return "person-" + MessageDigest.getInstance("SHA-256").digest(raw).take(12).joinToString("") { "%02x".format(it) }
}

private fun List<FaceIdentityKey>.manualClusterId(): String {
    val raw = joinToString("|") { "${it.volumeName}:${it.mediaStoreId}:${it.faceOrdinal}" }.encodeToByteArray()
    return "person-manual-" + MessageDigest.getInstance("SHA-256").digest(raw).take(12)
        .joinToString("") { "%02x".format(it) }
}

private fun constraint(
    first: FaceIdentityKey,
    second: FaceIdentityKey,
    relation: String,
    preferredClusterId: String?,
    now: Long,
): PersonConstraintEntity {
    require(first != second)
    val (left, right) = if (first < second) first to second else second to first
    return PersonConstraintEntity(
        left.volumeName, left.mediaStoreId, left.faceOrdinal,
        right.volumeName, right.mediaStoreId, right.faceOrdinal,
        relation, preferredClusterId, now,
    )
}

private fun PersonConstraintEntity.canonicalId() =
    "$leftVolumeName:$leftMediaStoreId:$leftFaceOrdinal|$rightVolumeName:$rightMediaStoreId:$rightFaceOrdinal"
