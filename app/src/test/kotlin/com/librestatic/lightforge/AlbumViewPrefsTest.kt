package com.librestatic.lightforge

import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.model.AlbumKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumViewPrefsTest {
    @Test
    fun everyCombinationRoundTrips() {
        for (filter in AlbumMediaFilter.entries) for (sort in AlbumSort.entries) {
            assertEquals(filter to sort, AlbumViewPrefs.decode(AlbumViewPrefs.encode(filter, sort)))
        }
    }

    @Test
    fun garbageAndAbsenceReadAsNothingStored() {
        assertNull(AlbumViewPrefs.decode(null))
        assertNull(AlbumViewPrefs.decode("All"))
        assertNull(AlbumViewPrefs.decode("All|Sideways"))
        assertNull(AlbumViewPrefs.decode("Docs|NewestFirst"))
    }

    @Test
    fun albumsKeepSeparateKeysThatNoFolderIdentityCanTake() {
        val a = AlbumViewPrefs.keyOf(AlbumKey.Physical("external_primary", 7))
        val b = AlbumViewPrefs.keyOf(AlbumKey.Physical("external_primary", 8))
        val c = AlbumViewPrefs.keyOf(AlbumKey.Virtual(7))
        assertEquals(3, setOf(a, b, c).size)
        // A folder identity is `volume|path/`; an album key starts with the word album, which is no volume name.
        assertNotEquals("default", a)
        assertEquals("album", a.substringBefore('|'))
    }
}
