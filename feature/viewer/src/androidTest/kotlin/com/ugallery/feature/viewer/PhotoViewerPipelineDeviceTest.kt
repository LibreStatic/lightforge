package com.ugallery.feature.viewer

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.thumbnail.NativeImageDecoder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoViewerPipelineDeviceTest {
    @Test
    fun fastOrVisuallySubtleThumbnailUpgradeIsImmediate() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val decoder = NativeImageDecoder(resolver)
        val fixture = publish("static.png", "image/png")
        try {
            val visiblyDifferent = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.MAGENTA)
            }
            val fastClock = ArrayDeque(listOf(0L, 249L))
            val fast = PhotoViewerPipeline(decoder, elapsedRealtimeMillis = fastClock::removeFirst)
                .load(fixture, 1_080, 2_400, visiblyDifferent)
                .toList().last() as PhotoLoadState.Ready
            assertEquals(PhotoPreviewTransition.Immediate, fast.thumbnailTransition)
            (fast.drawable as BitmapDrawable).bitmap.recycle()
            visiblyDifferent.recycle()

            val equivalentThumbnail = decoder.screenPreview(fixture, 64, 64)
            val slowClock = ArrayDeque(listOf(0L, 300L))
            val subtle = PhotoViewerPipeline(decoder, elapsedRealtimeMillis = slowClock::removeFirst)
                .load(fixture, 1_080, 2_400, equivalentThumbnail)
                .toList().last() as PhotoLoadState.Ready
            assertEquals(PhotoPreviewTransition.Immediate, subtle.thumbnailTransition)
            (subtle.drawable as BitmapDrawable).bitmap.recycle()
            equivalentThumbnail.recycle()
        } finally {
            resolver.delete(fixture, null, null)
        }
    }

    @Test
    fun staticAnimatedAndCorruptFormatsHaveExplicitStates() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val pipeline = PhotoViewerPipeline(NativeImageDecoder(resolver))
        val fixtures = listOf(
            publish("static.png", "image/png"),
            publish("animated.gif", "image/gif"),
            publish("animated.webp", "image/webp"),
            publish("corrupt.jpg", "image/jpeg"),
        )
        try {
            val cached = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val staticStates = pipeline.load(fixtures[0], 1_080, 2_400, cachedThumbnail = cached).toList()
            assertSame(cached, (staticStates.first() as PhotoLoadState.Thumbnail).bitmap)
            val static = staticStates.last() as PhotoLoadState.Ready
            assertFalse(static.isAnimated)
            assertTrue(static.supportsDeepZoom)
            (static.drawable as BitmapDrawable).bitmap.recycle()
            cached.recycle()

            val gif = pipeline.load(fixtures[1], 1_080, 2_400).toList().single()
                as PhotoLoadState.Ready
            assertTrue(gif.isAnimated)
            assertFalse(gif.supportsDeepZoom)

            val webp = pipeline.load(fixtures[2], 1_080, 2_400).toList().single()
                as PhotoLoadState.Ready
            assertTrue(webp.isAnimated)
            assertFalse(webp.supportsDeepZoom)

            val corrupt = pipeline.load(fixtures[3], 1_080, 2_400).toList().single()
            assertTrue(corrupt == PhotoLoadState.Error(PhotoFailure.CorruptOrUnsupported))
        } finally {
            fixtures.forEach { resolver.delete(it, null, null) }
        }
    }

    private fun publish(assetName: String, mimeType: String): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ugallery-m2-$assetName")
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGalleryViewerTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                instrumentation.context.assets.open(assetName).use { it.copyTo(output) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
