package com.ugallery.core.editing.image

import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.EditRecipe
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoImageRendererDeviceTest {
    @Test
    fun previewAndFilteredExportAreBoundedAndNonEmpty() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "renderer-source-${System.nanoTime()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryRendererTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        val output = File(context.cacheDir, "renderer-${System.nanoTime()}.png")
        try {
            resolver.openOutputStream(source, "w")!!.use { outputStream ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("static.png").use { input ->
                    input.copyTo(outputStream)
                }
            }
            resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(source))
            val recipe = EditRecipe.forSource(key, 0).append(EditOperation.Filter("mono"))
            val renderer = PhotoImageRenderer(resolver, maxExportPixels = 2_000_000)
            val bounds = renderer.bounds(source)
            assertTrue(bounds.width > 0 && bounds.height > 0)
            val preview = renderer.renderPreview(source, recipe, 512)
            assertTrue(preview.width <= 512 || preview.height <= 512)
            preview.recycle()
            val result = renderer.export(source, recipe, output)
            assertTrue(result is PhotoExportOutcome.Completed)
            assertTrue(output.isFile && output.length() > 0)
        } finally {
            resolver.delete(source, null, null)
            output.delete()
        }
    }
}
