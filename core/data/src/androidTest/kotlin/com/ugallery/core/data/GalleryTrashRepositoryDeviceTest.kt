package com.ugallery.core.data

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.TrashPagingSource
import com.ugallery.core.mediastore.MediaStoreReader
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryTrashRepositoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: GalleryDatabase
    private var fixture: Uri? = null

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        fixture?.let { context.contentResolver.delete(it, null, null) }
        database.close()
    }

    @Test
    fun favoriteTrashExpiryRestoreAndExternalDeleteReconcileFromMediaStore() = runBlocking {
        val resolver = context.contentResolver
        val uri = publishFixture().also { fixture = it }
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        val reader = MediaStoreReader(resolver)

        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_FAVORITE, 1)
        }, null, null))
        assertTrue(requireNotNull(reader.readOne(key)).isFavorite)

        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
        }, null, null))
        val trashed = requireNotNull(reader.readOne(key))
        assertTrue(trashed.isTrashed)
        assertNotNull("provider must supply dynamic trash expiry", trashed.dateExpiresSeconds)
        assertTrue(requireNotNull(trashed.dateExpiresSeconds) > System.currentTimeMillis() / 1_000)
        database.libraryDao().upsertMedia(listOf(trashed.toEntity(scanId = 1)))

        val page = TrashPagingSource(database).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(listOf(key.mediaStoreId), page.data.map { it.mediaStoreId })
        assertEquals(trashed.dateExpiresSeconds, page.data.single().dateExpiresSeconds)

        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 0)
        }, null, null))
        assertFalse(requireNotNull(reader.readOne(key)).isTrashed)

        assertEquals(1, resolver.delete(uri, null, null))
        fixture = null
        assertNull(reader.readOne(key))
    }

    private fun publishFixture(): Uri {
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ugallery-m2-trash.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGalleryTrashTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ))
        resolver.openOutputStream(uri, "w")!!.use {
            it.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
