package com.librestatic.lightforge.core.thumbnail

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs a HEIC on the device that the platform cannot decode (iOS HDR gain-map HEIC). Pass its
 * MediaStore display name with `-e heicFixtureName <name>`; skipped when absent.
 */
@RunWith(AndroidJUnit4::class)
class HeifFallbackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver: ContentResolver = instrumentation.targetContext.contentResolver

    private fun fixtureUri(): Uri {
        val name = InstrumentationRegistry.getArguments().getString("heicFixtureName") ?: "IMG_0735.HEIC.heif"
        val uri = try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (_: SecurityException) {
            null
        }
        assumeTrue("Fixture $name not found in MediaStore", uri != null)
        val readable = try {
            resolver.openFileDescriptor(uri!!, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
        assumeTrue("Fixture $name is not readable", readable)
        return uri!!
    }

    /** The fixture is an iPhone HEIC whose ICC profile is Display P3. */
    private fun assertDisplayP3(bitmap: Bitmap) {
        assertEquals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3), bitmap.colorSpace)
    }

    private fun assertPortraitAndNotUniform(bitmap: Bitmap) {
        assertTrue("expected portrait, got ${bitmap.width}x${bitmap.height}", bitmap.width < bitmap.height)
        val ratio = bitmap.width.toDouble() / bitmap.height
        assertEquals("expected about 3:4", 0.75, ratio, 0.03)
        // A black or gray image has (almost) zero luma variance.
        val samples = ArrayList<Double>()
        for (y in 0 until 16) for (x in 0 until 16) {
            val pixel = bitmap.getPixel(
                (x * (bitmap.width - 1)) / 15,
                (y * (bitmap.height - 1)) / 15,
            )
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            samples += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        assertTrue("image looks uniform (variance=$variance)", variance > 100.0)
    }

    @Test
    fun fallbackDecodesEmbeddedThumbnail() {
        val uri = fixtureUri()
        val bitmap = HeifFallbackDecoder(resolver).decodeThumbnail(uri, Size(384, 384), CancellationSignal())
        assertPortraitAndNotUniform(bitmap)
        assertDisplayP3(bitmap)
    }

    @Test
    fun fallbackDecodesPrimaryGrid() {
        val uri = fixtureUri()
        val bitmap = HeifFallbackDecoder(resolver).decodePrimary(uri, 1600, 1600)
        assertPortraitAndNotUniform(bitmap)
        assertDisplayP3(bitmap)
        assertTrue(bitmap.width <= 1600 && bitmap.height <= 1600)
    }

    @Test
    fun nativeDecoderThumbnailReturnsBitmap() {
        val uri = fixtureUri()
        val bitmap = NativeImageDecoder(resolver).thumbnail(uri, Size(256, 256), CancellationSignal())
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }
}
