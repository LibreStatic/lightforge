package com.librestatic.lightforge.core.model

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Explicit field order and bounded UTF-8; neither Java serialization nor executable/schema input.
 */
object PortableOrganizationCodec {
    private val magic = byteArrayOf(85, 71, 79, 82, 71, 1, 13, 10)

    fun encode(snapshot: PortableOrganizationSnapshot): ByteArray {
        snapshot.validate()
        val bytes = ByteArrayOutputStream()
        val bounded =
            object : OutputStream() {
                var count = 0

                override fun write(b: Int) {
                    require(count < PortableOrganizationSnapshot.MaximumBytes)
                    bytes.write(b)
                    count++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    require(len <= PortableOrganizationSnapshot.MaximumBytes - count)
                    bytes.write(b, off, len)
                    count += len
                }
            }
        Writer(DataOutputStream(bounded)).apply {
            out.write(magic)
            i(snapshot.schemaVersion)
            s(snapshot.snapshotId)
            s(snapshot.originNamespace)
            l(snapshot.createdAtMillis)
            i(snapshot.scope.selectedSourceCount)
            l(snapshot.scope.librarySourceCount)
            l(snapshot.scope.omittedReferences)
            list(snapshot.sources, ::PortableSourceFacts)
            list(snapshot.people, ::PortablePersonIdentity)
            list(snapshot.albums) { PortableVirtualAlbum(it, snapshot.schemaVersion) }
            list(snapshot.memories) { PortableMemory(it, snapshot.schemaVersion) }
            list(snapshot.smartAlbums, ::PortableSmartAlbum)
            list(snapshot.stacks, ::PortablePhotoStack)
            list(snapshot.decisions, ::PortableMediaDecision)
            list(snapshot.memoryDates, ::PortableMemoryDateRule)
            list(snapshot.memoryPeople, ::PortableMemoryPersonRule)
            b(snapshot.autoArchiveReview != null)
            snapshot.autoArchiveReview?.let(::PortableAutoArchiveReview)
            b(snapshot.preferencesJsonForReview != null)
            snapshot.preferencesJsonForReview?.let {
                s(it, PortableOrganizationSnapshot.MaximumPreferencesBytes)
            }
            if (snapshot.schemaVersion >= 2) list(snapshot.photoRecipes, ::PortablePhotoRecipe)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PortableOrganizationSnapshot {
        require(bytes.size <= PortableOrganizationSnapshot.MaximumBytes)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val header = ByteArray(magic.size)
        input.readFully(header)
        require(header.contentEquals(magic))
        return Reader(input).run {
            val version = i()
            require(version in 1..4)
            val snapshot =
                PortableOrganizationSnapshot(
                    version,
                    s(),
                    s(),
                    l(),
                    PortableOrganizationScope(i(), l(), l()),
                    list(::PortableSourceFacts),
                    list(::PortablePersonIdentity),
                    list({ PortableVirtualAlbum(version) }),
                    list({ PortableMemory(version) }),
                    list(::PortableSmartAlbum),
                    list(::PortablePhotoStack),
                    list(::PortableMediaDecision),
                    list(::PortableMemoryDateRule),
                    list(::PortableMemoryPersonRule),
                    if (b()) PortableAutoArchiveReview() else null,
                    if (b()) s(PortableOrganizationSnapshot.MaximumPreferencesBytes) else null,
                    if (version >= 2)
                        list(::PortablePhotoRecipe, PortableOrganizationSnapshot.MaximumSources)
                    else emptyList(),
                )
            require(input.read() == -1) { "Trailing organization bytes" }
            snapshot.validate()
        }
    }

    private class Writer(val out: DataOutputStream) {
        fun i(v: Int) = out.writeInt(v)

        fun l(v: Long) = out.writeLong(v)

        fun f(v: Float) = out.writeFloat(v)

        fun b(v: Boolean) = out.writeBoolean(v)

        fun s(v: String, max: Int = 4096) {
            val bytes = v.toByteArray(Charsets.UTF_8)
            require(bytes.size <= max)
            i(bytes.size)
            out.write(bytes)
        }

        fun ns(v: String?) {
            b(v != null)
            v?.let { s(it) }
        }

        fun ni(v: Int?) {
            b(v != null)
            v?.let(::i)
        }

        fun nl(v: Long?) {
            b(v != null)
            v?.let(::l)
        }

        fun kind(v: PortableMediaKind) = i(v.ordinal)

        fun <T> list(v: List<T>, write: (T) -> Unit) {
            i(v.size)
            v.forEach(write)
        }

        fun PortableSourceFacts(v: PortableSourceFacts) {
            s(v.sourceId)
            s(v.sha256)
            l(v.sizeBytes)
            kind(v.kind)
            l(v.timelineSortMillis)
            nl(v.dateTakenMillis)
            b(v.isFavorite)
            ns(v.relativePath)
        }

        fun PortablePersonIdentity(v: PortablePersonIdentity) {
            s(v.identityId)
            ns(v.displayName)
            s(v.algorithmVersion)
        }

        fun PortableAlbumMember(v: PortableAlbumMember) {
            s(v.sourceId)
            l(v.addedAtMillis)
        }

        fun PortableVirtualAlbum(v: PortableVirtualAlbum, version: Int) {
            s(v.entityId)
            s(v.name)
            l(v.createdAtMillis)
            l(v.updatedAtMillis)
            list(v.members, ::PortableAlbumMember)
            if (version >= 4) ns(v.coverSourceId)
        }

        fun PortableMemoryMember(v: PortableMemoryMember) {
            s(v.sourceId)
            i(v.ordinal)
            s(v.origin)
            f(v.score)
        }

        fun PortableMemory(v: PortableMemory, version: Int) {
            s(v.entityId)
            s(v.origin)
            s(v.state)
            s(v.algorithmVersion)
            ns(v.title)
            s(v.titleMode)
            b(v.isUserEdited)
            l(v.startMillis)
            l(v.endMillis)
            l(v.createdAtMillis)
            l(v.updatedAtMillis)
            list(v.members, ::PortableMemoryMember)
            ns(v.coverSourceId)
            b(v.userSelectedCover)
            if (version >= 3) b(v.includeSpecialMedia)
        }

        fun PortableSmartExclusion(v: PortableSmartExclusion) {
            s(v.sourceId)
            l(v.excludedAtMillis)
        }

        fun PortableSmartAlbum(v: PortableSmartAlbum) {
            s(v.entityId)
            s(v.name)
            ns(v.topic)
            ns(v.personIdentityId)
            ni(v.year)
            s(v.zoneId)
            nl(v.fromMillis)
            nl(v.untilMillis)
            b(v.favoritesOnly)
            l(v.createdAtMillis)
            l(v.updatedAtMillis)
            list(v.exclusions, ::PortableSmartExclusion)
        }

        fun PortableStackMember(v: PortableStackMember) {
            s(v.sourceId)
            i(v.ordinal)
        }

        fun PortablePhotoStack(v: PortablePhotoStack) {
            s(v.entityId)
            ns(v.title)
            ns(v.coverSourceId)
            l(v.createdAtMillis)
            l(v.updatedAtMillis)
            list(v.members, ::PortableStackMember)
        }

        fun PortableMediaDecision(v: PortableMediaDecision) {
            s(v.sourceId)
            nl(v.archivedAtMillis)
            ns(v.documentCategory)
            nl(v.documentUpdatedAtMillis)
            nl(v.stackSeparatedAtMillis)
            nl(v.similarityExcludedAtMillis)
            b(v.autoArchiveHistoryPresent)
            nl(v.autoArchiveWrittenAtMillis)
        }

        fun PortableMemoryDateRule(v: PortableMemoryDateRule) {
            s(v.entityId)
            l(v.startDay)
            l(v.endDay)
            s(v.zoneId)
            l(v.fromMillis)
            l(v.untilMillis)
            l(v.createdAtMillis)
        }

        fun PortableMemoryPersonRule(v: PortableMemoryPersonRule) {
            s(v.entityId)
            s(v.personIdentityId)
            l(v.createdAtMillis)
            list(v.knownSourceIds, ::s)
        }

        fun PortablePhotoRecipe(v: PortablePhotoRecipe) {
            s(v.sourceId)
            i(v.revision)
            l(v.createdAtMillis)
            l(v.updatedAtMillis)
            list(v.operations) {
                s(it, com.librestatic.lightforge.core.model.PortablePhotoRecipe.MaximumOperationBytes)
            }
        }

        fun PortableAutoArchiveReview(v: PortableAutoArchiveReview) {
            b(v.enabled)
            s(v.category)
            i(v.minimumAgeDays)
        }
    }

    private class Reader(val input: DataInputStream) {
        private var items = 0

        fun i(): Int = input.readInt()

        fun l(): Long = input.readLong()

        fun f(): Float = input.readFloat()

        fun b(): Boolean =
            input.readUnsignedByte().let {
                require(it == 0 || it == 1)
                it == 1
            }

        fun s(max: Int = 4096): String {
            val n = i()
            require(n in 0..max && n <= input.available())
            val bytes = ByteArray(n)
            input.readFully(bytes)
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }

        fun ns(): String? = if (b()) s() else null

        fun ni(): Int? = if (b()) i() else null

        fun nl(): Long? = if (b()) l() else null

        fun kind(): PortableMediaKind =
            i().let {
                require(it in PortableMediaKind.entries.indices)
                PortableMediaKind.entries[it]
            }

        fun <T> list(read: () -> T, maximum: Int = 100_000): List<T> {
            val n = i()
            require(n in 0..maximum && n <= 120_000 - items)
            items += n
            return List(n) { read() }
        }

        fun PortableSourceFacts(): PortableSourceFacts =
            com.librestatic.lightforge.core.model.PortableSourceFacts(s(), s(), l(), kind(), l(), nl(), b(), ns())

        fun PortablePersonIdentity(): PortablePersonIdentity =
            com.librestatic.lightforge.core.model.PortablePersonIdentity(s(), ns(), s())

        fun PortableAlbumMember(): PortableAlbumMember =
            com.librestatic.lightforge.core.model.PortableAlbumMember(s(), l())

        fun PortableVirtualAlbum(version: Int): PortableVirtualAlbum =
            com.librestatic.lightforge.core.model.PortableVirtualAlbum(
                s(),
                s(),
                l(),
                l(),
                list(::PortableAlbumMember),
                if (version >= 4) ns() else null,
            )

        fun PortableMemoryMember(): PortableMemoryMember =
            com.librestatic.lightforge.core.model.PortableMemoryMember(s(), i(), s(), f())

        fun PortableMemory(version: Int): PortableMemory =
            com.librestatic.lightforge.core.model.PortableMemory(
                s(),
                s(),
                s(),
                s(),
                ns(),
                s(),
                b(),
                l(),
                l(),
                l(),
                l(),
                list(::PortableMemoryMember),
                ns(),
                b(),
                if (version >= 3) b() else false,
            )

        fun PortableSmartExclusion(): PortableSmartExclusion =
            com.librestatic.lightforge.core.model.PortableSmartExclusion(s(), l())

        fun PortableSmartAlbum(): PortableSmartAlbum =
            com.librestatic.lightforge.core.model.PortableSmartAlbum(
                s(),
                s(),
                ns(),
                ns(),
                ni(),
                s(),
                nl(),
                nl(),
                b(),
                l(),
                l(),
                list(::PortableSmartExclusion),
            )

        fun PortableStackMember(): PortableStackMember =
            com.librestatic.lightforge.core.model.PortableStackMember(s(), i())

        fun PortablePhotoStack(): PortablePhotoStack =
            com.librestatic.lightforge.core.model.PortablePhotoStack(
                s(),
                ns(),
                ns(),
                l(),
                l(),
                list(::PortableStackMember),
            )

        fun PortableMediaDecision(): PortableMediaDecision =
            com.librestatic.lightforge.core.model.PortableMediaDecision(
                s(),
                nl(),
                ns(),
                nl(),
                nl(),
                nl(),
                b(),
                nl(),
            )

        fun PortableMemoryDateRule(): PortableMemoryDateRule =
            com.librestatic.lightforge.core.model.PortableMemoryDateRule(s(), l(), l(), s(), l(), l(), l())

        fun PortableMemoryPersonRule(): PortableMemoryPersonRule =
            com.librestatic.lightforge.core.model.PortableMemoryPersonRule(s(), s(), l(), list(::s))

        fun PortablePhotoRecipe(): PortablePhotoRecipe =
            com.librestatic.lightforge.core.model.PortablePhotoRecipe(
                s(),
                i(),
                l(),
                l(),
                list(
                    { s(com.librestatic.lightforge.core.model.PortablePhotoRecipe.MaximumOperationBytes) },
                    com.librestatic.lightforge.core.model.PortablePhotoRecipe.MaximumOperations,
                ),
            )

        fun PortableAutoArchiveReview(): PortableAutoArchiveReview =
            com.librestatic.lightforge.core.model.PortableAutoArchiveReview(b(), s(), i())
    }
}
