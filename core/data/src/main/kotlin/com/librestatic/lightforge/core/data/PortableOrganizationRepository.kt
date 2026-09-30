package com.librestatic.lightforge.core.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.model.*
import java.util.Locale
import java.util.UUID

/**
 * Digest/generation attestations come from the live export/restore adapter, never from an archive
 * identity lookup.
 */
data class PortableSourceBinding(
    val sourceId: String,
    val key: MediaKey,
    val generationModified: Long,
    val sha256: String,
    val sizeBytes: Long,
)

data class PortableRestoredSource(
    val sourceId: String,
    val key: MediaKey,
    val generationModified: Long,
    val sha256: String,
    val sizeBytes: Long,
    val restoreOperationId: String,
)

data class PortableImportOptions(
    val allowPartial: Boolean = false,
    val importGlobalMemoryRules: Boolean = false,
    val virtualAlbumNameOverrides: Map<String, String> = emptyMap(),
)

data class PortableImportResult(
    val namespace: String,
    val createdIds: Map<String, String>,
    val createdMemoryDateIds: List<String>,
    val createdMemoryPersonIds: List<String>,
    val reusedDateCount: Int,
    val skippedGlobalRules: Int,
    val unresolvedPeople: Int,
    val reviewOnlyItems: Int,
    val omittedEmptyStackCount: Int = 0,
)

class PortableOrganizationConflict(val kind: String) : IllegalStateException(kind)

/**
 * All SQL/table/column tokens below are constants authored in this file. Archive bytes are only
 * bound values.
 */
