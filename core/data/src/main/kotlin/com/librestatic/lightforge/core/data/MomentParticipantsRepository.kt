package com.librestatic.lightforge.core.data

import androidx.room.withTransaction
import com.librestatic.lightforge.core.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Explicit Manual(emptySet()) differs from Automatic. Global names/faces and photos are read-only. */
data class MomentParticipantsState(val momentId: String, val revision: Long, val manual: Boolean,
    val selectedIds: Set<String>, val automaticIds: Set<String>, val people: List<MomentParticipantPersonRow>)
enum class MomentParticipantsWrite { Saved, Conflict, Unavailable }

class MomentParticipantsRepository(private val database: GalleryDatabase, private val algorithmVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val dao = database.momentParticipantsDao()
    fun observe(momentId: String): Flow<MomentParticipantsState?> = database.invalidationTracker.createFlow(
        "moments", "moment_members", "moment_participant_state", "moment_participants", "person_clusters",
        "person_memberships", "media_items", "face_detection_runs", "detected_faces", "face_embeddings",
        "archived_media", "document_annotations", "memory_date_exclusions", "memory_person_exclusions",
        "memory_person_sources"
    ).map { snapshot(momentId) }

    suspend fun snapshot(momentId: String): MomentParticipantsState? = database.withTransaction {
        val moment = database.momentDao().moment(momentId)
        if (moment == null || moment.state == "DISMISSED") return@withTransaction null
        val state = dao.state(momentId)
        val people = dao.people(algorithmVersion, momentId)
        val automatic = dao.automatic(momentId, algorithmVersion).toSet()
        MomentParticipantsState(momentId, state?.revision ?: 0, state?.mode == "MANUAL",
            if (state?.mode == "MANUAL") dao.selected(momentId).toSet() else automatic, automatic, people)
    }

    suspend fun apply(momentId: String, expectedRevision: Long, manual: Boolean,
        selectedIds: Set<String>): MomentParticipantsWrite = database.withTransaction {
        val current = snapshot(momentId) ?: return@withTransaction MomentParticipantsWrite.Unavailable
        if (current.revision != expectedRevision) return@withTransaction MomentParticipantsWrite.Conflict
        val available = current.people.map { it.clusterId }.toSet()
        if (manual && !available.containsAll(selectedIds)) return@withTransaction MomentParticipantsWrite.Unavailable
        dao.initialize(MomentParticipantStateEntity(momentId, "AUTOMATIC", 0))
        check(dao.compareAndSet(momentId, expectedRevision, if (manual) "MANUAL" else "AUTOMATIC") == 1)
        dao.clear(momentId)
        if (manual) dao.insert(selectedIds.sorted().map { MomentParticipantEntity(momentId, it) })
        dao.markEdited(momentId, nowMillis())
        MomentParticipantsWrite.Saved
    }
}
