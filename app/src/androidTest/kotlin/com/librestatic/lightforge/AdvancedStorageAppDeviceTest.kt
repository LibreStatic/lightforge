package com.librestatic.lightforge

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.data.IncrementalMediaSynchronizer
import com.librestatic.lightforge.core.data.InitialMediaScanner
import com.librestatic.lightforge.core.data.RoomMediaIndexStore
import com.librestatic.lightforge.core.database.ArchivedMediaEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.VirtualAlbumEntity
import com.librestatic.lightforge.core.database.VirtualAlbumMediaEntity
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.MediaStoreGenerationProbe
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.storageadvanced.FileAdvancedStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADR-046 end to end on a device: an item moved in place through [AdvancedFileOperations] keeps its
 * identity, so the favourite, the virtual album and the archive entry stay attached, and hiding the
 * folder afterwards marks the row inaccessible instead of deleting it.
 *
 * Needs All files access for the test package: `adb shell appops set <package> MANAGE_EXTERNAL_STORAGE allow`.
 */
@RunWith(AndroidJUnit4::class)
class AdvancedStorageAppDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val storage = FileAdvancedStorage(context)
    private val pictures = File(Environment.getExternalStorageDirectory(), "Pictures")
    private val source = File(pictures, "LfAppProbeSource")
    private val target = File(pictures, "LfAppProbeTarget")
    private lateinit var database: GalleryDatabase

    @Before fun setUp() {
        assumeTrue("All files access is not granted to ${context.packageName}", storage.isGranted())
        cleanup()
        source.mkdirs()
        target.mkdirs()
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        runBlocking { storage.unhideAllRecorded() }
        cleanup()
    }

    private fun cleanup() {
        listOf(source, target).forEach { it.deleteRecursively() }
        runCatching {
            resolver.delete(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                arrayOf("${pictures.path}/LfAppProbe%"),
            )
        }
    }

    @Test fun moveInPlaceKeepsIdentityAndSavedStateThenHidingKeepsTheRow() = runBlocking {
        val photo = File(source, "probe.png")
        photo.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        scan(photo.path)

        val dao = database.libraryDao()
        val reader = MediaStoreReader(resolver)
        val store = RoomMediaIndexStore(dao)
        val access = { LibraryAccess(GrantLevel.Full, GrantLevel.Full, false) }
        val probe = MediaStoreGenerationProbe(context)
        val volume = probe.snapshot().first { it.volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY }
        InitialMediaScanner(reader, store, access, pageSize = 256).scan(volume)
        val synchronizer = IncrementalMediaSynchronizer(reader, store, access)

        val id = resolver.query(
            MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA}=?", arrayOf(photo.path), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        assertNotNull("the probe photo is in MediaStore", id)
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, id!!)
        assertEquals("Pictures/LfAppProbeSource/", dao.media(key.volumeName, key.mediaStoreId)?.relativePath)

        // Saved decisions attached to the item.
        resolver.update(
            android.content.ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id),
            ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, 1) }, null, null,
        )
        synchronizer.applyRowHint(key)
        val albumId = dao.insertVirtualAlbum(VirtualAlbumEntity(name = "Probe", normalizedName = "probe", createdAtMillis = 1L, updatedAtMillis = 1L))
        dao.addVirtualAlbumMedia(listOf(VirtualAlbumMediaEntity(albumId, key.volumeName, key.mediaStoreId, 1L)))
        dao.upsertArchived(ArchivedMediaEntity(key.volumeName, key.mediaStoreId, 1L))
        assertTrue(dao.media(key.volumeName, key.mediaStoreId)!!.isFavorite)

        val operations = AdvancedFileOperations(
            advanced = storage,
            isActive = { true },
            deleteEmptyFolders = { false },
            sources = { keys ->
                keys.mapNotNull { k ->
                    dao.media(k.volumeName, k.mediaStoreId)?.let {
                        TransferSource(k, MediaKind.Image, it.volumeName, it.relativePath!!, it.displayName!!)
                    }
                }
            },
            applyHint = { synchronizer.applyRowHint(it) },
            reconcile = { v, p -> synchronizer.reconcileFolder(v, p) },
            refresh = {},
            retirement = OriginalRetirement { },
            folderBuckets = { _, _ -> emptyMap() },
            folderRelocated = { _, _, _, _, _ -> },
            verifyIdentity = { sources -> resolver.keysStillAtRememberedPath(sources) },
            verifyCopy = { source, targetPath -> resolver.copyMatchesItem(source, targetPath) },
        )
        val result = operations.transfer(TransferPurpose.Move, listOf(key), FolderTarget(key.volumeName, "Pictures/LfAppProbeTarget/"))

        assertEquals(1, result.moved)
        assertEquals(0, result.failed)
        assertTrue(File(target, "probe.png").exists())
        assertFalse(photo.exists())
        val row = dao.media(key.volumeName, key.mediaStoreId)
        assertNotNull("the same MediaKey is still indexed (the rename kept the _ID)", row)
        assertEquals("Pictures/LfAppProbeTarget/", row!!.relativePath)
        assertTrue("favourite kept", row.isFavorite)
        assertEquals("album membership kept", 1L, dao.virtualAlbumMediaCount(albumId))
        assertNotNull("archive entry kept", database.openHelper.readableDatabase.query(
            "SELECT 1 FROM archived_media WHERE volumeName=? AND mediaStoreId=?", arrayOf(key.volumeName, key.mediaStoreId.toString()),
        ).use { if (it.moveToFirst()) true else null })

        // Hiding the folder removes it from the library without forgetting the item.
        val hidden = storage.hideFolder(target.path)
        assertEquals(AdvancedResult.Done, hidden)
        synchronizer.reconcileFolder(key.volumeName, "Pictures/LfAppProbeTarget/")
        val afterHide = dao.media(key.volumeName, key.mediaStoreId)
        assertNotNull("the row survives a hide", afterHide)
        assertFalse("the row is inaccessible", afterHide!!.isAccessible)
    }

    private fun scan(vararg paths: String) {
        val latch = java.util.concurrent.CountDownLatch(paths.size)
        android.media.MediaScannerConnection.scanFile(context, paths, null) { _, _ -> latch.countDown() }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}
