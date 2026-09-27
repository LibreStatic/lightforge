package com.librestatic.lightforge.core.ml

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.SimilarityEdgeEntity
import com.librestatic.lightforge.core.database.SimilarityExclusionEntity
import com.librestatic.lightforge.core.database.SimilarityFeatureEntity
import com.librestatic.lightforge.core.database.SimilarityMembershipEntity
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

data class RawSimilarityFeature(
    val pHash: Long,
    val compactEmbedding: ByteArray,
    val blurScore: Float,
)

fun interface SimilarityFeatureExtractor {
    suspend fun extract(item: MediaItemEntity): RawSimilarityFeature
}

class NativeSimilarityFeatureExtractor(
    private val resolver: ContentResolver,
) : SimilarityFeatureExtractor {
    override suspend fun extract(item: MediaItemEntity): RawSimilarityFeature = withContext(Dispatchers.Default) {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(item.volumeName),
            item.mediaStoreId,
        )
        val thumbnail = resolver.loadThumbnail(uri, Size(ThumbnailSize, ThumbnailSize), CancellationSignal())
        val bitmap = Bitmap.createScaledBitmap(thumbnail, DescriptorSize, DescriptorSize, true)
        try {
            val pixels = IntArray(DescriptorSize * DescriptorSize)
            bitmap.getPixels(pixels, 0, DescriptorSize, 0, 0, DescriptorSize, DescriptorSize)
            val gray = DoubleArray(pixels.size) { index ->
                val color = pixels[index]
                (0.299 * ((color shr 16) and 0xff)) +
                    (0.587 * ((color shr 8) and 0xff)) +
                    (0.114 * (color and 0xff))
            }
            RawSimilarityFeature(perceptualHash(gray), compactEmbedding(pixels), laplacianVariance(gray))
        } finally {
            if (bitmap !== thumbnail) bitmap.recycle()
            thumbnail.recycle()
        }
    }

    private fun perceptualHash(gray: DoubleArray): Long {
        val coefficients = DoubleArray(HashSide * HashSide)
        for (v in 0 until HashSide) for (u in 0 until HashSide) {
            var sum = 0.0
            for (y in 0 until DescriptorSize) for (x in 0 until DescriptorSize) {
                sum += gray[y * DescriptorSize + x] *
                    Cosine[u][x] * Cosine[v][y]
            }
            coefficients[v * HashSide + u] = sum
        }
        val median = coefficients.drop(1).sorted()[coefficients.size / 2]
        var hash = 0L
        coefficients.forEachIndexed { index, value -> if (value >= median) hash = hash or (1L shl index) }
        return hash
    }

    private fun compactEmbedding(pixels: IntArray): ByteArray {
        val result = ByteArray(GridSide * GridSide * 3)
        val cell = DescriptorSize / GridSide
        var output = 0
        for (gy in 0 until GridSide) for (gx in 0 until GridSide) {
            var red = 0L; var green = 0L; var blue = 0L
            for (y in gy * cell until (gy + 1) * cell) for (x in gx * cell until (gx + 1) * cell) {
                val color = pixels[y * DescriptorSize + x]
                red += (color shr 16) and 0xff
                green += (color shr 8) and 0xff
                blue += color and 0xff
            }
            val count = cell * cell
            result[output++] = (red / count).toByte()
            result[output++] = (green / count).toByte()
            result[output++] = (blue / count).toByte()
        }
        return result
    }

    private fun laplacianVariance(gray: DoubleArray): Float {
        val values = ArrayList<Double>((DescriptorSize - 2) * (DescriptorSize - 2))
        for (y in 1 until DescriptorSize - 1) for (x in 1 until DescriptorSize - 1) {
            val center = gray[y * DescriptorSize + x]
            values += gray[(y - 1) * DescriptorSize + x] + gray[(y + 1) * DescriptorSize + x] +
                gray[y * DescriptorSize + x - 1] + gray[y * DescriptorSize + x + 1] - (4 * center)
        }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) }.div(values.size).toFloat()
    }

    companion object {
        const val ThumbnailSize = 256
        const val DescriptorSize = 32
        const val HashSide = 8
        const val GridSide = 4
        private val Cosine = Array(HashSide) { frequency ->
            DoubleArray(DescriptorSize) { position ->
                cos(((2 * position + 1) * frequency * PI) / (2 * DescriptorSize))
            }
        }
    }
}

