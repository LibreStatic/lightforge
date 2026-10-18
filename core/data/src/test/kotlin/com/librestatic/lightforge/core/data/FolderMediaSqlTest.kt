package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.AlbumKeyset
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMediaSqlTest {
    private fun page(subfolders: Boolean, sort: AlbumSort = AlbumSort.NewestFirst, key: AlbumKeyset? = null) =
        FolderMediaSql.page("external_primary", "DCIM/", subfolders, AlbumMediaFilter.All, sort, key, 51)

    @Test fun exactFolderMatchesThePathAndSkipsHiddenTrashedAndArchivedRows() {
        val sql = page(subfolders = false).sql
        assertTrue(sql, "m.relativePath=?" in sql)
        assertTrue(sql, "instr(" !in sql)
        assertTrue(sql, "m.isAccessible=1" in sql && "m.isTrashed=0" in sql && "FROM archived_media" in sql)
    }

    @Test fun subfolderListingUsesThePrefixTest() {
        val sql = page(subfolders = true).sql
        assertTrue(sql, "instr(COALESCE(m.relativePath,''),?)=1" in sql)
        assertTrue(sql, "m.relativePath=?" !in sql)
    }

    @Test fun datePagingUsesTheAlbumOrderAndAKeysetOnTheTriple() {
        val sql = page(subfolders = false, key = AlbumKeyset(5, 7, "external_primary", null, 0)).sql
        assertTrue(sql, sql.endsWith("ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT ?"))
        assertTrue(sql, "m.timelineSortMillis < ?" in sql)
        // volume, path, three key columns twice plus the id/volume tie breakers, and the limit
        assertEquals(2 + 6 + 1, page(false, key = AlbumKeyset(5, 7, "v", null, 0)).argCount)
    }

    @Test fun nameAndSizeSortsMirrorTheAlbumQueries() {
        assertTrue(page(false, AlbumSort.NameAscending).sql.contains("ORDER BY LOWER(COALESCE(m.displayName,'')) ASC"))
        assertTrue(page(false, AlbumSort.SizeDescending).sql.contains("ORDER BY m.sizeBytes DESC"))
    }

    @Test fun countUsesTheSamePredicateAsThePage() {
        val count = FolderMediaSql.count("external_primary", "DCIM/", includeSubfolders = false)
        assertTrue(count.sql.startsWith("SELECT COUNT(*) FROM media_items m WHERE "))
        assertTrue(count.sql, "m.relativePath=?" in count.sql && "FROM archived_media" in count.sql)
        assertEquals(2, count.argCount)
    }
}
