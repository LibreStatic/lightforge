package com.ugallery.core.ml

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.database.DuplicateHashEntity
import com.ugallery.core.database.ExactDuplicateGroupRow
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

interface DuplicateContentHasher {
    suspend fun sample(item: MediaItemEntity): String
    suspend fun full(item: MediaItemEntity): String
}

class ResolverDuplicateContentHasher(
    private val resolver: ContentResolver,
) : DuplicateContentHasher {
    override suspend fun sample(item: MediaItemEntity): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("ugallery-sample-v1".encodeToByteArray())
        digest.update(longBytes(item.sizeBytes))
        val offsets = listOf(
            0L,
            ((item.sizeBytes - SampleWindowBytes).coerceAtLeast(0L) / 2L),
            (item.sizeBytes - SampleWindowBytes).coerceAtLeast(0L),
        ).distinct()
        resolver.openFileDescriptor(item.contentUri(), "r")!!.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val buffer = ByteBuffer.allocate(SampleWindowBytes)
                offsets.forEach { offset ->
                    currentCoroutineContext().ensureActive()
                    digest.update(longBytes(offset))
                    buffer.clear()
                    buffer.limit(minOf(SampleWindowBytes.toLong(), item.sizeBytes - offset).toInt())
                    var position = offset
                    while (buffer.hasRemaining()) {
                        currentCoroutineContext().ensureActive()
                        val read = channel.read(buffer, position)
                        if (read <= 0) break
                        position += read
                    }
                    digest.update(buffer.array(), 0, buffer.position())
                }
            }
        }
        digest.digest().hex()
    }

    override suspend fun full(item: MediaItemEntity): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(item.contentUri())!!.use { input ->
            val buffer = ByteArray(FullBufferBytes)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().hex()
    }

    private fun longBytes(value: Long) = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

    companion object {
        const val SampleWindowBytes = 64 * 1_024
        const val FullBufferBytes = 256 * 1_024
    }
}

class ExactDuplicateMlEngine(
    database: GalleryDatabase,
    private val hasher: DuplicateContentHasher,
    private val permission: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MlTaskEngine {
    private val dao = database.libraryDao()
    override val task = MlTaskType.ExactDuplicates
    override val modelVersion = HashVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome =
        withContext(Dispatchers.IO) {
            require(limit > 0)
            val fullCandidates = dao.pendingDuplicateFullCandidates(modelVersion, limit)
            if (fullCandidates.isNotEmpty()) {
                for (candidate in fullCandidates) {
                    if (!permission()) return@withContext MlChunkOutcome.PermissionLost
                    val digest = try {
                        hasher.full(candidate)
                    } catch (_: SecurityException) {
                        return@withContext MlChunkOutcome.PermissionLost
                    }
                    check(
                        dao.setDuplicateFullHash(
                            candidate.volumeName,
                            candidate.mediaStoreId,
                            candidate.generationModified,
                            modelVersion,
                            digest,
                            nowMillis(),
                        ) == 1,
                    ) { "Duplicate hash candidate changed while hashing" }
                }
                return@withContext MlChunkOutcome.More(fullCandidates.last().key(), fullCandidates.size)
            }

            val sampleCandidates = dao.pendingDuplicateSampleCandidates(modelVersion, limit)
            if (sampleCandidates.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
            for (candidate in sampleCandidates) {
                if (!permission()) return@withContext MlChunkOutcome.PermissionLost
                val digest = try {
                    hasher.sample(candidate)
                } catch (_: SecurityException) {
                    return@withContext MlChunkOutcome.PermissionLost
                }
                dao.upsertDuplicateHash(
                    DuplicateHashEntity(
                        candidate.volumeName,
                        candidate.mediaStoreId,
                        candidate.generationModified,
                        candidate.sizeBytes,
                        modelVersion,
                        digest,
                        sha256 = null,
                        updatedAtMillis = nowMillis(),
                    ),
                )
            }
            MlChunkOutcome.More(sampleCandidates.last().key(), sampleCandidates.size)
        }

    override suspend fun purgeDerivedData() {
        dao.purgeDuplicateHashes()
    }

    companion object { const val HashVersion = "sha256-sampled-v1" }
}

data class ExactDuplicateGroup(
    val id: String,
    val sha256: String,
    val sizeBytes: Long,
    val memberCount: Long,
    val recoverableBytes: Long,
    val recommendedKeep: MediaKey,
)

data class DuplicateGroupCursor(val recoverableBytes: Long, val groupId: String)

class ExactDuplicateRepository(database: GalleryDatabase) {
    private val dao = database.libraryDao()

    suspend fun groups(afterExclusive: DuplicateGroupCursor?, limit: Int): List<ExactDuplicateGroup> {
        require(limit in 1..500)
        return dao.exactDuplicateGroups(
            ExactDuplicateMlEngine.HashVersion,
            afterExclusive?.recoverableBytes,
            afterExclusive?.groupId,
            limit,
        ).map { it.domain() }
    }

    suspend fun members(
        group: ExactDuplicateGroup,
        afterExclusive: MediaKey?,
        limit: Int,
    ): List<MediaItemEntity> {
        require(limit in 1..500)
        return dao.exactDuplicateMembers(
            ExactDuplicateMlEngine.HashVersion,
            group.sha256,
            group.sizeBytes,
            afterExclusive?.volumeName,
            afterExclusive?.mediaStoreId ?: Long.MIN_VALUE,
            limit,
        )
    }

    private fun ExactDuplicateGroupRow.domain() = ExactDuplicateGroup(
        groupId,
        sha256,
        sizeBytes,
        memberCount,
        recoverableBytes,
        MediaKey(recommendedVolumeName, recommendedMediaStoreId),
    )
}

private fun MediaItemEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaItemEntity.contentUri(): Uri = ContentUris.withAppendedId(
    if (mediaType == 3) MediaStore.Video.Media.getContentUri(volumeName)
    else MediaStore.Images.Media.getContentUri(volumeName),
    mediaStoreId,
)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
