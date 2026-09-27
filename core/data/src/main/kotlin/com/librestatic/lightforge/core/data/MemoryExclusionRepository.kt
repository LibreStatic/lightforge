package com.librestatic.lightforge.core.data

import androidx.room.withTransaction
import com.librestatic.lightforge.core.database.*
import java.util.UUID

/** Duplicate submissions are no-ops; only a newly created UUID is offered as an undo receipt. */
data class MemoryRuleCreation<T>(val rule: T, val created: Boolean)

class MemoryRuleLimitReached : IllegalStateException("MemoryRuleLimitReached")

class MemoryPersonUnavailable : IllegalStateException("MemoryPersonUnavailable")

class MemoryExclusionRepository(
    private val db: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.memoryExclusionDao()

    fun dates() = dao.dates()

    fun people() = dao.people()

    fun knownSourceCount(id: String) = dao.knownSourceCount(id)

    suspend fun addDate(range: MemoryDateRange): MemoryRuleCreation<MemoryDateExclusionEntity> =
        db.withTransaction {
            val start = range.start.toEpochDay()
            val end = range.end.toEpochDay()
            dao.date(start, end, range.zoneId)?.let {
                return@withTransaction MemoryRuleCreation(it, false)
            }
            checkLimit()
            val bounds = range.bounds()
            val rule =
                MemoryDateExclusionEntity(
                    UUID.randomUUID().toString(),
                    start,
                    end,
                    range.zoneId,
                    bounds.first,
                    bounds.second,
                    now(),
                )
            dao.insertDate(rule)
            MemoryRuleCreation(rule, true)
        }

    suspend fun addPerson(
        cluster: String,
        version: String,
    ): MemoryRuleCreation<MemoryPersonExclusionEntity> =
        db.withTransaction {
            require(cluster.isNotBlank() && version.isNotBlank())
            dao.person(cluster, version)?.let {
                return@withTransaction MemoryRuleCreation(it, false)
            }
            val person = dao.currentPerson(cluster, version) ?: throw MemoryPersonUnavailable()
            checkLimit()
            val rule =
                MemoryPersonExclusionEntity(
                    UUID.randomUUID().toString(),
                    cluster,
                    version,
                    person.displayName,
                    now(),
                )
            dao.insertPerson(rule)
            dao.captureCurrentPersonMatches()
            MemoryRuleCreation(rule, true)
        }

    // Removal by exact immutable UUID cannot remove a later rule for the same person/date.
    suspend fun removeDate(ruleId: String) = dao.removeDate(ruleId) == 1

    suspend fun removePerson(ruleId: String) = dao.removePerson(ruleId) == 1

    suspend fun captureCurrentMatches() = dao.captureCurrentPersonMatches()

    private suspend fun checkLimit() {
        if (dao.ruleCount() >= MaximumRules) throw MemoryRuleLimitReached()
    }

    companion object {
        const val MaximumRules = 200
    }
}
