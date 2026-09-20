package com.ugallery.core.data

import androidx.paging.*
import androidx.room.withTransaction
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class SmartAlbumChanged : IllegalStateException("SmartAlbumChanged")

data class SmartAlbumExclusionReceipt(val albumId: String, val key: MediaKey, val token: String)

@OptIn(ExperimentalCoroutinesApi::class)
class GallerySmartAlbumRepository(
    private val db: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.smartAlbumDao()

    fun albums() =
        Pager(
                PagingConfig(20, initialLoadSize = 40, enablePlaceholders = false, maxSize = 120),
                pagingSourceFactory = dao::albums,
            )
            .flow

    fun people() =
        Pager(
                PagingConfig(40, initialLoadSize = 60, enablePlaceholders = false, maxSize = 200),
                pagingSourceFactory = dao::people,
            )
            .flow

    fun topics() =
        Pager(
                PagingConfig(40, initialLoadSize = 60, enablePlaceholders = false, maxSize = 200),
                pagingSourceFactory = { dao.topics(SmartAlbumQuery.MinimumLabelConfidence) },
            )
            .flow

    fun person(id: String, version: String) = dao.observePerson(id, version)

    fun source(key: MediaKey) = dao.source(key.volumeName, key.mediaStoreId)

    fun personCover(id: String, version: String) =
        dao.firstMedia(
            SmartAlbumQuery.build(
                SmartAlbumRule(personClusterId = id, personAlgorithmVersion = version),
                first = true,
            )
        )

    fun observe(id: String) = dao.observe(id)

    private fun previewQuery(
        rule: SmartAlbumRule,
        excluded: List<MediaKey>,
        editing: SmartAlbumEntity?,
        count: Boolean = false,
    ) =
        SmartAlbumQuery.build(
            rule,
            editing?.albumId,
            editing?.revision,
            excluded = excluded,
            count = count,
            bounds =
                if (editing != null && editing.year == rule.year && editing.zoneId == rule.zoneId)
                    editing.bounds()
                else rule.bounds(),
        )

    fun preview(
        rule: SmartAlbumRule,
        excluded: List<MediaKey> = emptyList(),
        editing: SmartAlbumEntity? = null,
    ) = pager { dao.media(previewQuery(rule, excluded, editing)) }

    fun previewCount(
        rule: SmartAlbumRule,
        excluded: List<MediaKey> = emptyList(),
        editing: SmartAlbumEntity? = null,
    ) = dao.count(previewQuery(rule, excluded, editing, count = true))

    fun media(id: String) =
        dao.observe(id).flatMapLatest { album ->
            if (album == null) flowOf(PagingData.empty())
            else
                pager {
                    dao.media(
                        SmartAlbumQuery.build(
                            album.rule(),
                            album.albumId,
                            album.revision,
                            bounds = album.bounds(),
                        )
                    )
                }
        }

    fun count(id: String): Flow<Long> =
        dao.observe(id).flatMapLatest { album ->
            if (album == null) flowOf(0L)
            else
                dao.count(
                    SmartAlbumQuery.build(
                        album.rule(),
                        album.albumId,
                        album.revision,
                        count = true,
                        bounds = album.bounds(),
                    )
                )
        }

    fun exclusions(id: String) =
        Pager(
                PagingConfig(40, initialLoadSize = 60, enablePlaceholders = false, maxSize = 200),
                pagingSourceFactory = { dao.exclusions(id) },
            )
            .flow

    /** Saves a live query, not a frozen preview snapshot or copies of matching originals. */
    suspend fun create(
        name: String,
        rule: SmartAlbumRule,
        excluded: List<MediaKey> = emptyList(),
    ): String =
        db.withTransaction {
            require(excluded.size <= 200 && excluded.distinct().size == excluded.size)
            val normalized = rule.normalized()
            validatePerson(normalized)
            val (from, until) = normalized.bounds()
            val time = now()
            val id = UUID.randomUUID().toString()
            dao.insert(
                SmartAlbumEntity(
                    id,
                    SmartAlbumRule.normalizeName(name),
                    normalized.topic,
                    normalized.personClusterId,
                    normalized.personAlgorithmVersion,
                    normalized.year,
                    normalized.zoneId,
                    from,
                    until,
                    normalized.favoritesOnly,
                    UUID.randomUUID().toString(),
                    time,
                    time,
                )
            )
            for (key in excluded) {
                if (
                    dao.countNow(SmartAlbumQuery.build(normalized, count = true, source = key)) !=
                        1L
                )
                    throw SmartAlbumChanged()
                dao.exclude(
                    SmartAlbumExclusionEntity(
                        id,
                        key.volumeName,
                        key.mediaStoreId,
                        UUID.randomUUID().toString(),
                        time,
                    )
                )
            }
            id
        }

    suspend fun update(
        id: String,
        revision: String,
        name: String,
        rule: SmartAlbumRule,
        excluded: List<MediaKey> = emptyList(),
    ) =
        db.withTransaction {
            require(excluded.size <= 200 && excluded.distinct().size == excluded.size)
            val current = current(id, revision)
            val normalized = rule.normalized()
            // A disappeared identity stays an explicit unmatched rule until the user replaces it.
            if (
                current.personClusterId != normalized.personClusterId ||
                    current.personAlgorithmVersion != normalized.personAlgorithmVersion
            )
                validatePerson(normalized)
            val (from, until) =
                if (current.year == normalized.year && current.zoneId == normalized.zoneId)
                    current.bounds()
                else normalized.bounds()
            dao.update(
                current.copy(
                    name = SmartAlbumRule.normalizeName(name),
                    topic = normalized.topic,
                    personClusterId = normalized.personClusterId,
                    personAlgorithmVersion = normalized.personAlgorithmVersion,
                    year = normalized.year,
                    zoneId = normalized.zoneId,
                    fromMillis = from,
                    untilMillis = until,
                    favoritesOnly = normalized.favoritesOnly,
                    revision = UUID.randomUUID().toString(),
                    updatedAtMillis = now(),
                )
            )
            for (key in excluded) {
                if (
                    dao.countNow(
                        SmartAlbumQuery.build(
                            normalized,
                            count = true,
                            source = key,
                            bounds = from to until,
                        )
                    ) != 1L
                )
                    throw SmartAlbumChanged()
                dao.exclude(
                    SmartAlbumExclusionEntity(
                        id,
                        key.volumeName,
                        key.mediaStoreId,
                        UUID.randomUUID().toString(),
                        now(),
                    )
                )
            }
        }

    suspend fun exclude(id: String, revision: String, key: MediaKey): SmartAlbumExclusionReceipt =
        db.withTransaction {
            val album = current(id, revision)
            if (
                dao.countNow(
                    SmartAlbumQuery.build(
                        album.rule(),
                        count = true,
                        source = key,
                        bounds = album.bounds(),
                    )
                ) != 1L
            )
                throw SmartAlbumChanged()
            val token = UUID.randomUUID().toString()
            dao.exclude(
                SmartAlbumExclusionEntity(id, key.volumeName, key.mediaStoreId, token, now())
            )
            SmartAlbumExclusionReceipt(id, key, token)
        }

    /** Conditional undo cannot erase a newer exclusion of the same source. */
    suspend fun undoExclude(receipt: SmartAlbumExclusionReceipt): Boolean =
        db.withTransaction {
            dao.undoExclude(
                receipt.albumId,
                receipt.key.volumeName,
                receipt.key.mediaStoreId,
                receipt.token,
            ) == 1
        }

    suspend fun includeAgain(id: String, revision: String, key: MediaKey): Boolean =
        db.withTransaction {
            current(id, revision)
            dao.includeAgain(id, key.volumeName, key.mediaStoreId) == 1
        }

    suspend fun delete(id: String, revision: String): Unit =
        db.withTransaction {
            current(id, revision)
            dao.delete(id)
        }

    private suspend fun validatePerson(rule: SmartAlbumRule) {
        rule.personClusterId?.let {
            if (dao.person(it, requireNotNull(rule.personAlgorithmVersion)) == null)
                throw SmartAlbumChanged()
        }
    }

    private suspend fun current(id: String, revision: String): SmartAlbumEntity {
        val album = dao.get(id)
        if (album == null || album.revision != revision) throw SmartAlbumChanged()
        return album
    }

    private fun pager(source: () -> PagingSource<Int, MediaItemEntity>) =
        Pager(
                PagingConfig(60, initialLoadSize = 120, enablePlaceholders = false, maxSize = 360),
                pagingSourceFactory = source,
            )
            .flow
}

fun SmartAlbumEntity.rule() =
    SmartAlbumRule(topic, personClusterId, personAlgorithmVersion, year, zoneId, favoritesOnly)

/** Use the saved interval even if the device timezone or its timezone database changes. */
internal fun SmartAlbumEntity.bounds(): Pair<Long?, Long?> =
    (fromMillis to untilMillis).also {
        check((year == null) == (fromMillis == null))
        check((fromMillis == null) == (untilMillis == null))
        check(fromMillis == null || requireNotNull(fromMillis) < requireNotNull(untilMillis))
    }
