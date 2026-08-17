package com.ugallery.core.thumbnail

import android.content.ContentValues
import android.graphics.Rect
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val FIXTURE_NAME = "ugallery_m0_200mp.jpg"
private const val EDITOR_PSS_GATE_KB = 500 * 1024

@RunWith(AndroidJUnit4::class)
class LargeImageDecoderDeviceTest {
    @Test
    fun previewAndTilesStayInsideEditorMemoryGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val uri = publishFixture()
        val decoder = NativeImageDecoder(resolver)
        try {
            forceGc()
            var peakPssKb = Debug.getPss()
            var previewPssKb = peakPssKb
            val tilePssKb = mutableListOf<Long>()

            val previewStartNs = SystemClock.elapsedRealtimeNanos()
            var previewWidth = 0
            var previewHeight = 0
            decoder.screenPreview(uri, targetWidth = 1_440, targetHeight = 3_120).also { preview ->
                assertTrue("preview width=${preview.width}", preview.width <= 1_440)
                assertTrue("preview height=${preview.height}", preview.height <= 3_120)
                previewWidth = preview.width
                previewHeight = preview.height
                peakPssKb = maxOf(peakPssKb, Debug.getPss())
                previewPssKb = Debug.getPss()
                preview.recycle()
            }
            val previewMs = (SystemClock.elapsedRealtimeNanos() - previewStartNs) / 1_000_000

            val tilesStartNs = SystemClock.elapsedRealtimeNanos()
            val deepZoom = decoder.deepZoomAvailability(uri)
            if (deepZoom is DeepZoomAvailability.Available) {
                listOf(
                    Rect(0, 0, 2_048, 2_048),
                    Rect(8_976, 3_976, 11_024, 6_024),
                    Rect(17_952, 7_952, 20_000, 10_000),
                ).forEach { region ->
                    val tile = decoder.tile(uri, region, sampleSize = 1)
                    assertNotNull("region decode failed for $region", tile)
                    assertTrue("tile width=${tile?.width}", requireNotNull(tile).width <= 1_024)
                    assertTrue("tile height=${tile.height}", tile.height <= 1_024)
                    peakPssKb = maxOf(peakPssKb, Debug.getPss())
                    tilePssKb.add(Debug.getPss())
                    tile.recycle()
                }
            } else {
                assertTrue(
                    "unexpected deep zoom state=$deepZoom",
                    deepZoom == DeepZoomAvailability.Unavailable(
                        DeepZoomUnavailableReason.DeviceDecoderMemoryRisk,
                    ),
                )
            }
            val tilesMs = (SystemClock.elapsedRealtimeNanos() - tilesStartNs) / 1_000_000

            forceGc()
            val finalPssKb = Debug.getPss()
            println(
                "UGALLERY_LARGE_IMAGE_METRICS fixture=$FIXTURE_NAME preview=${previewWidth}x$previewHeight " +
                    "previewMs=$previewMs threeTilesMs=$tilesMs peakPssKb=$peakPssKb finalPssKb=$finalPssKb " +
                    "previewPssKb=$previewPssKb tilePssKb=$tilePssKb deepZoom=$deepZoom",
            )
            assertTrue("peak PSS ${peakPssKb}KB exceeds ${EDITOR_PSS_GATE_KB}KB", peakPssKb <= EDITOR_PSS_GATE_KB)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun corruptJpegFailsPreviewAndDisablesDeepZoom() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = publishBytes("ugallery_m0_corrupt.jpg", "image/jpeg", "not-a-jpeg".encodeToByteArray())
        try {
            val decoder = NativeImageDecoder(resolver)
            assertTrue(runCatching { decoder.screenPreview(uri, 1_440, 3_120) }.isFailure)
            assertNull(decoder.tile(uri, Rect(0, 0, 1, 1), sampleSize = 1))
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun publishFixture(): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return publish(
            displayName = FIXTURE_NAME,
            mimeType = "image/jpeg",
        ) { output ->
            instrumentation.context.assets.open(FIXTURE_NAME).use { input -> input.copyTo(output) }
        }
    }

    private fun publishBytes(displayName: String, mimeType: String, bytes: ByteArray): Uri =
        publish(displayName, mimeType) { output -> output.write(bytes) }

    private fun publish(
        displayName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val primaryImages = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGalleryBenchmark")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(primaryImages, values)) { "MediaStore insert failed" }
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                write(output)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun forceGc() {
        repeat(2) {
            Runtime.getRuntime().gc()
            Thread.sleep(100)
        }
    }
}