class SimilarityMlEngine(
    database: GalleryDatabase,
    private val extractor: SimilarityFeatureExtractor,
    private val permission: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MlTaskEngine {
    private val dao = database.libraryDao()
    private val clusters = SimilarityClusterManager(database)
    override val task = MlTaskType.Similarity
    override val modelVersion = AlgorithmVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome = withContext(Dispatchers.IO) {
        val candidates = dao.pendingSimilarityCandidates(modelVersion, limit)
        if (candidates.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
        for (candidate in candidates) {
            if (!permission()) return@withContext MlChunkOutcome.PermissionLost
            val previousCluster = dao.similarityMembership(candidate.volumeName, candidate.mediaStoreId)?.clusterId
            val raw = try {
                extractor.extract(candidate)
            } catch (_: SecurityException) {
                return@withContext MlChunkOutcome.PermissionLost
            } catch (_: java.io.IOException) {
                // Missing or undecodable bytes (FileNotFoundException, ImageDecoder.DecodeException)
                // are permanent for this generation. Store a neutral feature so the item leaves the
                // pending query, and exclude it so it never joins a stack or the blurry list.
                dao.upsertSimilarityFeature(SkippedFeature.entity(candidate, nowMillis()))
                dao.excludeSimilarity(SimilarityExclusionEntity(candidate.volumeName, candidate.mediaStoreId, nowMillis()))
                previousCluster?.let { clusters.rebuild(it) }
                continue
            }
            val feature = raw.entity(candidate, nowMillis())
            dao.upsertSimilarityFeature(feature)
            previousCluster?.let { clusters.rebuild(it) }
            if (!dao.isSimilarityExcluded(candidate.volumeName, candidate.mediaStoreId)) {
                val scored = dao.similarityLshCandidates(
                    modelVersion,
                    candidate.volumeName,
                    candidate.mediaStoreId,
                    feature.lsh0,
                    feature.lsh1,
                    feature.lsh2,
                    feature.lsh3,
                    MaxCandidates,
                ).map { it to similarity(feature, it) }
                    .filter { it.second >= SimilarityThreshold }
                    .sortedByDescending { it.second }
                clusters.attach(feature, scored)
            }
        }
        MlChunkOutcome.More(candidates.last().key(), candidates.size)
    }

    override suspend fun purgeDerivedData() {
        dao.purgeSimilarityFeatures()
        dao.purgeSimilarityExclusions()
    }

    companion object {
        const val AlgorithmVersion = "phash64-rgb48-v1"
        const val MaxCandidates = 256
        const val SimilarityThreshold = 0.84f
        private val SkippedFeature = RawSimilarityFeature(0L, ByteArray(0), Float.MAX_VALUE)
    }
}

internal class SimilarityClusterManager(database: GalleryDatabase) {
    private val dao = database.libraryDao()

    suspend fun attach(
        feature: SimilarityFeatureEntity,
        scoredCandidates: List<Pair<SimilarityFeatureEntity, Float>>,
    ) {
        if (scoredCandidates.isEmpty()) {
            dao.deleteSimilarityMembership(feature.volumeName, feature.mediaStoreId)
            return
        }
        val candidateMemberships = scoredCandidates.associate { (candidate, _) ->
            candidate.key() to dao.similarityMembership(candidate.volumeName, candidate.mediaStoreId)
        }
        val existingClusters = candidateMemberships.values.mapNotNull { it?.clusterId }.distinct()
        val combinedSize = existingClusters.sumOf { dao.similarityClusterSize(it) } +
            candidateMemberships.values.count { it == null } + 1
        val allowedClusters = if (combinedSize <= MaxClusterSize) existingClusters.toSet()
        else setOfNotNull(candidateMemberships[scoredCandidates.first().first.key()]?.clusterId)
        val selected = scoredCandidates.filter { (candidate, _) ->
            candidateMemberships[candidate.key()]?.clusterId?.let { it in allowedClusters } ?: true
        }.take(MaxClusterSize - 1)
        if (selected.isEmpty()) return
        val clusterId = (allowedClusters + selected.map { it.first.key().stableId() } + feature.key().stableId()).min()
        allowedClusters.filter { it != clusterId }.forEach { dao.moveSimilarityCluster(it, clusterId) }
        dao.upsertSimilarityEdges(selected.map { (candidate, score) -> edge(feature, candidate, score) })
        val memberships = selected.map { (candidate, score) ->
            val current = candidateMemberships[candidate.key()]
            SimilarityMembershipEntity(
                candidate.volumeName,
                candidate.mediaStoreId,
                clusterId,
                maxOf(score, current?.bestScore ?: 0f),
            )
        } + SimilarityMembershipEntity(
            feature.volumeName,
            feature.mediaStoreId,
            clusterId,
            selected.maxOf { it.second },
        )
        dao.upsertSimilarityMemberships(memberships)
    }

    suspend fun rebuild(clusterId: String) {
        val features = dao.similarityClusterFeatures(clusterId, MaxClusterSize + 1)
        if (features.size > MaxClusterSize) return
        val edges = dao.similarityClusterEdges(clusterId)
        dao.deleteSimilarityClusterMemberships(clusterId)
        val byKey = features.associateBy(SimilarityFeatureEntity::key)
        val adjacency = features.associate { it.key() to mutableSetOf<MediaKey>() }
        edges.forEach { edge ->
            val a = MediaKey(edge.aVolumeName, edge.aMediaStoreId)
            val b = MediaKey(edge.bVolumeName, edge.bMediaStoreId)
            if (a in adjacency && b in adjacency) {
                adjacency.getValue(a) += b
                adjacency.getValue(b) += a
            }
        }
        val remaining = byKey.keys.toMutableSet()
        while (remaining.isNotEmpty()) {
            val queue = ArrayDeque<MediaKey>()
            val component = mutableSetOf<MediaKey>()
            queue += remaining.first()
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                if (!remaining.remove(key)) continue
                component += key
                adjacency.getValue(key).forEach(queue::addLast)
            }
            if (component.size > 1) {
                val id = component.minOf(MediaKey::stableId)
                dao.upsertSimilarityMemberships(component.map { key ->
                    val best = edges.asSequence().filter { it.touches(key) }.maxOfOrNull { it.score } ?: 0f
                    SimilarityMembershipEntity(key.volumeName, key.mediaStoreId, id, best)
                })
            }
        }
    }

    companion object { const val MaxClusterSize = 500 }
}

data class SimilarityStack(
    val id: String,
    val memberCount: Long,
    val bestScore: Float,
    val recommendedKeep: MediaKey,
)

class SimilarityRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()
    private val clusters = SimilarityClusterManager(database)

    suspend fun stacks(afterClusterId: String?, limit: Int): List<SimilarityStack> {
        require(limit in 1..500)
        return dao.similarityStacks(afterClusterId, limit).map {
            SimilarityStack(
                it.clusterId,
                it.memberCount,
                it.bestScore,
                MediaKey(it.recommendedVolumeName, it.recommendedMediaStoreId),
            )
        }
    }

    suspend fun members(stack: SimilarityStack, after: MediaKey?, limit: Int): List<MediaItemEntity> =
        dao.similarityStackMembers(
            stack.id,
            after?.volumeName,
            after?.mediaStoreId ?: Long.MIN_VALUE,
            limit.coerceIn(1, 500),
        )

    suspend fun ungroup(key: MediaKey) {
        val oldCluster = dao.similarityMembership(key.volumeName, key.mediaStoreId)?.clusterId
        dao.excludeSimilarity(SimilarityExclusionEntity(key.volumeName, key.mediaStoreId, nowMillis()))
        dao.deleteSimilarityEdges(key.volumeName, key.mediaStoreId)
        dao.deleteSimilarityMembership(key.volumeName, key.mediaStoreId)
        oldCluster?.let { clusters.rebuild(it) }
    }

    suspend fun restore(key: MediaKey) {
        dao.restoreSimilarity(key.volumeName, key.mediaStoreId)
        dao.deleteSimilarityFeature(key.volumeName, key.mediaStoreId)
    }
}

