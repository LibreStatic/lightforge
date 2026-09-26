package com.ugallery.core.editing.image

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.EditRecipe
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoImageRendererDeviceTest {
    @Test
    fun croppedExportHasTheRequestedDimensions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "renderer-crop-${System.nanoTime()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryRendererTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        val output = File(context.cacheDir, "renderer-crop-${System.nanoTime()}.png")
        try {
            resolver.openOutputStream(source, "w")!!.use { outputStream ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("static.png").use { input ->
                    input.copyTo(outputStream)
                }
            }
            resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(source))
            val recipe = EditRecipe.forSource(key, 0).append(EditOperation.Crop(250, 250, 750, 750))
            val renderer = PhotoImageRenderer(resolver, maxExportPixels = 2_000_000)
            val sourceBounds = renderer.bounds(source)
            val result = renderer.export(source, recipe, output) as PhotoExportOutcome.Completed

            assertTrue(result.width < sourceBounds.width)
            assertTrue(result.height < sourceBounds.height)
            val decodedBounds = BitmapFactory.Options().also {
                it.inJustDecodeBounds = true
                BitmapFactory.decodeFile(output.absolutePath, it)
            }
            assertEquals(result.width, decodedBounds.outWidth)
            assertEquals(result.height, decodedBounds.outHeight)
        } finally {
            resolver.delete(source, null, null)
            output.delete()
        }
    }

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

    @Test
    fun tiled200MpTransformPreservesDimensionsWithoutFullBitmapDecode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "renderer-200mp-${System.nanoTime()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryRendererTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        val output = File(context.cacheDir, "renderer-200mp-${System.nanoTime()}.png")
        try {
            resolver.openOutputStream(source, "w")!!.use { outputStream ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("large_200mp.jpg").use { input ->
                    input.copyTo(outputStream)
                }
            }
            resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(source))
            val recipe = EditRecipe.forSource(key, 0).append(EditOperation.Filter("mono"))
            val result = PhotoImageRenderer(resolver, maxExportPixels = 8_000_000L).export(source, recipe, output)
            val completed = result as? PhotoExportOutcome.Completed
                ?: error("200 MP tiled export failed: $result")

            assertEquals(20_000, completed.width)
            assertEquals(10_000, completed.height)
            assertEquals("image/png", completed.mimeType)
            assertTrue(!completed.wasDownscaled)
            assertTrue(completed.warnings.single() == PhotoExportWarning.FullResolutionPng)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            assertEquals(20_000, bounds.outWidth)
            assertEquals(10_000, bounds.outHeight)
            val decoder = BitmapRegionDecoder.newInstance(output.absolutePath, false)
            try {
                val sample = decoder.decodeRegion(android.graphics.Rect(0, 0, 8, 8), BitmapFactory.Options())
                checkNotNull(sample)
                val pixel = sample.getPixel(0, 0)
                assertEquals(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel))
                assertEquals(android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                sample.recycle()
            } finally {
                decoder.recycle()
            }
        } finally {
            resolver.delete(source, null, null)
            output.delete()
        }
    }
}
