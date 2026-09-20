package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import com.ugallery.core.database.GalleryDatabaseFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end non-destructive cover: actual viewer + Photos render, Room, Activity recreation,
 * reset.
 */
class MotionPhotoAppDeviceTest {
    @Test
    fun motionCoverPersistsAndProjectsIntoViewerAndPhotos(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val resolver = context.contentResolver
        val db = GalleryDatabaseFactory.open(context)
        val name = "motion-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        var source: Uri? = null
        var durableFrame: File? = null
        fun hash(uri: Uri) =
            resolver.openInputStream(uri)!!.use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { n ->
                    "%02x".format(n)
                }
            }
        fun awaitTag(tag: String) {
            assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20000))
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                } catch (_: StaleObjectException) {}
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width() }
                    ?.scroll(Direction.DOWN, .6f)
                device.waitForIdle()
            }
            device.dumpWindowHierarchy(File(evidence, "failure.xml"))
            device.takeScreenshot(File(evidence, "failure.png"))
            error("Missing $selector")
        }
        fun click(tag: String) {
            find(By.res(tag)).click()
            device.waitForIdle()
        }
        fun capture(label: String): Bitmap {
            val file = File(evidence, "$label.png")
            assertTrue(device.takeScreenshot(file))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
            return BitmapFactory.decodeFile(file.absolutePath)!!
        }
        data class CoverPixels(val magenta: Float, val yellow: Float, val blue: Float, val chromatic: Float)
        fun pixels(bitmap: Bitmap, bounds: android.graphics.Rect?): CoverPixels {
            val area = bounds ?: android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            var magenta = 0
            var yellow = 0
            var blue = 0
            var chromatic = 0
            repeat(5) { x ->
                repeat(5) { y ->
                    val px = area.left + area.width() * (40 + x * 5) / 100
                    val py = area.top + area.height() * (40 + y * 5) / 100
                    val color = bitmap.getPixel(px.coerceIn(0, bitmap.width - 1), py.coerceIn(0, bitmap.height - 1))
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)
                    if (r > 180 && b > 140 && g < 100) magenta++
                    if (r > 180 && g > 180 && b < 90) yellow++
                    if (b > 180 && r < 100 && g < 140) blue++
                    if (maxOf(r, g, b) - minOf(r, g, b) > 70) chromatic++
                }
            }
            return CoverPixels(magenta / 25f, yellow / 25f, blue / 25f, chromatic / 25f)
        }
        fun isSelectedFrame(sample: CoverPixels) =
            sample.yellow >= .16f && sample.blue >= .16f && sample.chromatic >= .7f && sample.magenta <= .6f
        fun awaitColor(label: String, original: Boolean, tile: String? = null): Float {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            var last: CoverPixels? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val bounds = tile?.let { find(By.res(it)).visibleBounds }
                    ?: device.findObject(By.desc(context.getString(com.ugallery.feature.viewer.R.string.viewer_photo_description)))?.visibleBounds
                val bitmap = capture(label)
                last = pixels(bitmap, bounds)
                bitmap.recycle()
                // Require the fixture's positive yellow/blue pattern, not merely absence of magenta:
                // black loading frames, gray placeholders and unrelated UI do not satisfy this.
                if ((original && last.magenta >= .9f) || (!original && isSelectedFrame(last))) return last.magenta
                android.os.SystemClock.sleep(150)
            }
            error("Wrong rendered cover $label: pixels=$last, original=$original")
        }
        try {
            val clip =
                instrumentation.context.assets.open("motion_fixture.mp4").use { it.readBytes() }
            val fixtureVideo = File.createTempFile("motion-track-", ".mp4", context.cacheDir)
            val extractor = android.media.MediaExtractor()
            val videoDuration =
                try {
                    fixtureVideo.writeBytes(clip)
                    extractor.setDataSource(fixtureVideo.absolutePath)
                    val track =
                        (0 until extractor.trackCount).first {
                            extractor
                                .getTrackFormat(it)
                                .getString(android.media.MediaFormat.KEY_MIME)!!
                                .startsWith("video/")
                        }
                    extractor.getTrackFormat(track).getLong(android.media.MediaFormat.KEY_DURATION)
                } finally {
                    extractor.release()
                    fixtureVideo.delete()
                }
            val expectedKeyTime = (videoDuration - 1) * 4 / 5
            val xmp =
                """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:C="http://ns.google.com/photos/1.0/camera/" xmlns:G="http://ns.google.com/photos/1.0/container/" xmlns:I="http://ns.google.com/photos/1.0/container/item/"><rdf:RDF><rdf:Description C:MotionPhoto="1" C:MotionPhotoVersion="1"><G:Directory><rdf:Seq><rdf:li><G:Item I:Semantic="Primary" I:Mime="image/jpeg" I:Length="0" I:Padding="7"/></rdf:li><rdf:li><G:Item I:Semantic="MotionPhoto" I:Mime="video/mp4" I:Length="${clip.size}"/></rdf:li></rdf:Seq></G:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
            val bitmap =
                Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.MAGENTA)
                }
            val jpeg =
                ByteArrayOutputStream()
                    .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    .toByteArray()
            bitmap.recycle()
            val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
            val header =
                byteArrayOf(-1, -31) +
                    ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() +
                    payload
            val bytes =
                jpeg.copyOfRange(0, 2) +
                    header +
                    jpeg.copyOfRange(2, jpeg.size) +
                    ByteArray(7) +
                    clip
            source =
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            resolver.openOutputStream(source, "w")!!.use { it.write(bytes) }
            resolver.update(
                source,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            val id = ContentUris.parseId(source)
            val tile = "media_external_primary_$id"
            val originalHash = hash(source)
            device.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                find(By.text(grant)).click()
                val allow =
                    By.res(
                        Pattern.compile(
                            ".*permissioncontroller:id/permission_allow(?:_all)?_button"
                        )
                    )
                assertTrue(device.wait(Until.hasObject(allow), 10000))
                device.findObject(allow).click()
            }
            val decline =
                context.getString(
                    com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline
                )
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) find(By.text(decline)).click()
            if (!device.hasObject(By.res("timeline_grid")))
                find(By.text(context.getString(R.string.nav_photos))).click()
            withTimeout(45000) {
                while (db.libraryDao().media("external_primary", id) == null) delay(100)
            }
            awaitTag(tile)
            awaitColor("photos-original", true, tile)
            click(tile)
            val motionLabel =
                context.getString(com.ugallery.feature.motionphotos.R.string.motion_title)
            assertTrue(device.wait(Until.hasObject(By.text(motionLabel)), 15000))
            awaitColor("viewer-original", true)
            find(By.text(motionLabel)).click()
            awaitTag("motion-frame-5")
            click("motion-frame-4")
            click("motion-set-key-frame")
            find(By.res("motion-key-saved"))
            val selected =
                withTimeout(15000) {
                    var row = db.motionKeyFrameDao().get("external_primary", id)
                    while (row == null) {
                        delay(100)
                        row = db.motionKeyFrameDao().get("external_primary", id)
                    }
                    row
                }
            assertEquals(expectedKeyTime, selected.timeUs)
            durableFrame = File(context.filesDir, "motion-key-frames/${selected.fileName}")
            assertTrue(durableFrame.isFile)
            assertEquals(
                selected.sha256,
                MessageDigest.getInstance("SHA-256").digest(durableFrame.readBytes()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                },
            )
            val decodedFrame = BitmapFactory.decodeFile(durableFrame.absolutePath)!!
            assertTrue("Durable JPEG must contain the fixture's yellow/blue frame", isSelectedFrame(pixels(decodedFrame, null)))
            decodedFrame.recycle()
            capture("key-frame-saved").recycle()
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text(motionLabel)), 15000))
            val viewerChanged = awaitColor("viewer-changed", false)
            // The running Activity is recreated deliberately, not restarted after an observation
            // timeout.
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .single()
                    .recreate()
            }
            device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.text(motionLabel)), 20000))
            awaitColor("viewer-recreated", false)
            val reopened = GalleryDatabaseFactory.open(context)
            try {
                assertEquals(selected, reopened.motionKeyFrameDao().get("external_primary", id))
            } finally {
                reopened.close()
            }
            device.pressBack()
            awaitTag("timeline_grid")
            val photosChanged = awaitColor("photos-changed", false, tile)
            click(tile)
            assertTrue(device.wait(Until.hasObject(By.text(motionLabel)), 15000))
            find(By.text(motionLabel)).click()
            awaitTag("motion-frame-5")
            click("motion-reset-key-frame")
            withTimeout(15000) {
                // Reset commits the Room removal before verifying/deleting its private file.
                // Observe both completion effects, not the intermediate committed transaction.
                while (db.motionKeyFrameDao().get("external_primary", id) != null || durableFrame.exists())
                    delay(100)
            }
            assertFalse(durableFrame.exists())
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text(motionLabel)), 15000))
            awaitColor("viewer-reset", true)
            device.pressBack()
            awaitTag("timeline_grid")
            awaitColor("photos-reset", true, tile)
            assertEquals(originalHash, hash(source))
            File(evidence, "result.json")
                .writeText(
                    """{"status":"PASS","source":"$source","sha256":"$originalHash","keyTimeUs":${selected.timeUs},"viewerChangedMagenta":$viewerChanged,"photosChangedMagenta":$photosChanged,"activityRecreated":true,"databaseReopened":true,"resetOriginal":true}"""
                )
        } catch (failure: Throwable) {
            capture("failure").recycle()
            source?.let { uri ->
                val row = db.libraryDao().media("external_primary", ContentUris.parseId(uri))
                val generation = resolver.query(uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
                    if (it.moveToFirst()) it.getLong(0) else null
                }
                val sourceCopy = File(evidence, "failure-source.jpg")
                resolver.openInputStream(uri)!!.use { input -> sourceCopy.outputStream().use { output -> input.copyTo(output) } }
                val fdParse = runCatching {
                    resolver.openFileDescriptor(uri, "r")!!.use { fd ->
                        java.io.RandomAccessFile("/proc/self/fd/${fd.fd}", "r").use {
                            com.ugallery.feature.motionphotos.MotionPhotoParser.parse(it)
                        }
                    }
                }
                val copiedParse = runCatching {
                    java.io.RandomAccessFile(sourceCopy, "r").use {
                        com.ugallery.feature.motionphotos.MotionPhotoParser.parse(it)
                    }
                }
                val plainInspect = com.ugallery.feature.motionphotos.MotionPhotoSession.inspect(context,
                    com.ugallery.feature.motionphotos.MotionPhotoInput(uri))
                val guardedInspect = com.ugallery.feature.motionphotos.MotionPhotoSession.inspect(context,
                    com.ugallery.feature.motionphotos.MotionPhotoInput(uri, row?.generationModified))
                File(evidence, "detector.txt").writeText("uri=$uri\nmediaStoreGeneration=$generation\nroomGeneration=${row?.generationModified}\nfdParse=$fdParse\ncopiedParse=$copiedParse\nplainInspect=$plainInspect\nguardedInspect=$guardedInspect\nsourceSha256=${hash(uri)}\n")
            }
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            source?.let { uri ->
                val id = ContentUris.parseId(uri)
                db.motionKeyFrameDao().get("external_primary", id)?.let { row ->
                    db.motionKeyFrameDao().remove("external_primary", id, row.revision)
                    File(context.filesDir, "motion-key-frames/${row.fileName}").delete()
                }
                resolver.delete(uri, null, null)
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?",
                    arrayOf<Any>("external_primary", id),
                )
            }
            durableFrame?.delete()
            db.close()
        }
    }
}
