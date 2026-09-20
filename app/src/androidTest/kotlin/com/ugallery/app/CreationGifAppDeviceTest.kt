package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import com.ugallery.core.database.GalleryDatabaseFactory
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Only owned acceptance fixtures and own published output are removed. */
@Suppress("DEPRECATION")
class CreationGifAppDeviceTest {
    @Test fun selectionCreatesRealOrderedGifAndRestoresDraftAndSavedOutput(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val name = "creation-gif-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        var output: Uri? = null
        val createdOutputs = mutableListOf<Uri>()
        fun capture(label: String) { device.takeScreenshot(File(evidence, "$label.png")); device.dumpWindowHierarchy(File(evidence, "$label.xml")) }
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        fun awaitTag(tag: String) { assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20000)) }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } } catch (_: StaleObjectException) {}
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width() }?.scroll(Direction.DOWN, .6f)
                device.waitForIdle()
            }
            error("Missing $selector")
        }
        fun click(tag: String) { find(By.res(tag)).click(); device.waitForIdle() }
        fun recreate() {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single().recreate() }
            device.waitForIdle(); awaitTag("creation-gif-screen")
        }
        fun outputs(): Set<Long> = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf("Pictures/UGallery/GIF/", context.packageName), null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        val before = outputs()
        try {
            listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name-$index.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                sources += uri
                val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            val originalHashes = sources.map(::hash)
            device.executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                find(By.text(grant)).click()
                val allow = By.res(Pattern.compile(".*permissioncontroller:id/permission_allow(?:_all)?_button"))
                assertTrue(device.wait(Until.hasObject(allow), 10000)); device.findObject(allow).click()
            }
            val decline = context.getString(com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline)
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) find(By.text(decline)).click()
            if (!device.hasObject(By.res("timeline_grid"))) find(By.text(context.getString(R.string.nav_photos))).click()
            withTimeout(45000) { while (sources.any { db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null }) delay(100) }
            val tags = sources.map { "media_external_primary_${ContentUris.parseId(it)}" }
            awaitTag(tags[0]); find(By.res(tags[0])).longClick()
            tags.drop(1).forEach(::click)
            find(By.desc(context.getString(com.ugallery.feature.viewer.R.string.viewer_more))).click()
            click("selection-create-gif")
            awaitTag("creation-gif-preview")
            click("creation-gif-seconds-1")
            click("creation-gif-later") // GREEN,RED,BLUE; selectedRED.
            click("creation-gif-previous")
            click("creation-gif-remove") // RED,BLUE.
            assertEquals(context.getString(com.ugallery.feature.collage.R.string.creation_gif_position, 1, 2), find(By.res("creation-gif-position")).text)
            recreate()
            assertEquals(context.getString(com.ugallery.feature.collage.R.string.creation_gif_position, 1, 2), find(By.res("creation-gif-position")).text)
            capture("draft-recreated")
            click("creation-gif-export")
            // Recreate deliberately immediately after the export action; retained controller owns it.
            recreate()
            find(By.res("creation-gif-saved"))
            val fresh = outputs() - before
            assertEquals("Only this export must publish", 1, fresh.size)
            output = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fresh.single())
            createdOutputs += output!!
            val bytes = resolver.openInputStream(output!!)!!.use { it.readBytes() }
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(512, movie.width()); assertEquals(512, movie.height()); assertEquals(2000, movie.duration())
            val decoded = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, expected ->
                movie.setTime(index * 1000 + 500); movie.draw(Canvas(decoded), 0f, 0f); assertEquals(expected, decoded.getPixel(256, 256))
            }
            decoded.recycle()
            assertEquals(originalHashes, sources.map(::hash))
            File(evidence, "output.gif").writeBytes(bytes)
            capture("saved-recreated")
            device.pressBack(); awaitTag("timeline_grid")
            // A new creation is not an implicit resume of A: use a different source identity.
            click(tags[0]) // Deselect RED from retained RGB selection, leaving GREEN,BLUE.
            find(By.desc(context.getString(com.ugallery.feature.viewer.R.string.viewer_more))).click()
            click("selection-create-gif")
            awaitTag("creation-gif-preview")
            assertEquals(context.getString(com.ugallery.feature.collage.R.string.creation_gif_position, 1, 2), find(By.res("creation-gif-position")).text)
            assertTrue(find(By.res("creation-gif-seconds-2")).isChecked)
            assertFalse(device.hasObject(By.res("creation-gif-error")))
            assertFalse(device.hasObject(By.res("creation-gif-saved")))
            click("creation-gif-export")
            find(By.res("creation-gif-saved"))
            val secondIds = outputs() - before - createdOutputs.map(ContentUris::parseId).toSet()
            assertEquals(1, secondIds.size)
            val second = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, secondIds.single())
            createdOutputs += second
            val secondBytes = resolver.openInputStream(second)!!.use { it.readBytes() }
            val secondMovie = Movie.decodeByteArray(secondBytes, 0, secondBytes.size)!!
            assertEquals(4000, secondMovie.duration())
            val secondFrame = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            listOf(Color.GREEN, Color.BLUE).forEachIndexed { index, expected ->
                secondMovie.setTime(index * 2000 + 500); secondMovie.draw(Canvas(secondFrame), 0f, 0f)
                assertEquals(expected, secondFrame.getPixel(256, 256))
            }
            secondFrame.recycle()
            File(evidence, "second-output.gif").writeBytes(secondBytes)
            capture("second-creation")
            device.pressBack(); awaitTag("timeline_grid")
            // The physical album contains only this test's three sources.
            find(By.text(context.getString(R.string.nav_collections))).click()
            find(By.text(name)).click()
            val photo = By.desc(context.getString(com.ugallery.feature.album.R.string.album_photo))
            assertTrue(device.wait(Until.hasObject(photo), 15000))
            assertEquals(3, device.findObjects(photo).size)
            device.findObjects(photo)[0].longClick()
            device.findObjects(photo)[1].click()
            find(By.desc(context.getString(com.ugallery.feature.viewer.R.string.viewer_more))).click()
            click("selection-create-gif")
            awaitTag("creation-gif-preview")
            device.pressBack()
            assertTrue("Back must restore the originating album", device.wait(Until.hasObject(By.text(name)), 15000))
            assertEquals(3, device.findObjects(photo).size)
            assertFalse(device.hasObject(By.res("timeline_grid")))
            assertEquals(originalHashes, sources.map(::hash))
            capture("returned-to-album")
            File(evidence, "result.json").writeText("""{"status":"PASS","uri":"$output","frames":2,"durationMs":2000,"secondUri":"$second","secondDurationMs":4000,"secondOrder":"green,blue","newDraft":true,"returnedToAlbum":true,"width":512,"height":512,"order":"red,blue","draftRecreated":true,"exportRecreated":true,"originalsUnchanged":true}""")
        } catch (failure: Throwable) {
            capture("failure"); File(evidence, "failure.txt").writeText(failure.stackTraceToString()); throw failure
        } finally {
            val unrecorded = outputs() - before - createdOutputs.map(ContentUris::parseId).toSet()
            unrecorded.singleOrNull()?.let { createdOutputs += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
            createdOutputs.distinct().forEach { resolver.delete(it, null, null) }
            sources.forEach { uri ->
                resolver.delete(uri, null, null)
                db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?", arrayOf<Any>("external_primary", ContentUris.parseId(uri)))
            }
            db.close()
        }
    }
}
