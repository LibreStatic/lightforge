package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MomentCandidateRow
import com.librestatic.lightforge.core.database.MomentRunCandidateEntity
import java.security.MessageDigest
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

data class MomentEvent(
    val startMillis: Long,
    val endMillis: Long,
    val itemCount: Int,
    val candidates: List<MomentRunCandidateEntity>,
)

class MomentAccumulator(
    private val algorithmVersion: String,
    startMillis: Long? = null,
    endMillis: Long? = null,
    lastMillis: Long? = null,
    itemCount: Int = 0,
    staged: List<MomentRunCandidateEntity> = emptyList(),
    lastLatitude: Double? = null,
    lastLongitude: Double? = null,
) {
    var startMillis: Long? = startMillis
        private set

    var endMillis: Long? = endMillis
        private set

    var lastMillis: Long? = lastMillis
        private set

    var itemCount: Int = itemCount
        private set

    var lastLatitude: Double? = lastLatitude
        private set

    var lastLongitude: Double? = lastLongitude
        private set

    private val candidates = staged.toMutableList()

    fun offer(row: MomentCandidateRow): MomentEvent? {
        val media = row.media
        val timestamp = media.timelineSortMillis
        val closed =
            if (startMillis != null && shouldSplit(timestamp, row.latitude, row.longitude)) close()
            else null
        if (startMillis == null) startMillis = timestamp
        endMillis = timestamp
        lastMillis = timestamp
        lastLatitude = row.latitude
        lastLongitude = row.longitude
        itemCount++
        if (!media.isScreenshotLike()) addCandidate(row)
        return closed
    }

    fun finish(): MomentEvent? = if (startMillis == null) null else close()

    fun staged(): List<MomentRunCandidateEntity> = candidates.sortedByDescending { it.score }

    private fun shouldSplit(timestamp: Long, latitude: Double?, longitude: Double?): Boolean {
        val start = checkNotNull(startMillis)
        val last = checkNotNull(lastMillis)
        val gap = (timestamp - last).coerceAtLeast(0)
        if (timestamp - start > MaximumEventDurationMillis || itemCount >= MaximumEventItems)
            return true
        val closeLocation =
            lastLatitude != null &&
                lastLongitude != null &&
                latitude != null &&
                longitude != null &&
                distanceKm(lastLatitude!!, lastLongitude!!, latitude, longitude) <= NearbyKm
        return if (closeLocation) gap > NearbyMaximumGapMillis else gap > DefaultMaximumGapMillis
    }

    private fun addCandidate(row: MomentCandidateRow) {
        val media = row.media
        val timeBucket = media.timelineSortMillis / DiversityWindowMillis
        val visualBucket =
            row.similarityClusterId
                ?: row.pHash?.ushr(48)?.toString(16)
                ?: "media-${media.volumeName}-${media.mediaStoreId % 31}"
        val candidate =
            MomentRunCandidateEntity(
                algorithmVersion = algorithmVersion,
                rank = 0,
                volumeName = media.volumeName,
                mediaStoreId = media.mediaStoreId,
                generationModified = media.generationModified,
                timelineSortMillis = media.timelineSortMillis,
                score = qualityScore(media, row.blurScore),
                timeBucket = timeBucket,
                visualBucket = visualBucket,
            )
        val sameBucket =
            candidates.indexOfFirst {
                it.timeBucket == candidate.timeBucket && it.visualBucket == candidate.visualBucket
            }
        if (sameBucket >= 0) {
            if (candidate.score > candidates[sameBucket].score) candidates[sameBucket] = candidate
        } else candidates += candidate
        if (candidates.size > MaximumStagingCandidates) {
            candidates.sortByDescending { it.score }
            candidates.subList(MaximumStagingCandidates, candidates.size).clear()
        }
        candidates.sortByDescending { it.score }
        candidates.forEachIndexed { index, value -> candidates[index] = value.copy(rank = index) }
    }

    private fun close(): MomentEvent {
        val result =
            MomentEvent(
                checkNotNull(startMillis),
                checkNotNull(endMillis),
                itemCount,
                candidates.sortedByDescending { it.score },
            )
        startMillis = null
        endMillis = null
        lastMillis = null
        itemCount = 0
        lastLatitude = null
        lastLongitude = null
        candidates.clear()
        return result
    }

    companion object {
        const val DefaultMaximumGapMillis = 6L * 60 * 60 * 1_000
        const val NearbyMaximumGapMillis = 24L * 60 * 60 * 1_000
        const val MaximumEventDurationMillis = 72L * 60 * 60 * 1_000
        const val MaximumEventItems = 5_000
        const val MaximumStagingCandidates = 96
        const val MaximumMomentMembers = 30
        const val MinimumMomentItems = 6
        private const val DiversityWindowMillis = 30L * 60 * 1_000
        private const val NearbyKm = 10.0

        fun selectMembers(event: MomentEvent): List<MomentRunCandidateEntity> {
            if (event.itemCount < MinimumMomentItems || event.candidates.size < 3)
                return emptyList()
            val diverse = linkedMapOf<Pair<Long, String>, MomentRunCandidateEntity>()
            event.candidates
                .sortedByDescending { it.score }
                .forEach { candidate ->
                    diverse.putIfAbsent(candidate.timeBucket to candidate.visualBucket, candidate)
                }
            return diverse.values.take(MaximumMomentMembers).sortedBy { it.timelineSortMillis }
        }

        fun stableMomentId(event: MomentEvent, members: List<MomentRunCandidateEntity>): String {
            val first = members.first()
            val raw = "${event.startMillis}:${first.volumeName}:${first.mediaStoreId}"
            return MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun qualityScore(media: MediaItemEntity, blurScore: Float?): Float {
            val megapixels = media.width.toLong() * media.height / 1_000_000f
            val resolution = (ln(1f + megapixels) / ln(1f + 24f)).coerceIn(0f, 1f)
            val sharpness = blurScore?.coerceIn(0f, 1f) ?: .5f
            return (resolution * .45f + sharpness * .35f + if (media.isFavorite) .20f else 0f)
                .coerceIn(0f, 1f)
        }

        private fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val radius = 6_371.0
            val dLat = Math.toRadians(bLat - aLat)
            val dLon = Math.toRadians(bLon - aLon)
            val x =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(aLat)) *
                        cos(Math.toRadians(bLat)) *
                        sin(dLon / 2) *
                        sin(dLon / 2)
            return radius * 2 * atan2(sqrt(x), sqrt(1 - x))
        }
    }
}

// Match a capture/scan token, not letters inside place names such as Scandinavia or Tuscany.
// Digits may immediately follow the token for scanner-generated names such as Scan123.jpg.
private val ScreenshotOrScanToken = Regex(
    """(?<![\p{L}\p{N}])(?:screen[\s_-]*(?:shots?|captures?)|scans?)(?!\p{L})""",
    RegexOption.IGNORE_CASE,
)

internal fun MediaItemEntity.isScreenshotLike(): Boolean =
    sequenceOf(bucketDisplayName, relativePath, displayName).filterNotNull().any {
        ScreenshotOrScanToken.containsMatchIn(it)
    }
