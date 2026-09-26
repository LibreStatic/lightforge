package com.ugallery.core.ml

import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MeMatchMediaRow
import com.ugallery.core.database.PersonClusterSummaryRow
import com.ugallery.core.database.PersonMembershipMediaRow
import kotlinx.coroutines.flow.Flow

class PeopleRepository(private val database: GalleryDatabase) {
    private val dao = database.personDao()

    fun people(limit: Int = DefaultPeopleLimit): Flow<List<PersonClusterSummaryRow>> =
        dao.visiblePersonSummaries(PersonClusteringMlEngine.AlgorithmVersion, limit)

    /** Hidden people stay listed so they can be unhidden. */
    fun hiddenPeople(limit: Int = DefaultPeopleLimit): Flow<List<PersonClusterSummaryRow>> =
        dao.hiddenPersonSummaries(PersonClusteringMlEngine.AlgorithmVersion, limit)

    suspend fun members(clusterId: String, limit: Int = DefaultMemberLimit): List<PersonMembershipMediaRow> =
        dao.visibleClusterMembers(clusterId, limit)

    suspend fun setAsMeFromCluster(clusterId: String): Boolean {
        val references = dao.visibleClusterMembers(clusterId, MeProfileRepository.MaximumReferences)
            .map { FaceIdentityKey(it.membership.volumeName, it.membership.mediaStoreId, it.membership.faceOrdinal) }
        if (references.isEmpty()) return false
        val me = MeProfileRepository(database)
        me.selectReferences(references)
        while (!me.rebuildMatchesChunk(MeRebuildChunkSize)) Unit
        return true
    }

    suspend fun rename(clusterId: String, name: String?) = PersonCorrectionRepository(database).rename(clusterId, name)

    suspend fun hide(clusterId: String) = PersonCorrectionRepository(database).hide(clusterId, true)

    suspend fun unhide(clusterId: String) = PersonCorrectionRepository(database).hide(clusterId, false)

    suspend fun merge(targetClusterId: String, sourceClusterId: String) =
        PersonCorrectionRepository(database).merge(targetClusterId, listOf(sourceClusterId))

    /** Moves [faces] out of [clusterId] into a new person and returns its id. */
    suspend fun split(clusterId: String, faces: List<FaceIdentityKey>): String =
        PersonCorrectionRepository(database).split(clusterId, faces)

    suspend fun meState(): MeProfileRepository.MeProfileState? = MeProfileRepository(database).state()

    suspend fun meMatches(limit: Int = DefaultMemberLimit): List<MeMatchMediaRow> =
        dao.visibleMeMatches(MeProfileRepository.ProfileId, limit)

    suspend fun resetMe() = MeProfileRepository(database).reset()

    private companion object {
        const val DefaultPeopleLimit = 200
        const val DefaultMemberLimit = 200
        const val MeRebuildChunkSize = 250
    }
}