class PortableOrganizationRepository(
    private val db: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(
        bindings: List<PortableSourceBinding>,
        preferencesJson: String? = null,
    ): PortableOrganizationSnapshot =
        db.withTransaction {
            require(bindings.size <= PortableOrganizationSnapshot.MaximumSources)
            require(
                bindings.map { it.sourceId }.distinct().size == bindings.size &&
                    bindings.map { it.key }.distinct().size == bindings.size
            )
            val sql = db.openHelper.writableDatabase
            var decodedRows = 0
            fun <T> readRows(
                sql: SupportSQLiteDatabase,
                statement: String,
                args: Array<out Any?> = emptyArray(),
                read: (Cursor) -> T,
            ): List<T> =
                rows(sql, statement, args) { cursor ->
                    require(++decodedRows <= 120_000) {
                        "Organization exceeds bounded export row limit"
                    }
                    read(cursor)
                }

            val keyed = bindings.associate { it.key to it.sourceId }
            var omitted = 0L
            fun source(volume: String, id: Long): String? =
                keyed[MediaKey(volume, id)].also { if (it == null) omitted++ }
            val sources =
                bindings.map { binding ->
                    val row =
                        db.libraryDao().media(binding.key.volumeName, binding.key.mediaStoreId)
                            ?: throw PortableOrganizationConflict("SourceMissing")
                    require(
                        row.generationModified == binding.generationModified &&
                            row.sizeBytes == binding.sizeBytes
                    ) {
                        "Source changed during export"
                    }
                    PortableSourceFacts(
                        binding.sourceId,
                        binding.sha256,
                        binding.sizeBytes,
                        when (row.mediaType) {
                            1 -> PortableMediaKind.Image
                            3 -> PortableMediaKind.Video
                            else -> PortableMediaKind.Document
                        },
                        row.timelineSortMillis,
                        row.dateTakenMillis,
                        row.isFavorite,
                        row.relativePath,
                    )
                }
            val people = linkedMapOf<Pair<String, String>, PortablePersonIdentity>()
            fun person(cluster: String, version: String, fallback: String? = null): String {
                return people
                    .getOrPut(cluster to version) {
                        val name =
                            readRows(
                                    sql,
                                    "SELECT displayName FROM person_clusters WHERE clusterId=? AND algorithmVersion=?",
                                    arrayOf(cluster, version),
                                ) {
                                    it.nullText("displayName")
                                }
                                .firstOrNull() ?: fallback
                        PortablePersonIdentity(UUID.randomUUID().toString(), name, version)
                    }
                    .identityId
            }
            val albums =
                readRows(sql, "SELECT * FROM virtual_albums ORDER BY albumId") { c ->
                    val id = c.long("albumId")
                    val members =
                        readRows(
                                sql,
                                "SELECT * FROM virtual_album_media WHERE albumId=? ORDER BY addedAtMillis,volumeName,mediaStoreId",
                                arrayOf(id),
                            ) { m ->
                                source(m.text("volumeName"), m.long("mediaStoreId"))?.let {
                                    PortableAlbumMember(it, m.long("addedAtMillis"))
                                }
                            }
                            .filterNotNull()
                    PortableVirtualAlbum(
                        id.toString(),
                        c.text("name"),
                        c.long("createdAtMillis"),
                        c.long("updatedAtMillis"),
                        members,
                        c.nullText("chosenCoverVolumeName")?.let { volume ->
                            c.nullLong("chosenCoverMediaStoreId")?.let { mediaId -> source(volume, mediaId) }
                        }?.takeIf { cover ->
                            members.any { it.sourceId == cover }.also { retained -> if (!retained) omitted++ }
                        },
                    )
                }
            val memories =
                readRows(sql, "SELECT * FROM moments ORDER BY momentId") { c ->
                    val id = c.text("momentId")
                    val members =
                        readRows(
                                sql,
                                "SELECT * FROM moment_members WHERE momentId=? ORDER BY ordinal",
                                arrayOf(id),
                            ) { m ->
                                source(m.text("volumeName"), m.long("mediaStoreId"))?.let {
                                    PortableMemoryMember(
                                        it,
                                        m.int("ordinal"),
                                        m.text("origin"),
                                        m.float("score"),
                                    )
                                }
                            }
                            .filterNotNull()
                    val cover =
                        readRows(
                                sql,
                                "SELECT * FROM moment_covers WHERE momentId=?",
                                arrayOf(id),
                            ) { m ->
                                source(m.text("volumeName"), m.long("mediaStoreId")) to
                                    (m.int("isUserSelected") != 0)
                            }
                            .firstOrNull()
                    PortableMemory(
                        id,
                        c.text("origin"),
                        c.text("state"),
                        c.text("algorithmVersion"),
                        c.nullText("title"),
                        c.text("titleMode"),
                        c.int("isUserEdited") != 0,
                        c.long("startMillis"),
                        c.long("endMillis"),
                        c.long("createdAtMillis"),
                        c.long("updatedAtMillis"),
                        members,
                        cover?.first,
                        cover?.second ?: false,
                        includeSpecialMedia = c.int("includeSpecialMedia") != 0,
                    )
                }
            val smart =
                readRows(sql, "SELECT * FROM smart_albums ORDER BY albumId") { c ->
                    val id = c.text("albumId")
                    val exclusions =
                        readRows(
                                sql,
                                "SELECT * FROM smart_album_exclusions WHERE albumId=? ORDER BY volumeName,mediaStoreId",
                                arrayOf(id),
                            ) { x ->
                                source(x.text("volumeName"), x.long("mediaStoreId"))?.let {
                                    PortableSmartExclusion(it, x.long("excludedAtMillis"))
                                }
                            }
                            .filterNotNull()
                    PortableSmartAlbum(
                        id,
                        c.text("name"),
                        c.nullText("topic"),
                        c.nullText("personClusterId")?.let {
                            person(it, c.text("personAlgorithmVersion"))
                        },
                        c.nullLong("year")?.toInt(),
                        c.text("zoneId"),
                        c.nullLong("fromMillis"),
                        c.nullLong("untilMillis"),
                        c.int("favoritesOnly") != 0,
                        c.long("createdAtMillis"),
                        c.long("updatedAtMillis"),
                        exclusions,
                    )
                }
            val stacks =
                readRows(sql, "SELECT * FROM photo_stacks ORDER BY stackId") { c ->
                    val id = c.text("stackId")
                    val members =
                        readRows(
                                sql,
                                "SELECT * FROM photo_stack_members WHERE stackId=? ORDER BY ordinal,volumeName,mediaStoreId",
                                arrayOf(id),
                            ) { m ->
                                source(m.text("volumeName"), m.long("mediaStoreId"))?.let {
                                    PortableStackMember(it, m.int("ordinal"))
                                }
                            }
                            .filterNotNull()
                    PortablePhotoStack(
                        id,
                        c.nullText("title"),
                        source(c.text("coverVolumeName"), c.long("coverMediaStoreId"))?.takeIf {
                            cover ->
                            members.any { it.sourceId == cover }
                        },
                        c.long("createdAtMillis"),
                        c.long("updatedAtMillis"),
                        members,
                    )
                }
            val decisions =
                readRows(
                        sql,
                        """SELECT m.volumeName,m.mediaStoreId,a.archivedAtMillis,d.category,d.updatedAtMillis AS documentUpdatedAt,s.separatedAtMillis,x.excludedAtMillis,h.runId,h.writtenAtMillis FROM media_items m
LEFT JOIN archived_media a ON a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId
LEFT JOIN document_annotations d ON d.volumeName=m.volumeName AND d.mediaStoreId=m.mediaStoreId
LEFT JOIN photo_stack_exclusions s ON s.volumeName=m.volumeName AND s.mediaStoreId=m.mediaStoreId
LEFT JOIN similarity_exclusions x ON x.volumeName=m.volumeName AND x.mediaStoreId=m.mediaStoreId
LEFT JOIN document_archive_history h ON h.volumeName=m.volumeName AND h.mediaStoreId=m.mediaStoreId AND h.generationModified=m.generationModified
WHERE a.mediaStoreId IS NOT NULL OR d.mediaStoreId IS NOT NULL OR s.mediaStoreId IS NOT NULL OR x.mediaStoreId IS NOT NULL OR h.mediaStoreId IS NOT NULL
ORDER BY m.volumeName,m.mediaStoreId""",
                    ) { c ->
                        source(c.text("volumeName"), c.long("mediaStoreId"))?.let {
                            PortableMediaDecision(
                                it,
                                c.nullLong("archivedAtMillis"),
                                c.nullText("category"),
                                c.nullLong("documentUpdatedAt"),
                                c.nullLong("separatedAtMillis"),
                                c.nullLong("excludedAtMillis"),
                                c.nullText("runId") != null,
                                c.nullLong("writtenAtMillis"),
                            )
                        }
                    }
                    .filterNotNull()
            val dates =
                readRows(sql, "SELECT * FROM memory_date_exclusions ORDER BY ruleId") { c ->
                    PortableMemoryDateRule(
                        c.text("ruleId"),
                        c.long("startDay"),
                        c.long("endDay"),
                        c.text("zoneId"),
                        c.long("fromMillis"),
                        c.long("untilMillis"),
                        c.long("createdAtMillis"),
                    )
                }
            val matches =
                readRows(sql, KnownMatches) { c ->
                        Triple(c.text("ruleId"), c.text("volumeName"), c.long("mediaStoreId"))
                    }
                    .groupBy { it.first }
            val rules =
                readRows(sql, "SELECT * FROM memory_person_exclusions ORDER BY ruleId") { c ->
                    val id = c.text("ruleId")
                    PortableMemoryPersonRule(
                        id,
                        person(
                            c.text("clusterId"),
                            c.text("algorithmVersion"),
                            c.nullText("displayName"),
                        ),
                        c.long("createdAtMillis"),
                        matches[id].orEmpty().mapNotNull { source(it.second, it.third) }.distinct(),
                    )
                }
            val auto =
                readRows(sql, "SELECT * FROM document_archive_rule WHERE id=1") { c ->
                        PortableAutoArchiveReview(
                            c.int("enabled") != 0,
                            c.text("category"),
                            c.int("minimumAgeDays"),
                        )
                    }
                    .firstOrNull()
            val bindingsByKey = bindings.associateBy { it.key }
            val photoRecipes = readRows(sql, "SELECT * FROM edit_recipes ORDER BY recipeId") { c ->
                val key = MediaKey(c.text("volumeName"), c.long("mediaStoreId"))
                val binding = bindingsByKey[key]
                if (binding == null || binding.generationModified != c.long("sourceGenerationModified")) {
                    omitted++
                    null
                } else {
                    val operations = readRows(
                        sql,
                        "SELECT ordinal,encodedOperation FROM edit_operations WHERE recipeId=? ORDER BY ordinal LIMIT 501",
                        arrayOf(c.text("recipeId")),
                    ) { op -> op.int("ordinal") to op.text("encodedOperation") }
                    require(operations.size <= 500 && operations.map { it.first } == operations.indices.toList()) {
                        "Photo recipe operation history is incomplete or exceeds its portable limit"
                    }
                    PortablePhotoRecipe(
                        binding.sourceId, c.int("revision"), c.long("createdAtMillis"),
                        c.long("updatedAtMillis"), operations.map { it.second },
                    )
                }
            }.filterNotNull()
            val count =
                readRows(sql, "SELECT COUNT(*) AS n FROM media_items") { it.long("n") }.single()
            PortableOrganizationSnapshot(
                    schemaVersion = if (albums.any { it.coverSourceId != null }) 4 else if (memories.any { it.includeSpecialMedia }) 3 else 2,
                    snapshotId = UUID.randomUUID().toString(),
                    originNamespace = UUID.randomUUID().toString(),
                    createdAtMillis = now(),
                    scope = PortableOrganizationScope(sources.size, count, omitted),
                    sources = sources,
                    people = people.values.toList(),
                    albums = albums,
                    memories = memories,
                    smartAlbums = smart,
                    stacks = stacks,
                    decisions = decisions,
                    memoryDates = dates,
                    memoryPeople = rules,
                    autoArchiveReview = auto,
                    preferencesJsonForReview = preferencesJson,
                    photoRecipes = photoRecipes,
                )
                .validate()
        }

    suspend fun importSnapshot(
        snapshot: PortableOrganizationSnapshot,
        mapping: List<PortableRestoredSource>,
        options: PortableImportOptions,
        namespace: UUID,
    ): PortableImportResult =
        db.withTransaction {
            snapshot.validate()
            if (snapshot.scope.partial && !options.allowPartial)
                throw PortableOrganizationConflict("PartialSnapshotNeedsReview")
            val ns = namespace.toString()
            require(
                mapping.size == snapshot.sources.size &&
                    mapping.map { it.sourceId }.distinct().size == mapping.size &&
                    mapping.map { it.key }.distinct().size == mapping.size
            )
            val mapped = mapping.associateBy { it.sourceId }
            val facts = snapshot.sources.associateBy { it.sourceId }
            require(mapped.keys == facts.keys)
            mapping.forEach { m ->
                require(m.restoreOperationId == ns)
                val f = facts.getValue(m.sourceId)
                require(m.sha256 == f.sha256 && m.sizeBytes == f.sizeBytes)
                val row =
                    db.libraryDao().media(m.key.volumeName, m.key.mediaStoreId)
                        ?: throw PortableOrganizationConflict("RestoredSourceMissing")
                require(
                    row.generationModified == m.generationModified && row.sizeBytes == m.sizeBytes
                )
            }
            val sql = db.openHelper.writableDatabase
            // Existing decisions on a supposedly new target contradict the adapter attestation.
            mapping.forEach { m ->
                val args = arrayOf<Any>(m.key.volumeName, m.key.mediaStoreId)
                if (rows(sql, ExistingDecisions, args) { it.long("n") }.single() != 0L)
                    throw PortableOrganizationConflict("TargetAlreadyOrganized")
            }
            val names =
                snapshot.albums.associate { a ->
                    val name =
                        (options.virtualAlbumNameOverrides[a.entityId] ?: a.name)
                            .trim()
                            .replace(Regex("\\s+"), " ")
                    require(name.isNotBlank() && name.length <= 80)
                    a.entityId to name
                }
            require(names.values.map { it.lowercase(Locale.ROOT) }.distinct().size == names.size)
            names.values.forEach { name ->
                if (
                    rows(
                            sql,
                            "SELECT COUNT(*) AS n FROM virtual_albums WHERE normalizedName=?",
                            arrayOf(name.lowercase(Locale.ROOT)),
                        ) {
                            it.long("n")
                        }
                        .single() > 0
                )
                    throw PortableOrganizationConflict("VirtualAlbumNameExists")
            }
            fun fresh(kind: String, original: String) =
                UUID.nameUUIDFromBytes("$ns|$kind|$original".toByteArray(Charsets.UTF_8)).toString()
            fun source(id: String) = mapped.getValue(id)
            fun person(id: String) = "portable:$ns:$id"
            val identities = snapshot.people.associateBy { it.identityId }
            val resultIds = linkedMapOf<String, String>()
            val createdDates = mutableListOf<String>()
            val createdPeople = mutableListOf<String>()
            var reusedDates = 0
            val newDates =
                if (!options.importGlobalMemoryRules) emptyList()
                else
                    snapshot.memoryDates.filter { r ->
                        val found =
                            rows(
                                    sql,
                                    "SELECT ruleId FROM memory_date_exclusions WHERE startDay=? AND endDay=? AND zoneId=?",
                                    arrayOf<Any?>(r.startDay, r.endDay, r.zoneId),
                                ) {
                                    it.text("ruleId")
                                }
                                .firstOrNull()
                        if (found != null) {
                            reusedDates++
                            false
                        } else true
                    }
            if (options.importGlobalMemoryRules) {
                val existing =
                    rows(
                            sql,
                            "SELECT (SELECT COUNT(*) FROM memory_date_exclusions)+(SELECT COUNT(*) FROM memory_person_exclusions) AS n",
                        ) {
                            it.long("n")
                        }
                        .single()
                require(
                    existing + newDates.size + snapshot.memoryPeople.size <=
                        MemoryExclusionRepository.MaximumRules
                )
            }
            snapshot.albums.forEach { a ->
                val target =
                    insert(
                        sql,
                        Table.Albums,
                        "name" to names.getValue(a.entityId),
                        "normalizedName" to names.getValue(a.entityId).lowercase(Locale.ROOT),
                        "createdAtMillis" to a.createdAtMillis,
                        "updatedAtMillis" to a.updatedAtMillis,
                        "chosenCoverVolumeName" to a.coverSourceId?.let { source(it).key.volumeName },
                        "chosenCoverMediaStoreId" to a.coverSourceId?.let { source(it).key.mediaStoreId },
                    )
                resultIds["album:${a.entityId}"] = target.toString()
                a.members.forEach { member ->
                    val m = source(member.sourceId)
                    insert(
                        sql,
                        Table.AlbumMembers,
                        "albumId" to target,
                        "volumeName" to m.key.volumeName,
                        "mediaStoreId" to m.key.mediaStoreId,
                        "addedAtMillis" to member.addedAtMillis,
                    )
                }
            }
            snapshot.memories.forEach { a ->
                val target = fresh("memory", a.entityId)
                resultIds["memory:${a.entityId}"] = target
                insert(
                    sql,
                    Table.Memories,
                    "momentId" to target,
                    "origin" to a.origin,
                    "state" to a.state,
                    "algorithmVersion" to a.algorithmVersion,
                    "title" to a.title,
                    "titleMode" to a.titleMode,
                    "isUserEdited" to a.isUserEdited,
                    "includeSpecialMedia" to a.includeSpecialMedia,
                    "startMillis" to a.startMillis,
                    "endMillis" to a.endMillis,
                    "createdAtMillis" to a.createdAtMillis,
                    "updatedAtMillis" to a.updatedAtMillis,
                )
                a.members.forEach { member ->
                    val m = source(member.sourceId)
                    insert(
                        sql,
                        Table.MemoryMembers,
                        "momentId" to target,
                        "ordinal" to member.ordinal,
                        "volumeName" to m.key.volumeName,
                        "mediaStoreId" to m.key.mediaStoreId,
                        "generationModifiedAtSelection" to m.generationModified,
                        "origin" to member.origin,
                        "score" to member.score,
                    )
                }
                a.coverSourceId?.let {
                    val m = source(it)
                    insert(
                        sql,
                        Table.MemoryCovers,
                        "momentId" to target,
                        "volumeName" to m.key.volumeName,
                        "mediaStoreId" to m.key.mediaStoreId,
                        "isUserSelected" to a.userSelectedCover,
                    )
                }
            }
            snapshot.smartAlbums.forEach { a ->
                val target = fresh("smart", a.entityId)
                resultIds["smart:${a.entityId}"] = target
                insert(
                    sql,
                    Table.Smart,
                    "albumId" to target,
                    "name" to a.name,
                    "topic" to a.topic,
                    "personClusterId" to a.personIdentityId?.let(::person),
                    "personAlgorithmVersion" to
                        a.personIdentityId?.let { "portable-unresolved-v1" },
                    "year" to a.year,
                    "zoneId" to a.zoneId,
                    "fromMillis" to a.fromMillis,
                    "untilMillis" to a.untilMillis,
                    "favoritesOnly" to a.favoritesOnly,
                    "revision" to fresh("smart-revision", a.entityId),
                    "createdAtMillis" to a.createdAtMillis,
                    "updatedAtMillis" to a.updatedAtMillis,
                )
                a.exclusions.forEach { x ->
                    val m = source(x.sourceId)
                    insert(
                        sql,
                        Table.SmartExclusions,
                        "albumId" to target,
                        "volumeName" to m.key.volumeName,
                        "mediaStoreId" to m.key.mediaStoreId,
                        "token" to fresh("smart-token", "${a.entityId}|${x.sourceId}"),
                        "excludedAtMillis" to x.excludedAtMillis,
                    )
                }
            }
            snapshot.stacks.forEach { a ->
                // Empty/one-member imported stacks retain decisions and will remain reviewable
                // after a partial restore.
                if (a.members.isNotEmpty()) {
                    val target = fresh("stack", a.entityId)
                    resultIds["stack:${a.entityId}"] = target
                    val cover = source(a.coverSourceId ?: a.members.minBy { it.ordinal }.sourceId)
                    insert(
                        sql,
                        Table.Stacks,
                        "stackId" to target,
                        "title" to a.title,
                        "coverVolumeName" to cover.key.volumeName,
                        "coverMediaStoreId" to cover.key.mediaStoreId,
                        "revision" to fresh("stack-revision", a.entityId),
                        "createdAtMillis" to a.createdAtMillis,
                        "updatedAtMillis" to a.updatedAtMillis,
                    )
                    a.members.forEach { member ->
                        val m = source(member.sourceId)
                        insert(
                            sql,
                            Table.StackMembers,
                            "stackId" to target,
                            "ordinal" to member.ordinal,
                            "volumeName" to m.key.volumeName,
                            "mediaStoreId" to m.key.mediaStoreId,
                        )
                    }
                }
            }
            snapshot.decisions.forEach { d ->
                val m = source(d.sourceId)
                val key =
                    arrayOf("volumeName" to m.key.volumeName, "mediaStoreId" to m.key.mediaStoreId)
                d.archivedAtMillis?.let {
                    insert(sql, Table.Archived, *key, "archivedAtMillis" to it)
                }
                d.documentCategory?.let {
                    insert(
                        sql,
                        Table.Documents,
                        *key,
                        "category" to it,
                        "updatedAtMillis" to (d.documentUpdatedAtMillis ?: now()),
                    )
                }
                d.stackSeparatedAtMillis?.let {
                    insert(sql, Table.StackExclusions, *key, "separatedAtMillis" to it)
                }
                d.similarityExcludedAtMillis?.let {
                    insert(sql, Table.SimilarityExclusions, *key, "excludedAtMillis" to it)
                }
                if (d.autoArchiveHistoryPresent)
                    insert(
                        sql,
                        Table.ArchiveHistory,
                        *key,
                        "generationModified" to m.generationModified,
                        "runId" to fresh("archive-history", d.sourceId),
                        "writtenAtMillis" to d.autoArchiveWrittenAtMillis,
                    )
            }
            snapshot.photoRecipes.forEach { recipe ->
                val target = source(recipe.sourceId)
                val recipeId = EditRecipeIds.forSource(target.key, target.generationModified)
                insert(
                    sql, Table.PhotoRecipes,
                    "recipeId" to recipeId,
                    "volumeName" to target.key.volumeName,
                    "mediaStoreId" to target.key.mediaStoreId,
                    "sourceGenerationModified" to target.generationModified,
                    "revision" to recipe.revision,
                    "createdAtMillis" to recipe.createdAtMillis,
                    "updatedAtMillis" to recipe.updatedAtMillis,
                )
                recipe.operations.forEachIndexed { ordinal, encoded ->
                    insert(sql, Table.PhotoOperations, "recipeId" to recipeId,
                        "ordinal" to ordinal, "encodedOperation" to encoded)
                }
                resultIds["photo-recipe:${recipe.sourceId}"] = recipeId
            }
            if (options.importGlobalMemoryRules) {
                newDates.forEach { a ->
                    val target = fresh("memory-date", a.entityId)
                    createdDates += target
                    insert(
                        sql,
                        Table.MemoryDates,
                        "ruleId" to target,
                        "startDay" to a.startDay,
                        "endDay" to a.endDay,
                        "zoneId" to a.zoneId,
                        "fromMillis" to a.fromMillis,
                        "untilMillis" to a.untilMillis,
                        "createdAtMillis" to a.createdAtMillis,
                    )
                }
                snapshot.memoryPeople.forEach { a ->
                    val target = fresh("memory-person", a.entityId)
                    createdPeople += target
                    insert(
                        sql,
                        Table.MemoryPeople,
                        "ruleId" to target,
                        "clusterId" to person(a.personIdentityId),
                        "algorithmVersion" to "portable-unresolved-v1",
                        "displayName" to identities.getValue(a.personIdentityId).displayName,
                        "createdAtMillis" to a.createdAtMillis,
                    )
                    a.knownSourceIds.forEach { id ->
                        val m = source(id)
                        insert(
                            sql,
                            Table.MemorySources,
                            "ruleId" to target,
                            "volumeName" to m.key.volumeName,
                            "mediaStoreId" to m.key.mediaStoreId,
                        )
                    }
                }
            }
            PortableImportResult(
                ns,
                resultIds,
                createdDates,
                createdPeople,
                reusedDates,
                if (options.importGlobalMemoryRules) 0
                else snapshot.memoryDates.size + snapshot.memoryPeople.size,
                snapshot.people.size,
                snapshot.totals().reviewOnlyItems,
                snapshot.stacks.count { it.members.isEmpty() },
            )
        }

    private enum class Table(val sqlName: String) {
        PhotoRecipes("edit_recipes"),
        PhotoOperations("edit_operations"),
        Albums("virtual_albums"),
        AlbumMembers("virtual_album_media"),
        Memories("moments"),
        MemoryMembers("moment_members"),
        MemoryCovers("moment_covers"),
        Smart("smart_albums"),
        SmartExclusions("smart_album_exclusions"),
        Stacks("photo_stacks"),
        StackMembers("photo_stack_members"),
        StackExclusions("photo_stack_exclusions"),
        SimilarityExclusions("similarity_exclusions"),
        Archived("archived_media"),
        Documents("document_annotations"),
        ArchiveHistory("document_archive_history"),
        MemoryDates("memory_date_exclusions"),
        MemoryPeople("memory_person_exclusions"),
        MemorySources("memory_person_sources"),
    }

    private fun insert(
        sql: SupportSQLiteDatabase,
        table: Table,
        vararg fields: Pair<String, Any?>,
    ): Long {
        val values = ContentValues()
        fields.forEach { (key, value) ->
            when (value) {
                null -> values.putNull(key)
                is String -> values.put(key, value)
                is Long -> values.put(key, value)
                is Int -> values.put(key, value)
                is Float -> values.put(key, value)
                is Boolean -> values.put(key, value)
                else -> error("Unsupported authored field")
            }
        }
        return sql.insert(table.sqlName, SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun <T> rows(
        sql: SupportSQLiteDatabase,
        statement: String,
        args: Array<out Any?> = emptyArray(),
        read: (Cursor) -> T,
    ): List<T> =
        sql.query(statement, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    require(size < 120_000)
                    add(read(c))
                }
            }
        }

    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))

    private fun Cursor.nullText(name: String) =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))

    private fun Cursor.nullLong(name: String) =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

    private fun Cursor.float(name: String) = getFloat(getColumnIndexOrThrow(name))

    companion object {
        // One bound pair, reused through a scalar CTE; covers every imported media-level decision.
        private const val ExistingDecisions =
            """WITH target(v,i) AS (SELECT ?,?) SELECT
(SELECT COUNT(*) FROM edit_recipes,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM virtual_album_media,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM moment_members,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM photo_stack_members,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM archived_media,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM document_annotations,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM photo_stack_exclusions,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM similarity_exclusions,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM document_archive_history,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM memory_person_sources,target WHERE volumeName=v AND mediaStoreId=i)+
(SELECT COUNT(*) FROM smart_album_exclusions,target WHERE volumeName=v AND mediaStoreId=i) AS n"""
        private const val KnownMatches =
            """SELECT ruleId,volumeName,mediaStoreId FROM memory_person_sources UNION
SELECT x.ruleId,m.volumeName,m.mediaStoreId FROM memory_person_exclusions x
JOIN person_clusters c ON c.clusterId=x.clusterId AND c.algorithmVersion=x.algorithmVersion
JOIN person_memberships p ON p.clusterId=c.clusterId AND p.algorithmVersion=c.algorithmVersion
JOIN media_items m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId
JOIN face_detection_runs r ON r.volumeName=p.volumeName AND r.mediaStoreId=p.mediaStoreId
JOIN detected_faces f ON f.volumeName=p.volumeName AND f.mediaStoreId=p.mediaStoreId AND f.faceOrdinal=p.faceOrdinal
JOIN face_embeddings e ON e.volumeName=p.volumeName AND e.mediaStoreId=p.mediaStoreId AND e.faceOrdinal=p.faceOrdinal
WHERE m.mediaType=1 AND r.generationModified=m.generationModified AND f.modelVersion=r.modelVersion AND e.detectionModelVersion=r.modelVersion"""
    }
}
