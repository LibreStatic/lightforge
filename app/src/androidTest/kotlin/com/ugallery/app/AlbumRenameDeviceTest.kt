package com.ugallery.app

import com.ugallery.feature.album.RenameAlbumDialog
import com.ugallery.core.data.GalleryAlbumRepository
import com.ugallery.core.designsystem.UGalleryTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated Room file only: no MediaStore/real app database access. */
@RunWith(AndroidJUnit4::class)
class AlbumRenameDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "album-rename-" + UUID.randomUUID() + ".db"
    private lateinit var database: GalleryDatabase
    private var clock = 1_000L
    private fun repository() = GalleryAlbumRepository(database) { clock }
    private fun open() = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()

    @Before fun setup() {
        check(!context.getDatabasePath(name).exists())
        database = open()
    }
    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        check(context.deleteDatabase(name)) { "Owned database cleanup failed: $name" }
        check(!context.getDatabasePath(name).exists())
    }

    @Test fun renameNormalizesAndSurvivesReopenWithoutChangingIdentityMembersOrMedia() = runBlocking {
        val repository = repository()
        database.libraryDao().upsertMedia(listOf(media("external_primary", 7, 7_000), media("test-volume", 8, 8_000)))
        val id = repository.createVirtualAlbum("Original")
        val other = repository.createVirtualAlbum("Unchanged")
        repository.addToVirtualAlbum(id, listOf(MediaKey("external_primary", 7), MediaKey("test-volume", 8)))
        val albumBefore = requireNotNull(database.libraryDao().virtualAlbum(id))
        val otherBefore = database.libraryDao().virtualAlbum(other)
        val mediaBefore = rows("media_items")
        val membersBefore = rows("virtual_album_media")
        clock = 2_000L
        var draft by mutableStateOf("Original")
        var visible by mutableStateOf(true)
        var submitted: String? = null
        compose.setContent {
            UGalleryTheme(darkTheme = false, dynamicColor = false) {
                if (visible) RenameAlbumDialog(name = draft, onNameChange = { draft = it }, working = false,
                    saveFailed = false, onConfirm = { submitted = it }, onDismiss = { visible = false })
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("  Viaje   Familiar 2026  ")
        compose.onNodeWithTag("album-rename-save").performTouchInput { click() }
        compose.runOnIdle { assertEquals("Viaje Familiar 2026", submitted) }
        assertTrue(repository.renameVirtualAlbum(id, requireNotNull(submitted)))
        compose.runOnIdle { visible = false }
        val expected = albumBefore.copy(name = "Viaje Familiar 2026", normalizedName = "viaje familiar 2026", updatedAtMillis = clock)
        assertEquals(expected, database.libraryDao().virtualAlbum(id))
        database.close()
        database = open()
        assertEquals(expected, database.libraryDao().virtualAlbum(id))
        assertEquals(otherBefore, database.libraryDao().virtualAlbum(other))
        assertEquals(mediaBefore, rows("media_items"))
        assertEquals(membersBefore, rows("virtual_album_media"))
        println("ALBUM_RENAME persisted normalized name; same ID/creation/other album/media/reference rows; owned DB=$name")
    }

    @Test fun invalidNamesAndIdsAndMissingAlbumLeaveExistingRowsUnchanged() = runBlocking {
        val repository = repository()
        val id = repository.createVirtualAlbum("Original")
        val before = rows("virtual_albums")
        for ((target, value) in listOf(id to "", id to " \n\t ", id to "x".repeat(201), 0L to "Valid", -1L to "Valid")) {
            val failure = runCatching { repository.renameVirtualAlbum(target, value) }.exceptionOrNull()
            assertTrue("Expected invalid input rejection: $target / ${value.length}", failure is IllegalArgumentException)
            assertEquals(before, rows("virtual_albums"))
        }
        assertFalse(repository.renameVirtualAlbum(Long.MAX_VALUE, "Missing"))
        assertEquals(before, rows("virtual_albums"))
        assertTrue(repository.renameVirtualAlbum(id, "x".repeat(200)))
        assertEquals("x".repeat(200), database.libraryDao().virtualAlbum(id)?.name)
        println("ALBUM_RENAME blank/201 chars/nonpositive ID rejected; missing ID=false; 200 chars accepted; owned DB=$name")
    }


    @Test fun dialogCancelValidationAndFailedSaveRetryKeepDraftExplicit() {
        var draft by mutableStateOf("Original")
        var visible by mutableStateOf(true)
        var failed by mutableStateOf(false)
        var submitted: String? = null
        var dismissCount = 0
        compose.setContent {
            UGalleryTheme(darkTheme = false, dynamicColor = false) {
                if (visible) RenameAlbumDialog(name = draft, onNameChange = { draft = it }, working = false,
                    saveFailed = failed, onConfirm = { submitted = it }, onDismiss = { visible = false; dismissCount++ })
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("  ")
        compose.onNodeWithTag("album-rename-save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(201))
        compose.onNodeWithTag("album-rename-save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Retry name")
        compose.onNodeWithTag("album-rename-save").performTouchInput { click() }
        compose.runOnIdle { assertEquals("Retry name", submitted) }
        runBlocking { assertFalse(repository().renameVirtualAlbum(Long.MAX_VALUE, requireNotNull(submitted))) }
        compose.runOnIdle { failed = true; submitted = null }
        compose.onNodeWithTag("album-rename-error").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Retry name")
        compose.onNodeWithTag("album-rename-save").performTouchInput { click() }
        compose.runOnIdle { assertEquals("Retry name", submitted) }
        compose.onNodeWithTag("album-rename-cancel").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, dismissCount); assertFalse(visible) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertTrue(rows("virtual_albums").isEmpty())
        assertTrue(rows("media_items").isEmpty())
        println("ALBUM_RENAME_DIALOG invalid save disabled; missing album save keeps draft/error/retry; Cancel dismisses without writes")
    }

    private fun rows(table: String): List<List<String?>> {
        check(table in setOf("media_items", "virtual_album_media", "virtual_albums"))
        return database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
    private fun media(
        volume: String,
        id: Long,
        sort: Long,
        mediaType: Int = 1,
        relativePath: String = "DCIM/Test/",
    ) = MediaItemEntity(
        volumeName = volume,
        mediaStoreId = id,
        mediaType = mediaType,
        mimeType = if (mediaType == 3) "video/mp4" else "image/jpeg",
        displayName = "$id",
        sizeBytes = 100,
        width = 10,
        height = 10,
        durationMillis = if (mediaType == 3) 1_000 else 0,
        orientationDegrees = 0,
        dateTakenMillis = sort,
        dateAddedSeconds = sort / 1_000,
        dateModifiedSeconds = sort / 1_000,
        timelineSortMillis = sort,
        generationAdded = 1,
        generationModified = 1,
        bucketId = 99L,
        bucketDisplayName = "Test",
        relativePath = relativePath,
        isFavorite = false,
        isTrashed = false,
        isAccessible = true,
        lastSeenScanId = 1,
    )

}
