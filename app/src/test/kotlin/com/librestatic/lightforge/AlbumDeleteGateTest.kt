package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.AlbumKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumDeleteGateTest {
    @Test
    fun `virtual album exposes its row id`() {
        assertEquals(7L, deletableVirtualAlbumId(AlbumKey.Virtual(7L)))
    }

    @Test
    fun `device folder is never deletable`() {
        assertNull(deletableVirtualAlbumId(AlbumKey.Physical("external", 42L)))
    }

    @Test
    fun `missing album is never deletable`() {
        assertNull(deletableVirtualAlbumId(null))
    }

    @Test
    fun `non positive virtual id never reaches the repository require`() {
        assertNull(deletableVirtualAlbumId(AlbumKey.Virtual(0L)))
        assertNull(deletableVirtualAlbumId(AlbumKey.Virtual(-1L)))
    }
}
