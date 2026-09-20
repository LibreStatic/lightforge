package com.ugallery.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Native heuristic IDs are24 lowercase hex; portable imports use independent UUID IDs. */
const val NativeGeneratedMomentIdSql = "length(momentId)=24 AND momentId NOT GLOB '*[^0-9a-f]*'"

private fun nativeGeneratedId(id: String) = id.length == 24 && id.all { it in '0'..'9' || it in 'a'..'f' }

class MomentDiscoveryChanged : IllegalStateException("Moment discovery inputs changed")

@Dao
abstract class MomentDiscoveryDao {
    @Query("SELECT COALESCE((SELECT revision FROM moment_discovery_revision WHERE id=1),0)")
    abstract suspend fun discoveryRevision(): Long

    @Query("SELECT COALESCE((SELECT revision FROM moment_discovery_revision WHERE id=1),0)")
    abstract fun observeRevision(): Flow<Long>

    @Query("SELECT * FROM media_items WHERE volumeName=:volume AND mediaStoreId=:id")
    abstract fun observeSource(volume: String, id: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM moment_runs WHERE algorithmVersion=:version")
    protected abstract suspend fun run(version: String): MomentRunEntity?

    @Query(
        "SELECT DISTINCT mo.* FROM moments mo JOIN moment_members mm ON mm.momentId=mo.momentId WHERE mm.volumeName=:volume AND mm.mediaStoreId IN (:ids) LIMIT 1001"
    )
    protected abstract suspend fun touching(volume: String, ids: List<Long>): List<MomentEntity>

    @Query(
        "SELECT * FROM moments WHERE origin='AUTO' AND algorithmVersion=:version AND startMillis<=:until AND endMillis>=:from LIMIT 1001"
    )
    protected abstract suspend fun overlapping(
        version: String,
        from: Long,
        until: Long,
    ): List<MomentEntity>

    @Query("SELECT * FROM moments WHERE momentId=:id")
    protected abstract suspend fun moment(id: String): MomentEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM moment_discovery_seen WHERE algorithmVersion=:version AND runId=:runId AND momentId=:id)"
    )
    protected abstract suspend fun seen(version: String, runId: String, id: String): Boolean

    @Query("SELECT * FROM moment_members WHERE momentId=:id ORDER BY ordinal")
    protected abstract suspend fun storedMembers(id: String): List<MomentMemberEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun markSeen(row: MomentDiscoverySeenEntity)

    @Upsert protected abstract suspend fun putMoment(row: MomentEntity)

    @Upsert protected abstract suspend fun putCover(row: MomentCoverEntity)

    @Upsert protected abstract suspend fun putRun(row: MomentRunEntity)

    @Insert protected abstract suspend fun putMembers(rows: List<MomentMemberEntity>)

    @Query("DELETE FROM moment_members WHERE momentId=:id")
    protected abstract suspend fun removeMembers(id: String)

    @Query("DELETE FROM moment_covers WHERE momentId=:id")
    protected abstract suspend fun removeCover(id: String)

    @Query(
        "DELETE FROM moments WHERE " + NativeGeneratedMomentIdSql + " AND origin='AUTO' AND algorithmVersion=:version AND state='SUGGESTED' AND isUserEdited=0 AND NOT EXISTS(SELECT 1 FROM moment_discovery_seen s WHERE s.algorithmVersion=:version AND s.runId=:runId AND s.momentId=moments.momentId)"
    )
    protected abstract suspend fun removeUnseen(version: String, runId: String): Int

    suspend fun assertCurrent(expected: MomentRunEntity) {
        val current = run(expected.algorithmVersion)
        if (
            discoveryRevision() != expected.inputRevision ||
                current == null ||
                current.runId != expected.runId ||
                current.status != "RUNNING"
        )
            throw MomentDiscoveryChanged()
    }

    /**
     * Reuse an existing event identity even if an older import changes its first source. User-owned
     * stories (including dismissed ones) freeze their grouping: regeneration never edits or clones
     * them. A completed pass removes only obsolete, untouched suggestions. Interrupted passes do
     * not run that cleanup. Raw references, not currently visible/excluded members, match events.
     */
    @Transaction
    open suspend fun reconcile(
        expected: MomentRunEntity,
        proposed: MomentEntity,
        members: List<MomentMemberEntity>,
        cover: MomentCoverEntity,
        candidates: List<MomentRunCandidateEntity>,
    ): Boolean {
        assertCurrent(expected)
        val matches = linkedMapOf<String, MomentEntity>()
        candidates
            .groupBy { it.volumeName }
            .forEach { (volume, rows) ->
                val found = touching(volume, rows.map { it.mediaStoreId })
                require(found.size <= 1000) { "Too many overlapping memories" }
                found.forEach { matches[it.momentId] = it }
            }
        val temporal =
            overlapping(proposed.algorithmVersion, proposed.startMillis, proposed.endMillis)
        require(temporal.size <= 1000) { "Too many overlapping memories" }
        temporal.forEach { matches[it.momentId] = it }
        moment(proposed.momentId)?.let { matches[it.momentId] = it }
        // Imported/manual stories are independent namespaces. They still protect their raw sources
        // from being cloned into an automatically generated copy.
        if (
            matches.values.any { it.origin != "AUTO" || !nativeGeneratedId(it.momentId) || it.state != "SUGGESTED" || it.isUserEdited }
        ) {
            return false
        }
        // A process may have committed this event before its containing page checkpoint. Replay
        // must acknowledge the same event rather than claim another old overlapping suggestion.
        for (existing in matches.values) {
            if (
                existing.algorithmVersion == proposed.algorithmVersion &&
                    existing.startMillis == proposed.startMillis &&
                    existing.endMillis == proposed.endMillis &&
                    seen(expected.algorithmVersion, expected.runId, existing.momentId) &&
                    storedMembers(existing.momentId) ==
                        members.map { it.copy(momentId = existing.momentId) }
            ) {
                return false
            }
        }
        val reusable =
            matches.values
                .filter { it.algorithmVersion == proposed.algorithmVersion }
                .sortedWith(compareBy<MomentEntity> { it.createdAtMillis }.thenBy { it.momentId })
                .firstOrNull { !seen(expected.algorithmVersion, expected.runId, it.momentId) }
        val id = reusable?.momentId ?: proposed.momentId
        if (seen(expected.algorithmVersion, expected.runId, id)) return false
        val row =
            proposed.copy(
                momentId = id,
                createdAtMillis = reusable?.createdAtMillis ?: proposed.createdAtMillis,
            )
        putMoment(row)
        removeMembers(id)
        removeCover(id)
        putMembers(members.map { it.copy(momentId = id) })
        putCover(cover.copy(momentId = id))
        markSeen(MomentDiscoverySeenEntity(expected.algorithmVersion, expected.runId, id))
        return true
    }

    @Transaction
    open suspend fun complete(expected: MomentRunEntity) {
        assertCurrent(expected)
        removeUnseen(expected.algorithmVersion, expected.runId)
        putRun(expected.copy(status = "COMPLETE"))
    }
}
