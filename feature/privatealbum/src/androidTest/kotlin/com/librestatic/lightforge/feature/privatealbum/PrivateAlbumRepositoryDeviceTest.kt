package com.librestatic.lightforge.feature.privatealbum

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateAlbumRepositoryDeviceTest {
    private lateinit var context: Context
    private lateinit var database: PrivateAlbumDatabase
    private var sourceId: Long? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        sourceId?.let { id ->
            context.contentResolver.delete(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id),
                null,
                null,
            )
        }
    }

    @Test
    fun importFromMediaEncryptsMetadataAndCommitsRow() = runBlocking {
        val media = createPng()
        val repository = PrivateAlbumRepository(context, database)

        val result = repository.importFromMedia(media, aesKey())

        assertTrue(result.success)
        assertEquals(1, repository.count())
        val stored = repository.getMetadata(requireNotNull(result.mediaId))
        assertEquals(media.displayName, stored?.originalDisplayName)
        assertEquals("image/png", stored?.originalMimeType)
        assertEquals("image", stored?.mediaKind)
        assertTrue(File(requireNotNull(stored).containerPath).length() > 0)
        repository.delete(requireNotNull(result.mediaId))
    }

    @Test
    fun failedDatabaseCommitRemovesPartialContainer() = runBlocking {
        val media = createPng()
        val repository = PrivateAlbumRepository(context, database)
        val directory = File(context.filesDir, "private-album")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        database.close()

        val result = repository.importFromMedia(media, aesKey())

        val after = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertFalse(result.success)
        assertEquals(before, after)
    }

    @Test
    fun importFromMediaAcceptsAndroidKeystoreMasterKey() = runBlocking {
        PrivateAlbumCrypto.deleteMasterKey()
        try {
            val media = createPng()
            val repository = PrivateAlbumRepository(context, database)

            val result = repository.importFromMedia(
                media,
                PrivateAlbumCrypto.getOrCreateMasterKey(),
            )

            assertTrue(result.success)
            repository.delete(requireNotNull(result.mediaId))
        } finally {
            PrivateAlbumCrypto.deleteMasterKey()
        }
    }

    private fun createPng(): TimelineMedia {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "private-import-${System.nanoTime()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ),
        )
        context.contentResolver.openOutputStream(uri).use { output ->
            requireNotNull(output).write(ONE_PIXEL_PNG)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        val id = uri.lastPathSegment!!.toLong()
        sourceId = id
        return TimelineMedia(
            key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, id),
            kind = MediaKind.Image,
            generationModified = 1,
            timelineSortMillis = System.currentTimeMillis(),
            width = 1,
            height = 1,
            durationMillis = 0,
            displayName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                ?: "private-import.png",
        )
    }

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    companion object {
        private val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT,
        )
    }
}
