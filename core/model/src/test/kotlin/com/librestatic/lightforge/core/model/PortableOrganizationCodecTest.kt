package com.librestatic.lightforge.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PortableOrganizationCodecTest {
    @Test fun selectedAlbumCoverV4RoundTripsAndOlderVersionsRemainExplicit() {
        val base = fixture()
        val album = base.albums.single()
        val cover = album.members.first().sourceId
        val selected = base.copy(schemaVersion = 4, albums = listOf(album.copy(coverSourceId = cover)))
        assertEquals(selected, PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(selected)))
        for (version in 1..3) {
            val historical = base.copy(schemaVersion = version)
            assertEquals(historical, PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(historical)))
            assertTrue(runCatching { selected.copy(schemaVersion = version).validate() }.isFailure)
        }
        assertTrue(runCatching { selected.copy(albums = listOf(album.copy(coverSourceId = "missing"))).validate() }.isFailure)
        assertTrue(runCatching { selected.copy(albums = listOf(album.copy(members = album.members.drop(1), coverSourceId = cover))).validate() }.isFailure)
    }

    private fun id() = UUID.randomUUID().toString()

    private fun fixture(): PortableOrganizationSnapshot {
        val a = id()
        val b = id()
        val person = id()
        return PortableOrganizationSnapshot(
            snapshotId = id(),
            originNamespace = id(),
            createdAtMillis = 5,
            scope = PortableOrganizationScope(2, 2, 0),
            sources =
                listOf(a, b).map {
                    PortableSourceFacts(
                        it,
                        "a".repeat(64),
                        10,
                        PortableMediaKind.Image,
                        100,
                        99,
                        true,
                        "Pictures/été/",
                    )
                },
            people = listOf(PortablePersonIdentity(person, "Personne ☀", "v1")),
            albums =
                listOf(
                    PortableVirtualAlbum(
                        "album",
                        "Family",
                        1,
                        2,
                        listOf(PortableAlbumMember(a, 1), PortableAlbumMember(b, 2)),
                    )
                ),
            memories =
                listOf(
                    PortableMemory(
                        "moment",
                        "USER",
                        "SAVED",
                        "v1",
                        "Keep me",
                        "USER",
                        true,
                        1,
                        2,
                        3,
                        4,
                        listOf(
                            PortableMemoryMember(a, 2, "USER", 1f),
                            PortableMemoryMember(b, 8, "USER", .9f),
                        ),
                        b,
                        true,
                    )
                ),
            smartAlbums =
                listOf(
                    PortableSmartAlbum(
                        "smart",
                        "Local people",
                        "nature",
                        person,
                        2026,
                        "UTC",
                        LocalDate.of(2026, 1, 1).toEpochDay() * 86_400_000L,
                        LocalDate.of(2027, 1, 1).toEpochDay() * 86_400_000L,
                        true,
                        1,
                        2,
                        listOf(PortableSmartExclusion(a, 3)),
                    )
                ),
            stacks =
                listOf(
                    PortablePhotoStack(
                        "stack",
                        "Order",
                        b,
                        1,
                        2,
                        listOf(PortableStackMember(a, 0), PortableStackMember(b, 1)),
                    )
                ),
            decisions = listOf(PortableMediaDecision(a, 1, "Excluded", 2, 3, 4, true, null)),
            memoryDates =
                listOf(
                    PortableMemoryDateRule(
                        "date",
                        20000,
                        20001,
                        "UTC",
                        20000L * 86_400_000L,
                        20002L * 86_400_000L,
                        1,
                    )
                ),
            memoryPeople = listOf(PortableMemoryPersonRule("rule", person, 1, listOf(a))),
            autoArchiveReview = PortableAutoArchiveReview(true, "Receipt", 30),
            preferencesJsonForReview = "{\"schemaVersion\":1}",
        )
    }

    @Test
    fun everyTypedFieldRoundTripsWithoutConflatingEqualDigests() {
        val input = fixture()
        val output = PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(input))
        assertEquals(input, output)
        assertEquals(2, output.sources.size)
        assertEquals(output.sources[0].sha256, output.sources[1].sha256)
        assertNotEquals(output.sources[0].sourceId, output.sources[1].sourceId)
        assertEquals(listOf(2, 8), output.memories.single().members.map { it.ordinal })
        assertNull(output.decisions.single().autoArchiveWrittenAtMillis)
        assertTrue(output.decisions.single().autoArchiveHistoryPresent)
    }

    @Test
    fun specialMediaPermissionRoundTripsIndependentlyOfMemoryOrigin() {
        val base = fixture()
        val memories = listOf("AUTO", "MANUAL").flatMap { origin ->
            listOf(false, true).map { allowed ->
                base.memories.single().copy(entityId = "$origin-$allowed", origin = origin,
                    includeSpecialMedia = allowed)
            }
        }
        val input = base.copy(schemaVersion = 3, memories = memories)
        assertEquals(input, PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(input)))
        for (version in 1..2) {
            assertThrows(IllegalArgumentException::class.java) {
                PortableOrganizationCodec.encode(input.copy(schemaVersion = version))
            }
        }
    }

    @Test
    fun legacyVersionOneAndTwoBytesNeverInferSpecialMediaPermissionFromOrigin() {
        for (version in 1..2) for (origin in listOf("AUTO", "MANUAL")) {
            // Frozen original field order: this writer deliberately has no new permission byte.
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                fun text(value: String) {
                    val utf8 = value.toByteArray(Charsets.UTF_8)
                    out.writeInt(utf8.size); out.write(utf8)
                }
                out.write(byteArrayOf(85, 71, 79, 82, 71, 1, 13, 10))
                out.writeInt(version)
                text("00000000-0000-0000-0000-000000000001")
                text("00000000-0000-0000-0000-000000000002")
                out.writeLong(1) // Creation timestamp.
                out.writeInt(0); out.writeLong(0); out.writeLong(0) // Scope.
                repeat(3) { out.writeInt(0) } // Sources, people, albums.
                out.writeInt(1) // One memory.
                text("legacy"); text(origin); text("SAVED"); text("v1")
                out.writeBoolean(false) // Nullable title.
                text("AUTO"); out.writeBoolean(false) // Title mode and edited flag.
                repeat(4) { out.writeLong(1) } // Start/end/created/updated.
                out.writeInt(0); out.writeBoolean(false); out.writeBoolean(false) // Members/cover/user cover.
                repeat(5) { out.writeInt(0) } // Smart, stacks, decisions, date and person rules.
                out.writeBoolean(false); out.writeBoolean(false) // Automation and preferences.
                if (version >= 2) out.writeInt(0) // Recipes.
            }
            val decoded = PortableOrganizationCodec.decode(bytes.toByteArray())
            assertEquals(version, decoded.schemaVersion)
            assertEquals(origin, decoded.memories.single().origin)
            assertFalse(decoded.memories.single().includeSpecialMedia)
            assertArrayEquals(bytes.toByteArray(), PortableOrganizationCodec.encode(decoded))
        }
    }

    @Test
    fun unknownVersionTrailingAndTruncatedBytesAreRejected() {
        val bytes = PortableOrganizationCodec.encode(fixture())
        assertThrows(IllegalArgumentException::class.java) {
            PortableOrganizationCodec.decode(bytes + byteArrayOf(0))
        }
        val bad = bytes.copyOf()
        bad[11] = 4
        assertThrows(IllegalArgumentException::class.java) { PortableOrganizationCodec.decode(bad) }
        assertThrows(Exception::class.java) {
            PortableOrganizationCodec.decode(bytes.copyOf(bytes.size - 1))
        }
    }

    @Test
    fun sourceReferencesMustResolveAndDuplicateIdentityIsRejected() {
        val f = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(sources = listOf(f.sources.first(), f.sources.first())).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(
                    albums =
                        listOf(
                            f.albums.single().copy(members = listOf(PortableAlbumMember(id(), 0)))
                        )
                )
                .validate()
        }
    }

    @Test
    fun lengthsAndMalformedUtf8AreBoundedBeforeAllocation() {
        val f = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(
                    preferencesJsonForReview =
                        "x".repeat(PortableOrganizationSnapshot.MaximumPreferencesBytes + 1)
                )
                .validate()
        }
        val bytes = PortableOrganizationCodec.encode(f)
        // First string starts after magic + version + 4-byte byte-count.
        bytes[16] = 0xff.toByte()
        assertThrows(Exception::class.java) { PortableOrganizationCodec.decode(bytes) }
        assertThrows(IllegalArgumentException::class.java) {
            PortableOrganizationCodec.decode(
                ByteArray(PortableOrganizationSnapshot.MaximumBytes + 1)
            )
        }
    }

    @Test
    fun scopeIsExplicitAndCivilInstantsAreNotRecomputed() {
        val f = fixture().copy(scope = PortableOrganizationScope(2, 4, 7))
        val restored = PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(f))
        assertTrue(restored.scope.partial)
        assertEquals(7L, restored.scope.omittedReferences)
        assertEquals(f.memoryDates.single().fromMillis, restored.memoryDates.single().fromMillis)
        assertEquals(f.smartAlbums.single().untilMillis, restored.smartAlbums.single().untilMillis)
        assertEquals(2, restored.totals().reviewOnlyItems)
    }

    @Test
    fun unsupportedRulesAndNonfiniteScoresAreRejected() {
        val f = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(decisions = listOf(f.decisions.single().copy(documentCategory = "SQL")))
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(
                    memories =
                        listOf(
                            f.memories
                                .single()
                                .copy(
                                    members =
                                        listOf(
                                            PortableMemoryMember(
                                                f.sources.first().sourceId,
                                                0,
                                                "USER",
                                                Float.NaN,
                                            )
                                        )
                                )
                        )
                )
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(smartAlbums = listOf(f.smartAlbums.single().copy(year = null))).validate()
        }
    }

    @Test
    fun incoherentCivilLabelsCannotActivateUnrelatedGlobalInstants() {
        val f = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(
                    memoryDates =
                        listOf(f.memoryDates.single().copy(fromMillis = 100, untilMillis = 200))
                )
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(
                    smartAlbums =
                        listOf(f.smartAlbums.single().copy(fromMillis = 100, untilMillis = 200))
                )
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(
                    memoryDates =
                        listOf(
                            f.memoryDates
                                .single()
                                .copy(fromMillis = Long.MIN_VALUE, untilMillis = Long.MAX_VALUE)
                        )
                )
                .validate()
        }
    }

    @Test
    fun frozenPlausibleOffsetsArePreservedRatherThanRecomputedFromCurrentTimezoneRules() {
        val f = fixture()
        val date =
            f.memoryDates.single().copy(fromMillis = f.memoryDates.single().fromMillis + 1_800_000L)
        val smart =
            f.smartAlbums
                .single()
                .copy(
                    fromMillis = f.smartAlbums.single().fromMillis!! - 1_800_000L,
                    untilMillis = f.smartAlbums.single().untilMillis!! - 1_800_000L,
                )
        val frozen = f.copy(memoryDates = listOf(date), smartAlbums = listOf(smart))
        assertEquals(
            frozen,
            PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(frozen)),
        )
    }

    @Test
    fun daylightSavingShortAndLongDaysKeepTheirActualStoredDuration() {
        val f = fixture()
        for ((dayText, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
            val day = LocalDate.parse(dayText)
            val zone = ZoneId.of("America/New_York")
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val input =
                f.copy(
                    memoryDates =
                        listOf(
                            PortableMemoryDateRule(
                                "dst",
                                day.toEpochDay(),
                                day.toEpochDay(),
                                zone.id,
                                from,
                                until,
                                1,
                            )
                        )
                )
            val output = PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(input))
            assertEquals(
                hours * 3_600_000L,
                output.memoryDates.single().untilMillis - output.memoryDates.single().fromMillis,
            )
        }
    }

    @Test
    fun endpointPlausibilityUsesInclusiveEndPlusOneAndExactOffsetBounds() {
        val f = fixture()
        val rule = f.memoryDates.single()
        val maximum = 18 * 3_600_000L
        f.copy(
                memoryDates =
                    listOf(
                        rule.copy(
                            fromMillis = rule.fromMillis + maximum,
                            untilMillis = rule.untilMillis - maximum,
                        )
                    )
            )
            .validate()
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(memoryDates = listOf(rule.copy(fromMillis = rule.fromMillis - maximum - 1)))
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(memoryDates = listOf(rule.copy(untilMillis = rule.untilMillis + maximum + 1)))
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.copy(memoryDates = listOf(rule.copy(untilMillis = rule.untilMillis - 86_400_000L)))
                .validate()
        }
    }
}
