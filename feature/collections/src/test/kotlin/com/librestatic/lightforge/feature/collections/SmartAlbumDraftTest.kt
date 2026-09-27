package com.librestatic.lightforge.feature.collections

import androidx.compose.runtime.saveable.SaverScope
import com.librestatic.lightforge.core.database.SmartAlbumEntity
import com.librestatic.lightforge.core.model.MediaKey
import org.junit.Assert.*
import org.junit.Test

class SmartAlbumDraftTest {
    @Test
    fun optionalYearAcceptsAllYearsAndValidCalendarRange() {
        for (year in listOf("", "1", "2026", "9998")) assertNotNull(
            SmartAlbumDraft(year = year).rule()
        )
        for (year in listOf("0", "9999", "x", "2.5", "-1", "  ")) assertNull(
            SmartAlbumDraft(year = year).rule()
        )
    }

    @Test
    fun blankNamesStayUnsavableAndWhitespaceNormalizesInRepository() {
        assertFalse(SmartAlbumDraft().validName)
        assertFalse(SmartAlbumDraft(name = "  ").validName)
        assertFalse(SmartAlbumDraft(name = "a".repeat(81)).validName)
        assertTrue(SmartAlbumDraft(name = " My album ").validName)
    }

    @Test
    fun pendingExclusionsDeduplicateWithoutMixingVolumes() {
        val a = MediaKey("primary", 1)
        val b = MediaKey("sd", 1)
        val draft = SmartAlbumDraft().exclude(a).exclude(a).exclude(b)
        assertEquals(listOf(a, b), draft.excluded)
        assertEquals(listOf(b), draft.include(a).excluded)
    }

    @Test
    fun pendingExclusionsAreBoundedAtTwoHundred() {
        var draft = SmartAlbumDraft()
        for (id in 1L..201L) draft = draft.exclude(MediaKey("v", id))
        assertEquals(200, draft.excluded.size)
        assertFalse(MediaKey("v", 201) in draft.excluded)
    }

    @Test
    fun editingKeepsTheSavedIdentityRevisionAndTimezone() {
        val row =
            SmartAlbumEntity(
                "id",
                "Name",
                "beach",
                "person",
                "v1",
                2026,
                "UTC",
                1L,
                2L,
                true,
                "revision",
                1,
                2,
            )
        val draft = SmartAlbumDraft.from(row)
        assertEquals("id", draft.albumId)
        assertEquals("revision", draft.revision)
        assertEquals("UTC", draft.zoneId)
        assertEquals("person", draft.rule()!!.personClusterId)
        assertTrue(draft.favorites)
    }

    @Test
    fun editsAreImmutableAndRetainPendingChoices() {
        val first = SmartAlbumDraft(name = "Original").exclude(MediaKey("v", 1))
        val second = first.copy(name = "Changed", year = "2026", favorites = true)
        assertEquals("Original", first.name)
        assertEquals(first.excluded, second.excluded)
        assertFalse(first.favorites)
    }

    @Test
    fun saveableDraftRoundTripRetainsCompositeKeysAndRevision() {
        val draft =
            SmartAlbumDraft(
                "Trip",
                "beach",
                "person",
                "v1",
                "2026",
                "UTC",
                true,
                listOf(MediaKey("volume:with:colon", 2)),
                "album",
                "revision",
            )
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }
        val saved = with(SmartAlbumDraft.Saver) { scope.save(draft) }
        assertEquals(draft, SmartAlbumDraft.Saver.restore(requireNotNull(saved)))
    }

    @Test
    fun emptyDraftRoundTripKeepsAnyPersonTopicAndYear() {
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }
        val draft = SmartAlbumDraft()
        val saved = with(SmartAlbumDraft.Saver) { scope.save(draft) }
        assertEquals(draft, SmartAlbumDraft.Saver.restore(requireNotNull(saved)))
    }
}
