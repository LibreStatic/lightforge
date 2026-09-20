package com.ugallery.core.model

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * File-instance identity is deliberately independent of content identity: equal bytes may have
 * different decisions.
 */
data class PortableSourceFacts(
    val sourceId: String,
    val sha256: String,
    val sizeBytes: Long,
    val kind: PortableMediaKind,
    val timelineSortMillis: Long,
    val dateTakenMillis: Long?,
    val isFavorite: Boolean,
    val relativePath: String?,
)

enum class PortableMediaKind {
    Image,
    Video,
    Document,
}

data class PortableOrganizationScope(
    val selectedSourceCount: Int,
    val librarySourceCount: Long,
    val omittedReferences: Long,
) {
    val partial: Boolean
        get() = selectedSourceCount.toLong() < librarySourceCount || omittedReferences > 0
}

data class PortablePersonIdentity(
    val identityId: String,
    val displayName: String?,
    val algorithmVersion: String,
)

data class PortableAlbumMember(val sourceId: String, val addedAtMillis: Long)

data class PortableVirtualAlbum(
    val entityId: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val members: List<PortableAlbumMember>,
    val coverSourceId: String? = null,
)

data class PortableMemoryMember(
    val sourceId: String,
    val ordinal: Int,
    val origin: String,
    val score: Float,
)

data class PortableMemory(
    val entityId: String,
    val origin: String,
    val state: String,
    val algorithmVersion: String,
    val title: String?,
    val titleMode: String,
    val isUserEdited: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val members: List<PortableMemoryMember>,
    val coverSourceId: String?,
    val userSelectedCover: Boolean,
    val includeSpecialMedia: Boolean = false,
)

data class PortableSmartExclusion(val sourceId: String, val excludedAtMillis: Long)

data class PortableSmartAlbum(
    val entityId: String,
    val name: String,
    val topic: String?,
    val personIdentityId: String?,
    val year: Int?,
    val zoneId: String,
    val fromMillis: Long?,
    val untilMillis: Long?,
    val favoritesOnly: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val exclusions: List<PortableSmartExclusion>,
)

data class PortableStackMember(val sourceId: String, val ordinal: Int)

data class PortablePhotoStack(
    val entityId: String,
    val title: String?,
    val coverSourceId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val members: List<PortableStackMember>,
)

data class PortableMediaDecision(
    val sourceId: String,
    val archivedAtMillis: Long?,
    val documentCategory: String?,
    val documentUpdatedAtMillis: Long?,
    val stackSeparatedAtMillis: Long?,
    val similarityExcludedAtMillis: Long?,
    val autoArchiveHistoryPresent: Boolean,
    val autoArchiveWrittenAtMillis: Long?,
)

data class PortableMemoryDateRule(
    val entityId: String,
    val startDay: Long,
    val endDay: Long,
    val zoneId: String,
    val fromMillis: Long,
    val untilMillis: Long,
    val createdAtMillis: Long,
)

data class PortableMemoryPersonRule(
    val entityId: String,
    val personIdentityId: String,
    val createdAtMillis: Long,
    val knownSourceIds: List<String>,
)

/**
 * A singleton automation configuration is preserved for review, never activated by organization
 * import.
 */
data class PortableAutoArchiveReview(
    val enabled: Boolean,
    val category: String,
    val minimumAgeDays: Int,
)

data class PortableOrganizationTotals(
    val sources: Int,
    val entities: Int,
    val relationships: Long,
    val unresolvedPeople: Int,
    val reviewOnlyItems: Int,
)

