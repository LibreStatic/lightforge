package com.librestatic.lightforge.core.raw

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.RawCameraCrop
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawOutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Uses the synthetic DNGs from fixtures/make_default_crop_dngs.py: a 64x48 blue field with an
 * orange rectangle at the declared DefaultCrop whose first 8x8 pixels are a white patch.
 */
class RawCameraFramingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val work = File(context.cacheDir, "raw-framing-test")
    private lateinit var developer: RawDeveloper

    private val framed = RawDevelopmentSettings(cameraFraming = true)
    private val full = RawDevelopmentSettings(cameraFraming = false)

    @Before
    fun setUp() {
        work.deleteRecursively()
        work.mkdirs()
        developer = RawDeveloper(context.contentResolver, File(work, "scratch"))
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun fixture(name: String): Uri {
        val file = File(work, name)
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private fun Bitmap.isBlue(x: Int, y: Int) = Color.blue(getPixel(x, y)) > Color.red(getPixel(x, y)) + 60
    private fun Bitmap.isOrange(x: Int, y: Int) = Color.red(getPixel(x, y)) > Color.blue(getPixel(x, y)) + 60
    private fun Bitmap.isWhite(x: Int, y: Int) = getPixel(x, y).let {
        Color.red(it) > 190 && Color.green(it) > 190 && Color.blue(it) > 190
    }

    @Test
    fun inspectReportsFramedSizeFullSizeAndCropRect() = runBlocking {
        val metadata = developer.inspect(fixture("default_crop_even.dng"))
        assertEquals(30, metadata.width)
        assertEquals(20, metadata.height)
        assertEquals(64, metadata.fullWidth)
        assertEquals(48, metadata.fullHeight)
        assertEquals(RawCameraCrop(10, 8, 30, 20), metadata.cameraCrop)
        assertTrue(metadata.hasMeaningfulCameraCrop)
        assertEquals(30, metadata.dimensions(cameraFraming = true).width)
        assertEquals(64, metadata.dimensions(cameraFraming = false).width)
    }

    @Test
    fun framingOnDevelopsOnlyTheDefaultCrop() = runBlocking {
        val bitmap = developer.renderPreview(fixture("default_crop_even.dng"), framed, 256)
        assertEquals(30, bitmap.width)
        assertEquals(20, bitmap.height)
        assertTrue("white patch at the framed origin", bitmap.isWhite(3, 3))
        assertTrue("orange inside the frame", bitmap.isOrange(20, 12))
        assertTrue("orange near the framed corner", bitmap.isOrange(25, 16))
    }

    @Test
    fun framingOffDevelopsTheFullSensor() = runBlocking {
        val bitmap = developer.renderPreview(fixture("default_crop_even.dng"), full, 256)
        assertEquals(64, bitmap.width)
        assertEquals(48, bitmap.height)
        assertTrue(bitmap.isBlue(2, 2))
        assertTrue(bitmap.isBlue(58, 44))
        assertTrue(bitmap.isWhite(13, 11))
        assertTrue(bitmap.isOrange(30, 20))
    }

    @Test
    fun oddOriginIsRoundedUpToTheBayerPhaseByLibRaw() = runBlocking {
        val uri = fixture("default_crop_odd.dng")
        // Declared origin 11,9 and size 30x20; LibRaw moves the origin to the even 12,10 and
        // gives up the pixel, so the framed size is 29x19 and the CFA phase stays RGGB.
        val metadata = developer.inspect(uri)
        assertEquals(RawCameraCrop(12, 10, 29, 19), metadata.cameraCrop)
        val bitmap = developer.renderPreview(uri, framed, 256)
        assertEquals(29, bitmap.width)
        assertEquals(19, bitmap.height)
        assertTrue(bitmap.isWhite(2, 2))
        assertTrue(bitmap.isOrange(20, 12))
        assertEquals(64, developer.renderPreview(uri, full, 256).width)
    }

    @Test
    fun fileWithoutDefaultCropIsUnchanged() = runBlocking {
        val uri = fixture("no_crop.dng")
        val metadata = developer.inspect(uri)
        assertNull(metadata.cameraCrop)
        assertFalse(metadata.hasMeaningfulCameraCrop)
        assertEquals(64, metadata.width)
        assertEquals(48, metadata.height)
        listOf(framed, full).forEach {
            val bitmap = developer.renderPreview(uri, it, 256)
            assertEquals(64, bitmap.width)
            assertEquals(48, bitmap.height)
        }
    }

    @Test
    fun previewSessionTogglingFramingChangesTheOutputSize() = runBlocking {
        developer.openPreviewSession(fixture("default_crop_even.dng")).use { session ->
            assertEquals(30, session.renderPreview(framed, 256).width)
            val wide = session.renderPreview(full, 256)
            assertEquals(64, wide.width)
            assertEquals(48, wide.height)
            assertTrue(wide.isBlue(2, 2))
            val narrow = session.renderPreview(framed, 256)
            assertEquals(30, narrow.width)
            assertEquals(20, narrow.height)
            assertTrue(narrow.isWhite(3, 3))
            // Slider-style approximate frames keep the current framing.
            assertEquals(30, session.renderPreview(framed.copy(exposureEv = 0.5f), 256, approximate = true).width)
        }
    }

    @Test
    fun exportsUseTheDevelopedFrameSize() = runBlocking {
        val uri = fixture("default_crop_even.dng")
        val on = developer.export(uri, framed, RawOutputFormat.JpegSrgb, File(work, "on.jpg"))
        val off = developer.export(uri, full, RawOutputFormat.JpegSrgb, File(work, "off.jpg"))
        val tiff = developer.export(uri, framed, RawOutputFormat.Tiff16Srgb, File(work, "on.tiff"))
        assertEquals(30, (on as RawExportOutcome.Completed).width)
        assertEquals(20, on.height)
        assertEquals(64, (off as RawExportOutcome.Completed).width)
        assertEquals(48, off.height)
        assertEquals(30, (tiff as RawExportOutcome.Completed).width)
        assertEquals(20, tiff.height)
    }

    @Test
    fun jpegExportDevelopsIntoTheBitmapWithPreviewColors() = runBlocking {
        val uri = fixture("default_crop_even.dng")
        val outcome = developer.export(uri, framed, RawOutputFormat.JpegSrgb, File(work, "direct.jpg"))
        val decoded = android.graphics.BitmapFactory.decodeFile((outcome as RawExportOutcome.Completed).file.path)
        assertEquals(30, decoded.width)
        assertEquals(20, decoded.height)
        assertTrue("white patch", decoded.isWhite(3, 3))
        assertTrue("orange inside the frame", decoded.isOrange(20, 12))
        assertFalse("not channel swapped", decoded.isBlue(20, 12))
        val preview = developer.renderPreview(uri, framed, 256)
        for ((x, y) in listOf(3 to 3, 20 to 12, 25 to 16)) {
            val a = decoded.getPixel(x, y)
            val b = preview.getPixel(x, y)
            assertTrue("red at $x,$y", kotlin.math.abs(Color.red(a) - Color.red(b)) < 24)
            assertTrue("green at $x,$y", kotlin.math.abs(Color.green(a) - Color.green(b)) < 24)
            assertTrue("blue at $x,$y", kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 24)
        }
    }

    @Test
    fun jpegExportOfAnOddOriginFrameUsesLibRawsRoundedSize() = runBlocking {
        // The bitmap must follow LibRaw's 29x19 output, not the declared 30x20 DefaultCrop.
        val outcome = developer.export(
            fixture("default_crop_odd.dng"), framed, RawOutputFormat.JpegSrgb, File(work, "odd.jpg"),
        )
        val completed = outcome as RawExportOutcome.Completed
        val decoded = android.graphics.BitmapFactory.decodeFile(completed.file.path)
        assertEquals(29, decoded.width)
        assertEquals(19, decoded.height)
        assertEquals(29, completed.width)
        assertTrue(decoded.isWhite(2, 2))
    }

    @Test
    fun jpegExportReportsTooLargeWhenMemoryIsShort() = runBlocking {
        val tight = RawDeveloper(context.contentResolver, File(work, "scratch"), availableMemoryBytes = { 0L })
        val outcome = tight.export(
            fixture("default_crop_even.dng"), framed, RawOutputFormat.JpegSrgb, File(work, "tight.jpg"),
        )
        assertEquals(RawExportFailureKind.TooLargeForJpeg, (outcome as RawExportOutcome.Failure).kind)
    }
}
