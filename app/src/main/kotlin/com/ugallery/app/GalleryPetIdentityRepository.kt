package com.ugallery.app

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import androidx.room.withTransaction
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import com.ugallery.feature.petrecognition.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Pet-only, local Room adapter. An embedding ranking never silently establishes an identity. */
class GalleryPetIdentityRepository(
    private val db: GalleryDatabase,
    private val resolver: ContentResolver? = null,
) : PetIdentityRepository {
    private val dao
        get() = db.petIdentityDao()

    override val summary = dao.observeInputs().map { currentSummary() }.distinctUntilChanged()

    private suspend fun state() = dao.state() ?: PetStateEntity().also { dao.ensureState(it) }

    override suspend fun currentSummary(): PetSummary =
        db.withTransaction {
            val s = state()
            PetSummary(
                s.revision,
                s.enabled,
                s.modelFingerprint,
                dao.identityCount(),
                dao.observationCount(null, 0),
                dao.observationCount(null, 1),
                dao.observationCount(null, 2),
                s.undoToken,
            )
        }

    override suspend fun setAnalysisEnabled(
        expectedRevision: Long,
        enabled: Boolean,
        modelFingerprint: String?,
    ): Boolean =
        db.withTransaction {
            val s = state()
            if (s.revision != expectedRevision) return@withTransaction false
            if (enabled)
                require(!modelFingerprint.isNullOrBlank() && modelFingerprint.length <= 256)
            if (s.enabled == enabled && s.modelFingerprint == modelFingerprint && enabled)
                return@withTransaction true
            check(
                dao.compareAndSet(
                    s.revision,
                    enabled,
                    if (enabled) modelFingerprint else null,
                    null,
                ) == 1
            )
            dao.clearUndo()
            if (!enabled) {
                dao.clearObservations()
                dao.clearStamps()
                dao.clearIdentities()
            }
            true
        }

    override suspend fun identitiesPage(afterId: String?, limit: Int): PetPage<PetIdentityCard> =
        db.withTransaction {
            require(limit in 1..100)
            val page = dao.identitiesPage(afterId.orEmpty(), limit + 1)
            PetPage(
                page.take(limit).map { identity ->
                    PetIdentityCard(
                        identity.id,
                        species(identity.species),
                        identity.name,
                        dao.observationCount(identity.id, 0),
                        dao.observationsPage(identity.id, 0, "", 1).firstOrNull()?.card(),
                    )
                },
                if (page.size > limit) page[limit - 1].id else null,
            )
        }

    override suspend fun observationsPage(
        identityId: String?,
        filter: PetObservationFilter,
        afterId: String?,
        limit: Int,
    ): PetPage<PetObservationCard> =
        db.withTransaction {
            require(limit in 1..100)
            val page =
                dao.observationsPage(identityId, filter.ordinal, afterId.orEmpty(), limit + 1)
            PetPage(
                page.take(limit).map { it.card() },
                if (page.size > limit) page[limit - 1].id else null,
            )
        }

    override suspend fun eligibleSources(afterKey: MediaKey?, limit: Int): PetSourcePage =
        db.withTransaction {
            require(limit in 1..64)
            val page =
                dao.eligibleSources(
                    afterKey?.volumeName.orEmpty(),
                    afterKey?.mediaStoreId ?: -1,
                    limit + 1,
                )
            PetSourcePage(
                page.take(limit).map {
                    source(
                        it.volumeName,
                        it.mediaStoreId,
                        it.generationAdded,
                        it.generationModified,
                    )
                },
                if (page.size > limit)
                    page[limit - 1].let { MediaKey(it.volumeName, it.mediaStoreId) }
                else null,
            )
        }

    override suspend fun isCurrent(source: PetMediaSource): Boolean =
        providerCurrent(source) && currentInRoom(source)

    private suspend fun currentInRoom(source: PetMediaSource): Boolean =
        dao.currentSource(
            source.key.volumeName,
            source.key.mediaStoreId,
            source.generationAdded,
            source.generationModified,
        ) != null

    private suspend fun providerCurrent(source: PetMediaSource): Boolean =
        withContext(Dispatchers.IO) {
            val provider =
                resolver ?: return@withContext true // Only isolated Room fixtures omit this.
            val uri =
                this@GalleryPetIdentityRepository.source(
                        source.key.volumeName,
                        source.key.mediaStoreId,
                        source.generationAdded,
                        source.generationModified,
                    )
                    .uri
            if (source.uri != uri) return@withContext false
            try {
                provider
                    .query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.GENERATION_ADDED,
                            MediaStore.MediaColumns.GENERATION_MODIFIED,
                            MediaStore.MediaColumns.IS_TRASHED,
                            MediaStore.MediaColumns.IS_PENDING,
                        ),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        cursor.moveToFirst() &&
                            cursor.getLong(0) == source.generationAdded &&
                            cursor.getLong(1) == source.generationModified &&
                            cursor.getInt(2) == 0 &&
                            cursor.getInt(3) == 0
                    } == true
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: android.database.sqlite.SQLiteException) {
                false
            }
        }

    override suspend fun isAnalyzed(source: PetMediaSource, modelFingerprint: String): Boolean {
        if (!providerCurrent(source)) return false
        return db.withTransaction {
            val s = state()
            s.enabled &&
                s.modelFingerprint == modelFingerprint &&
                currentInRoom(source) &&
                stampMatches(source, modelFingerprint)
        }
    }

    private suspend fun stampMatches(source: PetMediaSource, fingerprint: String): Boolean =
        dao.stamp(source.key.volumeName, source.key.mediaStoreId, fingerprint)?.let {
            it.generationAdded == source.generationAdded &&
                it.generationModified == source.generationModified
        } == true

    override suspend fun commitAnalysis(
        source: PetMediaSource,
        modelFingerprint: String,
        observations: List<PetAnalyzedObservation>,
    ): Boolean {
        require(
            observations.size <= 2000 &&
                observations.map { it.id }.toSet().size == observations.size
        )
        observations.forEach { require(UUID.fromString(it.id).toString() == it.id) }
        // MediaStore and Room have no shared transaction. Verify provider before entering Room;
        // scanner generation filtering invalidates the remaining narrow concurrent-edit window.
        if (!providerCurrent(source)) return false
        return db.withTransaction {
            val s = state()
            if (!s.enabled || s.modelFingerprint != modelFingerprint || !currentInRoom(source))
                return@withTransaction false
            if (stampMatches(source, modelFingerprint)) return@withTransaction true
            if (raw(observations.map { it.id }).isNotEmpty()) return@withTransaction false
            dao.insertObservations(
                observations.map { o ->
                    PetObservationEntity(
                        o.id,
                        source.key.volumeName,
                        source.key.mediaStoreId,
                        source.generationAdded,
                        source.generationModified,
                        modelFingerprint,
                        o.box.left,
                        o.box.top,
                        o.box.right,
                        o.box.bottom,
                        o.species.ordinal,
                        o.detectorScore,
                        encode(o.embedding),
                    )
                }
            )
            // Older reviewed observations remain hidden historical decisions, never
            // bbox-reassociated.
            dao.putStamp(
                PetAnalysisStampEntity(
                    source.key.volumeName,
                    source.key.mediaStoreId,
                    modelFingerprint,
                    source.generationAdded,
                    source.generationModified,
                )
            )
            advance(s, null)
            true
        }
    }

    override suspend fun observationEmbedding(
        observationId: String,
        modelFingerprint: String,
    ): FloatArray? = dao.observationEmbedding(observationId, modelFingerprint)?.let(::decode)

    override suspend fun referenceEmbeddingsPage(
        species: PetSpecies,
        modelFingerprint: String,
        afterId: String?,
        limit: Int,
    ): PetPage<PetReferenceEmbedding> =
        db.withTransaction {
            require(species != PetSpecies.Uncertain && limit in 1..64)
            val page =
                dao.referencePage(species.ordinal, modelFingerprint, afterId.orEmpty(), limit + 1)
            PetPage(
                page.take(limit).map {
                    PetReferenceEmbedding(it.id, checkNotNull(it.identityId), decode(it.embedding))
                },
                if (page.size > limit) page[limit - 1].id else null,
            )
        }

    override suspend fun edit(expectedRevision: Long, edit: PetEdit): PetEditResult =
        db.withTransaction {
            val s = state()
            if (!s.enabled || s.revision != expectedRevision)
                return@withTransaction PetEditResult(false, s.revision)
            val beforeIdentities = mutableMapOf<String, PetIdentityEntity>()
            val beforeRows = mutableMapOf<String, PetObservationEntity>()
            val afterRows = mutableMapOf<String, PetObservationEntity>()
            val created = mutableListOf<PetIdentityEntity>()
            val deleted = mutableSetOf<String>()
            var renamed: PetIdentityEntity? = null
            suspend fun identity(id: String): PetIdentityEntity {
                val value = requireNotNull(dao.identity(id)) { "Pet identity no longer exists" }
                beforeIdentities[id] = value
                return value
            }
            suspend fun select(ids: Set<String>): List<PetObservationEntity> {
                require(ids.isNotEmpty() && ids.size <= 2000)
                val rows = current(ids.toList())
                require(rows.size == ids.size) { "Pet observation is no longer current" }
                rows.forEach { beforeRows[it.id] = it }
                return rows
            }
            fun change(
                rows: List<PetObservationEntity>,
                update: (PetObservationEntity) -> PetObservationEntity,
            ) {
                rows.forEach { afterRows[it.id] = update(it) }
            }
            fun newIdentity(kind: PetSpecies, name: String?): PetIdentityEntity {
                require(kind != PetSpecies.Uncertain)
                return PetIdentityEntity(
                        UUID.randomUUID().toString(),
                        kind.ordinal,
                        normalizeName(name),
                        System.currentTimeMillis(),
                    )
                    .also { created += it }
            }
            suspend fun requireNoPhotoCollision(id: String, rows: List<PetObservationEntity>) {
                val existing = dao.observationsPage(id, 0, "", 2001)
                require(existing.size <= 2000)
                noPhotoCollision((existing + rows).distinctBy { it.id })
            }
            try {
                when (edit) {
                    is PetEdit.CreateIdentity -> {
                        val rows = select(edit.observationIds)
                        require(
                            rows.all {
                                it.identityId == null &&
                                    !it.excluded &&
                                    it.species == edit.species.ordinal
                            }
                        )
                        noPhotoCollision(rows)
                        val new = newIdentity(edit.species, edit.name)
                        change(rows) { it.copy(identityId = new.id) }
                    }
                    is PetEdit.Rename -> {
                        val old = identity(edit.identityId)
                        renamed = old.copy(name = normalizeName(edit.name))
                    }
                    is PetEdit.Merge -> {
                        require(
                            edit.otherIdentityIds.isNotEmpty() &&
                                edit.otherIdentityIds.size < 2000 &&
                                edit.targetIdentityId !in edit.otherIdentityIds
                        )
                        val target = identity(edit.targetIdentityId)
                        edit.otherIdentityIds.forEach {
                            require(identity(it).species == target.species)
                        }
                        val all = identityRows(beforeIdentities.keys.toList())
                        require(all.size <= 2000)
                        if (all.isNotEmpty()) select(all.map { it.id }.toSet())
                        noPhotoCollision(all)
                        change(all) { it.copy(identityId = target.id) }
                        deleted += edit.otherIdentityIds
                    }
                    is PetEdit.Split -> {
                        val old = identity(edit.identityId)
                        val rows = select(edit.observationIds)
                        require(rows.all { it.identityId == old.id && it.species == old.species })
                        noPhotoCollision(rows)
                        val new = newIdentity(species(old.species), edit.name)
                        change(rows) { it.copy(identityId = new.id) }
                    }
                    is PetEdit.Exclude ->
                        change(select(edit.observationIds)) { it.copy(excluded = true) }
                    is PetEdit.Restore ->
                        change(select(edit.observationIds)) { it.copy(excluded = false) }
                    is PetEdit.SetSpecies -> {
                        require(edit.species != PetSpecies.Uncertain)
                        val rows = select(edit.observationIds)
                        require(rows.all { it.identityId == null })
                        change(rows) { it.copy(species = edit.species.ordinal) }
                    }
                    is PetEdit.AcceptSuggestion -> {
                        val target = identity(edit.identityId)
                        val rows = select(edit.observationIds)
                        require(
                            rows.all {
                                it.identityId == null &&
                                    !it.excluded &&
                                    it.species == target.species
                            }
                        )
                        requireNoPhotoCollision(target.id, rows)
                        change(rows) { it.copy(identityId = target.id) }
                    }
                }
                require(beforeRows.size <= 2000 && beforeIdentities.size + created.size <= 2000)
            } catch (_: IllegalArgumentException) {
                return@withTransaction PetEditResult(false, s.revision)
            }
            val changed =
                afterRows.values.filter { after ->
                    beforeRows[after.id]?.let { before ->
                        before.identityId != after.identityId ||
                            before.excluded != after.excluded ||
                            before.species != after.species
                    } == true
                }
            if (
                changed.isEmpty() &&
                    created.isEmpty() &&
                    deleted.isEmpty() &&
                    (renamed == null || renamed == beforeIdentities[renamed!!.id])
            )
                return@withTransaction PetEditResult(false, s.revision)
            val token = UUID.randomUUID().toString()
            val payload =
                undoPayload(
                    beforeIdentities.values.toList(),
                    beforeRows.values.toList(),
                    created.map { it.id },
                )
            dao.putIdentities(created + listOfNotNull(renamed))
            changed.forEach { dao.updateReview(it.id, it.identityId, it.excluded, it.species) }
            if (deleted.isNotEmpty())
                deleted.toList().chunked(400).forEach { dao.deleteIdentities(it) }
            advance(s, token)
            dao.putUndo(PetUndoEntity(token, s.revision + 1, payload))
            PetEditResult(true, s.revision + 1, token)
        }

    override suspend fun undo(expectedRevision: Long, token: String): PetEditResult =
        db.withTransaction {
            val s = state()
            if (!s.enabled || s.revision != expectedRevision || s.undoToken != token)
                return@withTransaction PetEditResult(false, s.revision)
            val undo = dao.undo(token) ?: return@withTransaction PetEditResult(false, s.revision)
            if (undo.revision != expectedRevision)
                return@withTransaction PetEditResult(false, s.revision)
            val json = JSONObject(undo.payload)
            require(json.getInt("version") == 1 && undo.payload.length <= 1024 * 1024)
            val identities = json.getJSONArray("identities")
            val rows = json.getJSONArray("observations")
            val created = json.getJSONArray("created")
            require(identities.length() + created.length() <= 2000 && rows.length() <= 2000)
            val oldRows = List(rows.length()) { rows.getJSONObject(it) }
            val now = current(oldRows.map { it.getString("id") }).associateBy { it.id }
            if (
                oldRows.any { old ->
                    now[old.getString("id")]?.let {
                        it.generationAdded == old.getLong("added") &&
                            it.generationModified == old.getLong("modified") &&
                            it.modelFingerprint == old.getString("fingerprint")
                    } != true
                }
            )
                return@withTransaction PetEditResult(false, s.revision)
            dao.putIdentities(
                List(identities.length()) { i ->
                    identities.getJSONObject(i).let {
                        PetIdentityEntity(
                            it.getString("id"),
                            it.getInt("species"),
                            it.nullable("name"),
                            it.getLong("created"),
                        )
                    }
                }
            )
            oldRows.forEach {
                dao.updateReview(
                    it.getString("id"),
                    it.nullable("identity"),
                    it.getBoolean("excluded"),
                    it.getInt("species"),
                )
            }
            if (created.length() > 0)
                dao.deleteIdentities(List(created.length()) { created.getString(it) })
            advance(s, null)
            PetEditResult(true, s.revision + 1)
        }

    private suspend fun advance(s: PetStateEntity, token: String?) {
        check(dao.compareAndSet(s.revision, s.enabled, s.modelFingerprint, token) == 1)
        dao.clearUndo()
    }

    // SQLite bind variable limits apply to selection too; a 2000-row edit stays bounded in chunks.
    private suspend fun identityRows(ids: List<String>): List<PetObservationEntity> {
        val result = mutableListOf<PetObservationEntity>()
        for (chunk in ids.chunked(400)) {
            result += dao.identityObservations(chunk, 2001 - result.size)
            if (result.size > 2000) break
        }
        return result
    }

    private suspend fun current(ids: List<String>) =
        ids.chunked(400).flatMap { dao.currentObservations(it) }

    private suspend fun raw(ids: List<String>) =
        ids.chunked(400).flatMap { dao.rawObservations(it) }

    private fun normalizeName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 80) }

    private fun species(value: Int) =
        PetSpecies.entries.getOrNull(value) ?: error("Invalid pet species")

    private fun noPhotoCollision(rows: List<PetObservationEntity>) {
        require(
            rows.map { Triple(it.volumeName, it.mediaStoreId, it.generationAdded) }.toSet().size ==
                rows.size
        ) {
            "One identity cannot occur twice in one photo"
        }
    }

    private fun source(volume: String, id: Long, added: Long, modified: Long) =
        PetMediaSource(
            MediaKey(volume, id),
            modified,
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id),
            added,
        )

    private fun PetObservationEntity.card() =
        PetObservationCard(
            id,
            source(volumeName, mediaStoreId, generationAdded, generationModified),
            PetBox(left, top, right, bottom),
            species(species),
            detectorScore,
            identityId,
            excluded,
            modelFingerprint,
        )

    private fun encode(value: FloatArray): ByteArray =
        ByteBuffer.allocate(2048)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> value.forEach(buffer::putFloat) }
            .array()

    private fun decode(value: ByteArray): FloatArray {
        check(value.size == 2048)
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(512) { buffer.float }.also { check(it.all(Float::isFinite)) }
    }

    private fun JSONObject.nullable(key: String) = if (isNull(key)) null else getString(key)

    private fun undoPayload(
        identities: List<PetIdentityEntity>,
        observations: List<PetObservationEntity>,
        created: List<String>,
    ): String {
        val payload =
            JSONObject()
                .put("version", 1)
                .put(
                    "identities",
                    JSONArray().also { array ->
                        identities.forEach {
                            array.put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put("species", it.species)
                                    .put("name", it.name ?: JSONObject.NULL)
                                    .put("created", it.createdAtMillis)
                            )
                        }
                    },
                )
                .put(
                    "observations",
                    JSONArray().also { array ->
                        observations.forEach {
                            array.put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put("identity", it.identityId ?: JSONObject.NULL)
                                    .put("excluded", it.excluded)
                                    .put("species", it.species)
                                    .put("added", it.generationAdded)
                                    .put("modified", it.generationModified)
                                    .put("fingerprint", it.modelFingerprint)
                            )
                        }
                    },
                )
                .put("created", JSONArray(created))
                .toString()
        require(payload.length <= 1024 * 1024)
        return payload
    }
}