data class PortableOrganizationSnapshot(
    val schemaVersion: Int = 1,
    val snapshotId: String,
    val originNamespace: String,
    val createdAtMillis: Long,
    val scope: PortableOrganizationScope,
    val sources: List<PortableSourceFacts>,
    val people: List<PortablePersonIdentity> = emptyList(),
    val albums: List<PortableVirtualAlbum> = emptyList(),
    val memories: List<PortableMemory> = emptyList(),
    val smartAlbums: List<PortableSmartAlbum> = emptyList(),
    val stacks: List<PortablePhotoStack> = emptyList(),
    val decisions: List<PortableMediaDecision> = emptyList(),
    val memoryDates: List<PortableMemoryDateRule> = emptyList(),
    val memoryPeople: List<PortableMemoryPersonRule> = emptyList(),
    val autoArchiveReview: PortableAutoArchiveReview? = null,
    val preferencesJsonForReview: String? = null,
    val photoRecipes: List<PortablePhotoRecipe> = emptyList(),
) {
    fun totals() =
        PortableOrganizationTotals(
            sources.size,
            people.size +
                albums.size +
                memories.size +
                smartAlbums.size +
                stacks.size +
                decisions.size +
                memoryDates.size +
                memoryPeople.size +
                photoRecipes.size,
            albums.sumOf { it.members.size.toLong() + if (it.coverSourceId == null) 0 else 1 } +
                memories.sumOf {
                    it.members.size.toLong() + if (it.coverSourceId == null) 0 else 1
                } +
                smartAlbums.sumOf { it.exclusions.size.toLong() } +
                stacks.sumOf { it.members.size.toLong() + if (it.coverSourceId == null) 0 else 1 } +
                memoryPeople.sumOf { it.knownSourceIds.size.toLong() } +
                photoRecipes.sumOf { it.operations.size.toLong() },
            people.size,
            (if (autoArchiveReview == null) 0 else 1) +
                if (preferencesJsonForReview == null) 0 else 1,
        )

    fun validate(): PortableOrganizationSnapshot = apply {
        require(schemaVersion in 1..4)
        require(schemaVersion >= 4 || albums.all { it.coverSourceId == null })
        require(schemaVersion >= 2 || photoRecipes.isEmpty())
        require(schemaVersion >= 3 || memories.none { it.includeSpecialMedia })
        uuid(snapshotId)
        uuid(originNamespace)
        require(
            scope.selectedSourceCount == sources.size &&
                scope.librarySourceCount >= 0 &&
                scope.omittedReferences >= 0
        )
        require(
            sources.size <= MaximumSources &&
                totals().entities <= MaximumEntities &&
                totals().relationships <= MaximumRelationships
        )
        unique(sources.map { it.sourceId })
        sources.forEach {
            uuid(it.sourceId)
            require(it.sha256.matches(Regex("[a-f0-9]{64}")) && it.sizeBytes >= 0)
            text(it.relativePath)
        }
        val refs = sources.map { it.sourceId }.toSet()
        fun ref(id: String) {
            require(id in refs) { "Missing source reference" }
        }
        unique(people.map { it.identityId })
        people.forEach {
            uuid(it.identityId)
            text(it.displayName)
            text(it.algorithmVersion)
            require(it.algorithmVersion.isNotBlank())
        }
        require(photoRecipes.size <= MaximumSources)
        unique(photoRecipes.map { it.sourceId })
        val sourceKinds = sources.associate { it.sourceId to it.kind }
        photoRecipes.forEach {
            it.validate()
            require(sourceKinds[it.sourceId] == PortableMediaKind.Image) {
                "Photo recipe requires an image source"
            }
        }
        val identities = people.map { it.identityId }.toSet()
        fun identity(id: String) {
            require(id in identities) { "Missing person reference" }
        }
        unique(albums.map { it.entityId })
        albums.forEach {
            require(it.entityId.isNotBlank())
            text(it.entityId)
            name(it.name)
            unique(it.members.map { it.sourceId })
            it.members.forEach { m -> ref(m.sourceId) }
            it.coverSourceId?.let { cover ->
                ref(cover)
                require(it.members.any { member -> member.sourceId == cover })
            }
        }
        unique(memories.map { it.entityId })
        memories.forEach {
            require(it.entityId.isNotBlank())
            text(it.entityId)
            text(it.origin)
            text(it.state)
            text(it.algorithmVersion)
            text(it.title)
            text(it.titleMode)
            require(it.state in setOf("SAVED", "SUGGESTED", "DISMISSED"))
            unique(it.members.map { m -> m.sourceId })
            unique(it.members.map { m -> m.ordinal })
            it.members.forEach { m ->
                ref(m.sourceId)
                require(m.ordinal >= 0 && m.score.isFinite())
                text(m.origin)
            }
            it.coverSourceId?.let(::ref)
        }
        unique(smartAlbums.map { it.entityId })
        smartAlbums.forEach {
            require(it.entityId.isNotBlank())
            text(it.entityId)
            name(it.name)
            text(it.topic)
            it.personIdentityId?.let(::identity)
            ZoneId.of(it.zoneId)
            require(it.year == null || it.year in 1..9998)
            require((it.fromMillis == null) == (it.untilMillis == null))
            require((it.year == null) == (it.fromMillis == null))
            if (it.fromMillis != null) {
                require(it.fromMillis < requireNotNull(it.untilMillis))
                val firstDay = LocalDate.of(requireNotNull(it.year), 1, 1)
                plausibleCivilBound(it.fromMillis, firstDay)
                plausibleCivilBound(it.untilMillis, firstDay.plusYears(1))
            }
            unique(it.exclusions.map { x -> x.sourceId })
            it.exclusions.forEach { x -> ref(x.sourceId) }
        }
        unique(stacks.map { it.entityId })
        val stacked = mutableSetOf<String>()
        stacks.forEach {
            require(it.entityId.isNotBlank())
            text(it.entityId)
            text(it.title)
            require(it.members.size <= 500)
            unique(it.members.map { m -> m.ordinal })
            it.members.forEach { m ->
                ref(m.sourceId)
                require(m.ordinal >= 0 && stacked.add(m.sourceId))
            }
            it.coverSourceId?.let { id ->
                ref(id)
                require(it.members.any { m -> m.sourceId == id })
            }
        }
        unique(decisions.map { it.sourceId })
        decisions.forEach {
            ref(it.sourceId)
            require(
                it.documentCategory == null ||
                    it.documentCategory in setOf("Receipt", "Ticket", "Note", "Other", "Excluded")
            )
            require(it.autoArchiveHistoryPresent || it.autoArchiveWrittenAtMillis == null)
        }
        require(memoryDates.size + memoryPeople.size <= 200)
        unique(memoryDates.map { it.entityId })
        unique(memoryDates.map { Triple(it.startDay, it.endDay, it.zoneId) })
        memoryDates.forEach {
            require(it.entityId.isNotBlank())
            text(it.entityId)
            require(
                LocalDate.ofEpochDay(it.startDay).year in 1..9998 &&
                    LocalDate.ofEpochDay(it.endDay).year in 1..9998 &&
                    it.endDay >= it.startDay
            )
            ZoneId.of(it.zoneId)
            require(it.fromMillis < it.untilMillis)
            plausibleCivilBound(it.fromMillis, LocalDate.ofEpochDay(it.startDay))
            plausibleCivilBound(it.untilMillis, LocalDate.ofEpochDay(it.endDay).plusDays(1))
        }
        unique(memoryPeople.map { it.entityId })
        unique(memoryPeople.map { it.personIdentityId })
        memoryPeople.forEach {
            require(it.entityId.isNotBlank())
            text(it.entityId)
            identity(it.personIdentityId)
            unique(it.knownSourceIds)
            it.knownSourceIds.forEach(::ref)
        }
        autoArchiveReview?.let {
            require(it.minimumAgeDays in 0..3650)
            text(it.category)
        }
        require(
            preferencesJsonForReview == null ||
                preferencesJsonForReview.toByteArray(Charsets.UTF_8).size <= MaximumPreferencesBytes
        )
    }

    companion object {
        const val MaximumSources = 10_000
        const val MaximumEntities = 10_000
        const val MaximumRelationships = 100_000L
        const val MaximumBytes = 32 * 1024 * 1024
        const val MaximumPreferencesBytes = 1024 * 1024

        internal fun uuid(value: String) {
            require(UUID.fromString(value).toString() == value)
        }

        private fun text(value: String?) {
            require(value == null || value.toByteArray(Charsets.UTF_8).size <= 4096)
        }

        /**
         * Java timezone offsets lie within +/-18 hours. Check civil plausibility without applying
         * this device timezone database to frozen instants (historical/DST rules may have changed).
         * Comparing a bounded range rather than subtracting the input also avoids Long overflow.
         */
        private fun plausibleCivilBound(value: Long, day: LocalDate) {
            val nominalUtcMidnight = Math.multiplyExact(day.toEpochDay(), 86_400_000L)
            val largestOffset = 18 * 60 * 60 * 1000L
            require(
                value in (nominalUtcMidnight - largestOffset)..(nominalUtcMidnight + largestOffset)
            ) {
                "Stored instant does not correspond to its declared civil date"
            }
        }

        private fun name(value: String) {
            require(value.isNotBlank() && value.length <= 80)
            text(value)
        }

        private fun <T> unique(items: List<T>) {
            require(items.size == items.toSet().size) { "Duplicate organization identity" }
        }
    }
}
