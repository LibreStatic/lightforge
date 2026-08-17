package com.ugallery.core.mediastore

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreReaderDeviceTest {
    @Test
    fun mapsAppOwnedImageToVolumeScopedKeyAndCanonicalUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val name = "ugallery-reader-${System.nanoTime()}.png"
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))

        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.BLUE)
                    compress(Bitmap.CompressFormat.PNG, 100, output)
                    recycle()
                }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            val id = uri.lastPathSegment!!.toLong()

            var afterId = maxOf(-1, id - 2)
            var record: MediaStoreRecord? = null
            repeat(3) {
                val page = MediaStoreReader(resolver).readIdPage(volume, afterId, limit = 2)
                record = page.records.firstOrNull { it.key == MediaKey(volume, id) } ?: record
                afterId = page.nextAfterId ?: return@repeat
            }
            val mapped = checkNotNull(record)
            assertEquals(MediaKey(volume, id), mapped.key)
            assertEquals(MediaKind.Image, mapped.kind)
            assertEquals(name, mapped.displayName)
            assertEquals(uri, MediaStoreUriFactory.uriFor(mapped.key))
            assertTrue(mapped.sizeBytes > 0)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun sameIdOnDifferentVolumesBuildsDifferentUris() {
        val primary = MediaStoreUriFactory.uriFor(MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, 42))
        val removable = MediaStoreUriFactory.uriFor(MediaKey("1234-5678", 42))
        assertNotEquals(primary, removable)
        assertTrue(primary.toString().contains(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        assertTrue(removable.toString().contains("1234-5678"))
    }
}