internal fun similarity(a: SimilarityFeatureEntity, b: SimilarityFeatureEntity): Float {
    val hamming = java.lang.Long.bitCount(a.pHash xor b.pHash)
    val hashScore = 1f - hamming / 64f
    val length = minOf(a.compactEmbedding.size, b.compactEmbedding.size)
    if (length == 0) return hashScore
    var difference = 0L
    for (index in 0 until length) {
        difference += abs((a.compactEmbedding[index].toInt() and 0xff) - (b.compactEmbedding[index].toInt() and 0xff))
    }
    val embeddingScore = 1f - difference.toFloat() / (length * 255f)
    return (0.7f * hashScore) + (0.3f * embeddingScore)
}

private fun RawSimilarityFeature.entity(item: MediaItemEntity, nowMillis: Long) = SimilarityFeatureEntity(
    item.volumeName,
    item.mediaStoreId,
    item.generationModified,
    SimilarityMlEngine.AlgorithmVersion,
    pHash,
    compactEmbedding,
    (pHash and 0xffff).toInt(),
    ((pHash ushr 16) and 0xffff).toInt(),
    ((pHash ushr 32) and 0xffff).toInt(),
    ((pHash ushr 48) and 0xffff).toInt(),
    blurScore,
    nowMillis,
)

private fun edge(
    first: SimilarityFeatureEntity,
    second: SimilarityFeatureEntity,
    score: Float,
): SimilarityEdgeEntity {
    val (a, b) = if (first.key().stableId() <= second.key().stableId()) first to second else second to first
    return SimilarityEdgeEntity(a.volumeName, a.mediaStoreId, b.volumeName, b.mediaStoreId, score)
}

private fun SimilarityEdgeEntity.touches(key: MediaKey) =
    (aVolumeName == key.volumeName && aMediaStoreId == key.mediaStoreId) ||
        (bVolumeName == key.volumeName && bMediaStoreId == key.mediaStoreId)

private fun SimilarityFeatureEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaItemEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaKey.stableId() = "$volumeName:$mediaStoreId"
