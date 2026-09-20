package com.ugallery.core.data

import androidx.room.withTransaction
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MomentCoverEntity
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentMemberEntity
import com.ugallery.core.model.MediaKey
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ManualMomentSource(
    val key: MediaKey,
    val generationAdded: Long,
    val generationModified: Long,
    val requiresSpecialMedia: Boolean = false,
)

data class ManualMomentDraft(val id: String, val sources: List<ManualMomentSource>)

/** Read-only outcome for the original request, independent of later memory/source edits. */
enum class ManualMomentCommitStatus { Missing, Committed, Conflict }

/** Explicit user creation. Caller verifies live provider access; this transaction verifies Room. */
class ManualMomentRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.momentDao()

    suspend fun prepare(keys: List<MediaKey>): ManualMomentDraft {
        val selected = keys.toList()
        requireKeys(selected)
        return database.withTransaction {
            database.memoryExclusionDao().captureCurrentPersonMatches()
            val sources = selected.map { key ->
                val media = requireNotNull(dao.manualSource(key.volumeName, key.mediaStoreId)) {
                    "Manual memory source is unavailable or excluded"
                }
                ManualMomentSource(key, media.generationAdded, media.generationModified,
                    media.isScreenshotLike() || dao.isConfirmedDocument(key.volumeName, key.mediaStoreId))
            }
            ManualMomentDraft(UUID.randomUUID().toString(), sources)
        }
    }

    suspend fun create(
        draft: ManualMomentDraft,
        orderedKeys: List<MediaKey>,
        title: String,
        includeSpecialMedia: Boolean,
    ): String {
        val request = validatedRequest(draft, orderedKeys, title, includeSpecialMedia)
        val order = request.order
        val byKey = request.sources.associateBy { it.key }
        val normalizedTitle = request.normalizedTitle
        val fingerprint = request.fingerprint
        return database.withTransaction {
            database.memoryExclusionDao().captureCurrentPersonMatches()
            dao.moment(draft.id)?.let { existing ->
                check(existing.origin == "MANUAL" && existing.algorithmVersion == fingerprint) {
                    "Manual memory draft ID was already used by another request"
                }
                // Treat a repeated commit as a receipt, preserving subsequent user edits.
                return@withTransaction existing.momentId
            }
            val current = order.map { key ->
                val expected = byKey.getValue(key)
                val media = requireNotNull(dao.manualSource(key.volumeName, key.mediaStoreId)) {
                    "Manual memory source is unavailable or excluded"
                }
                check(media.generationAdded == expected.generationAdded &&
                    media.generationModified == expected.generationModified) {
                    "Manual memory source changed after preparation"
                }
                check(includeSpecialMedia || (!media.isScreenshotLike() &&
                    !dao.isConfirmedDocument(key.volumeName, key.mediaStoreId))) {
                    "Screenshots and documents require explicit inclusion"
                }
                media
            }
            val now = nowMillis()
            dao.upsertMoment(MomentEntity(
                momentId = draft.id, origin = "MANUAL", state = "SAVED",
                algorithmVersion = fingerprint,
                startMillis = current.minOf { it.timelineSortMillis },
                endMillis = current.maxOf { it.timelineSortMillis },
                title = normalizedTitle.takeIf { it.isNotEmpty() },
                titleMode = if (normalizedTitle.isEmpty()) "AUTO" else "USER",
                isUserEdited = true, createdAtMillis = now, updatedAtMillis = now,
                includeSpecialMedia = includeSpecialMedia,
            ))
            dao.insertMembers(current.mapIndexed { ordinal, media ->
                MomentMemberEntity(draft.id, ordinal, media.volumeName, media.mediaStoreId,
                    media.generationModified, "MANUAL", 1f)
            })
            val cover = order.first()
            dao.upsertCover(MomentCoverEntity(draft.id, cover.volumeName, cover.mediaStoreId, true))
            draft.id
        }
    }

    /**
     * Receipt lookup only. In particular, Missing never retries create(), and matching receipts
     * remain authoritative if sources disappeared or the user subsequently edited the memory.
     * Invalid request structure throws exactly as create(); a different valid request conflicts.
     */
    suspend fun committedStatus(
        draft: ManualMomentDraft,
        orderedKeys: List<MediaKey>,
        title: String,
        includeSpecialMedia: Boolean,
    ): ManualMomentCommitStatus {
        val request = validatedRequest(draft, orderedKeys, title, includeSpecialMedia)
        val existing = dao.moment(draft.id) ?: return ManualMomentCommitStatus.Missing
        return if (existing.origin == "MANUAL" && existing.algorithmVersion == request.fingerprint)
            ManualMomentCommitStatus.Committed else ManualMomentCommitStatus.Conflict
    }

    private data class Request(
        val sources: List<ManualMomentSource>,
        val order: List<MediaKey>,
        val normalizedTitle: String,
        val fingerprint: String,
    )

    private fun validatedRequest(
        draft: ManualMomentDraft,
        orderedKeys: List<MediaKey>,
        title: String,
        includeSpecialMedia: Boolean,
    ): Request {
        val sources = draft.sources.toList()
        val order = orderedKeys.toList()
        require(UUID.fromString(draft.id).toString() == draft.id) { "Invalid manual memory draft ID" }
        requireKeys(sources.map { it.key })
        requireKeys(order)
        val byKey = sources.associateBy { it.key }
        require(order.all { it in byKey }) { "Manual memory order contains an unprepared source" }
        val normalizedTitle = title.trim()
        require(normalizedTitle.length <= 120) { "Manual memory title is too long" }
        val fingerprint = fingerprint(sources, order, normalizedTitle, includeSpecialMedia)
        return Request(sources, order, normalizedTitle, fingerprint)
    }

    private fun requireKeys(keys: List<MediaKey>) {
        require(keys.size in 1..120 && keys.distinct().size == keys.size) {
            "Manual memories require 1 to 120 unique photos"
        }
        require(keys.all { it.volumeName.isNotBlank() && it.mediaStoreId >= 0 })
    }

    private fun fingerprint(
        sources: List<ManualMomentSource>,
        order: List<MediaKey>,
        title: String,
        includeSpecialMedia: Boolean,
    ): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                val utf8 = value.toByteArray(Charsets.UTF_8)
                out.writeInt(utf8.size); out.write(utf8)
            }
            fun key(value: MediaKey) { text(value.volumeName); out.writeLong(value.mediaStoreId) }
            text("manual-v1")
            out.writeInt(sources.size)
            sources.forEach {
                key(it.key); out.writeLong(it.generationAdded); out.writeLong(it.generationModified)
            }
            out.writeInt(order.size); order.forEach(::key)
            text(title); out.writeBoolean(includeSpecialMedia)
        }
        return "manual-v1:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
