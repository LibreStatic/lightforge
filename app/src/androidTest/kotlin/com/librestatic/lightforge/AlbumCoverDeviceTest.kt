package com.librestatic.lightforge

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.VirtualAlbumRow
import com.librestatic.lightforge.core.data.GalleryAlbumRepository
import com.librestatic.lightforge.core.data.PortableOrganizationRepository
import com.librestatic.lightforge.core.data.PortableSourceBinding
import com.librestatic.lightforge.core.data.PortableRestoredSource
import com.librestatic.lightforge.core.data.PortableImportOptions
import com.librestatic.lightforge.core.model.PortableOrganizationCodec
import java.security.MessageDigest
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Owned small disk database only; never modifies MediaStore or an application database. */
@RunWith(AndroidJUnit4::class)
class AlbumCoverDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "album-cover-" + UUID.randomUUID() + ".db"
    private lateinit var database: GalleryDatabase
    private fun open() = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
    private fun repository() = GalleryAlbumRepository(database) { 2_000L }
    @Before fun setup() { check(!context.getDatabasePath(name).exists()); database = open() }
    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        check(context.deleteDatabase(name))
        check(!context.getDatabasePath(name).exists())
    }

    @Test fun chosenOlderCoverPersistsAndUnavailableRemovedSourcesFallbackWithoutLosingPreference() = runBlocking {
        val older = media("external_primary", 1, 1_000)
        val newer = media("secondary", 2, 2_000)
        database.libraryDao().upsertMedia(listOf(older, newer))
        val id = repository().createVirtualAlbum("Cover fixture")
        repository().addToVirtualAlbum(id, listOf(key(older), key(newer)))
        assertEquals(key(newer), cover(id))
        val mediaBefore = rows("media_items")
        val membersBefore = rows("virtual_album_media")
        val albumBefore = requireNotNull(database.libraryDao().virtualAlbum(id))
        assertTrue(repository().setVirtualAlbumCover(id, key(older)))
        assertEquals(key(older), cover(id))
        database.close(); database = open()
        val chosen = requireNotNull(database.libraryDao().virtualAlbum(id))
        assertEquals(albumBefore.albumId, chosen.albumId)
        assertEquals(albumBefore.name, chosen.name)
        assertEquals(albumBefore.createdAtMillis, chosen.createdAtMillis)
        assertEquals(key(older), preference(id))
        assertEquals(key(older), cover(id))
        assertEquals(mediaBefore, rows("media_items"))
        assertEquals(membersBefore, rows("virtual_album_media"))
        database.libraryDao().upsertMedia(listOf(older.copy(isAccessible = false)))
        assertEquals(key(newer), cover(id)); assertEquals(key(older), preference(id))
        database.libraryDao().upsertMedia(listOf(older))
        assertEquals(key(older), cover(id))
        database.libraryDao().upsertMedia(listOf(older.copy(isTrashed = true)))
        assertEquals(key(newer), cover(id)); assertEquals(key(older), preference(id))
        database.libraryDao().upsertMedia(listOf(older))
        assertEquals(key(older), cover(id))
        assertTrue(repository().removeFromVirtualAlbum(id, key(older)))
        assertEquals(key(newer), cover(id)); assertEquals(key(older), preference(id))
        repository().addToVirtualAlbum(id, listOf(key(older)))
        assertEquals(key(older), cover(id))
        assertTrue(repository().setVirtualAlbumCover(id, null))
        assertNull(preference(id)); assertEquals(key(newer), cover(id))
        database.close(); database = open()
        assertNull(preference(id)); assertEquals(key(newer), cover(id))
        assertEquals(mediaBefore, rows("media_items"))
        println("ALBUM_COVER older chosen/reopened; inaccessible/trash/removal fallback; restored preference; reset/reopen automatic; media unchanged")
    }

    @Test fun nonmemberAndMissingAlbumRejectWithoutMutatingAnyRows() = runBlocking {
        val member = media("external_primary", 1, 1_000)
        val outsider = media("external_primary", 2, 2_000)
        database.libraryDao().upsertMedia(listOf(member, outsider))
        val id = repository().createVirtualAlbum("Cover fixture")
        repository().addToVirtualAlbum(id, listOf(key(member)))
        assertTrue(repository().setVirtualAlbumCover(id, key(member)))
        val before = snapshot()
        assertFalse(repository().setVirtualAlbumCover(id, key(outsider)))
        assertEquals(before, snapshot())
        assertFalse(repository().setVirtualAlbumCover(id, MediaKey("absent", 99)))
        assertEquals(before, snapshot())
        assertFalse(repository().setVirtualAlbumCover(Long.MAX_VALUE, key(member)))
        assertEquals(before, snapshot())
        assertFalse(repository().setVirtualAlbumCover(Long.MAX_VALUE, null))
        assertEquals(before, snapshot())
        assertEquals(key(member), cover(id))
        println("ALBUM_COVER nonmember/unknown media/missing album reject; albums/media/membership byte-value snapshots unchanged")
    }

    @Test fun portableCodecAndImportRemapChosenSourceToNewMediaKeyAndPersistCover() = runBlocking {
        val older = media("origin-volume", 1, 1_000)
        val newer = media("origin-volume", 2, 2_000)
        database.libraryDao().upsertMedia(listOf(older, newer))
        val id = repository().createVirtualAlbum("Portable cover")
        repository().addToVirtualAlbum(id, listOf(key(older), key(newer)))
        assertTrue(repository().setVirtualAlbumCover(id, key(older)))
        // Tiny in-memory bytes attest the repository boundary, not a MediaStore copy operation.
        val bindings = listOf(older, newer).mapIndexed { index, row ->
            val bytes = ByteArray(100) { (index + 1).toByte() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            PortableSourceBinding(UUID.randomUUID().toString(), key(row), 1, hash, bytes.size.toLong())
        }
        val portable = PortableOrganizationRepository(database).export(bindings, "{\"schemaVersion\":1}")
        assertEquals(4, portable.schemaVersion)
        val exportedAlbum = portable.albums.single()
        assertEquals(bindings.first().sourceId, exportedAlbum.coverSourceId)
        val decoded = PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(portable))
        assertEquals(exportedAlbum.coverSourceId, decoded.albums.single().coverSourceId)
        val namespace = UUID.randomUUID()
        val targets = listOf(older.copy(volumeName = "restored-volume", mediaStoreId = 101),
            newer.copy(volumeName = "restored-volume", mediaStoreId = 102))
        database.libraryDao().upsertMedia(targets)
        val beforeMedia = rows("media_items")
        val sourceAlbum = database.libraryDao().virtualAlbum(id)
        val mapping = bindings.mapIndexed { index, binding ->
            PortableRestoredSource(binding.sourceId, key(targets[index]), 1, binding.sha256,
                binding.sizeBytes, namespace.toString())
        }
        val result = PortableOrganizationRepository(database).importSnapshot(decoded, mapping,
            PortableImportOptions(allowPartial = true,
                virtualAlbumNameOverrides = mapOf(exportedAlbum.entityId to "Restored portable cover")), namespace)
        val restoredId = result.createdIds.getValue("album:${exportedAlbum.entityId}").toLong()
        assertNotEquals(id, restoredId)
        assertEquals(key(targets.first()), preference(restoredId))
        assertEquals(key(targets.first()), cover(restoredId))
        assertEquals(2L, database.libraryDao().virtualAlbumMediaCount(restoredId))
        assertEquals(sourceAlbum, database.libraryDao().virtualAlbum(id))
        assertEquals(beforeMedia, rows("media_items"))
        database.close(); database = open()
        assertEquals(key(targets.first()), preference(restoredId))
        assertEquals(key(targets.first()), cover(restoredId))
        assertEquals(key(older), preference(id))
        println("ALBUM_COVER portable schema4 actual codec/export/import/reopen; chosen sourceId rebound to restored-volume/101; media unchanged; repository-boundary attestations only")
    }

    private suspend fun preference(id: Long): MediaKey? {
        val album = requireNotNull(database.libraryDao().virtualAlbum(id))
        val volume = album.chosenCoverVolumeName
        val mediaId = album.chosenCoverMediaStoreId
        check((volume == null) == (mediaId == null))
        return if (volume == null) null else MediaKey(volume, requireNotNull(mediaId))
    }
    private suspend fun cover(id: Long): MediaKey? {
        val source = database.libraryDao().virtualAlbums()
        try {
            val result = source.load(PagingSource.LoadParams.Refresh<Int>(null, 20, false))
            val page = result as? PagingSource.LoadResult.Page<Int, VirtualAlbumRow> ?: error("Album page: $result")
            val row = page.data.single { it.albumId == id }
            check((row.coverVolumeName == null) == (row.coverMediaStoreId == null))
            return row.coverVolumeName?.let { MediaKey(it, requireNotNull(row.coverMediaStoreId)) }
        } finally { source.invalidate() }
    }
    private fun snapshot() = listOf("virtual_albums", "media_items", "virtual_album_media").associateWith(::rows)
    private fun rows(table: String): List<List<String?>> {
        check(table in setOf("virtual_albums", "media_items", "virtual_album_media"))
        return database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
    private fun key(row: MediaItemEntity) = MediaKey(row.volumeName, row.mediaStoreId)
    private fun media(volume: String, id: Long, date: Long) = MediaItemEntity(
        volumeName = volume, mediaStoreId = id, mediaType = 1, mimeType = "image/jpeg", displayName = "$id.jpg",
        sizeBytes = 100, width = 10, height = 10, durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = date, dateAddedSeconds = date / 1_000, dateModifiedSeconds = date / 1_000,
        timelineSortMillis = date, generationAdded = 1, generationModified = 1, bucketId = 10,
        bucketDisplayName = "Fixture", relativePath = "Pictures/Fixture/", isFavorite = false,
        isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )
}
