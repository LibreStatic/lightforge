package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.database.GalleryDatabaseFactory
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Two owned PNGs only: real selection handoff, editing, back origin and a fresh second draft. */
@Suppress("DEPRECATION")
class SelectionMemoryVideoAppDeviceTest {
    @Test fun selectedPhotosOpenEditableVideoAndBackRetainsSelectionWithoutReusingDraft(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val name = "selection-memory-video-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }
        fun capture(label: String) {
            device.takeScreenshot(File(evidence, "$label.png"))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        }
        fun awaitTag(tag: String) {
            assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20000))
        }
        fun scroll(backward: Boolean): Boolean {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser && node.isScrollable) candidates += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
            val node = candidates.maxByOrNull {
                val bounds = Rect(); it.getBoundsInScreen(bounds); bounds.width() * bounds.height()
            } ?: return false
            return node.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            var backward = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } }
                catch (_: StaleObjectException) {}
                if (!scroll(backward)) backward = !backward
                device.waitForIdle()
            }
            error("Missing $selector")
        }
        fun click(tag: String) {
            find(By.res(tag))
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && node.isEnabled && node.viewIdResourceName == tag) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { target(it)?.let { found -> return found } }
                    return null
                }
                var node = instrumentation.uiAutomation.rootInActiveWindow?.let(::target)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    File(evidence, "actions.txt").appendText("ACTION_CLICK $tag accepted\n")
                    device.waitForIdle()
                    return
                }
                android.os.SystemClock.sleep(100)
            }
            error("Click not accepted: $tag")
        }
        fun position(number: Int) {
            find(By.res("memory-video-position"))
            val expected = context.getString(com.ugallery.feature.videoeditor.R.string.memory_video_position, number, 2)
            assertTrue("Expected video position $number/2", device.wait(Until.hasObject(By.text(expected)), 5000))
        }
        fun selectedSeconds(seconds: Int) {
            val tag = "memory-video-seconds-$seconds"
            find(By.res(tag))
            assertTrue("Expected $seconds seconds per photo", device.wait(Until.hasObject(By.res(tag).checked(true)), 5000))
        }
        fun selectionRetained() {
            awaitTag("timeline_grid")
            assertTrue("Selection was not retained at Photos origin",
                device.wait(Until.hasObject(By.text(context.getString(R.string.selection_count, 2))), 5000))
        }
        fun openVideo() {
            val label = context.getString(R.string.nav_create)
            (device.findObject(By.desc(label)) ?: find(By.text(label))).click()
            click("create-memory-video")
            awaitTag("memory-video-screen")
        }
        try {
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name-$index.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                sources += uri
                val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            }
            val originalHashes = sources.map(::hash)
            device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                find(By.text(grant)).click()
                val allow = By.res(Pattern.compile(".*permissioncontroller:id/permission_allow(?:_all)?_button"))
                assertTrue(device.wait(Until.hasObject(allow), 10000)); device.findObject(allow).click()
            }
            val decline = context.getString(com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline)
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) find(By.text(decline)).click()
            if (!device.hasObject(By.res("timeline_grid"))) find(By.text(context.getString(R.string.nav_photos))).click()
            awaitTag("timeline_grid")
            withTimeout(45000) {
                while (sources.any { db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null }) delay(100)
            }
            val tags = sources.map { "media_external_primary_${ContentUris.parseId(it)}" }
            awaitTag(tags[0]); find(By.res(tags[0])).longClick()
            click(tags[1])
            selectionRetained()
            capture("selected-two-photos")
            openVideo()
            position(1)
            selectedSeconds(3)
            click("memory-video-later")
            position(2)
            click("memory-video-seconds-1")
            selectedSeconds(1)
            capture("edited-first-draft")
            device.pressBack()
            selectionRetained()
            assertFalse(device.hasObject(By.res("memory-video-screen")))
            capture("returned-photos-selection")
            openVideo()
            position(1)
            selectedSeconds(3)
            assertFalse(device.hasObject(By.res("memory-video-saved")))
            assertFalse(device.hasObject(By.res("memory-video-progress")))
            capture("fresh-second-draft")
            assertEquals(originalHashes, sources.map(::hash))
            device.pressBack()
            selectionRetained()
            File(evidence, "result.json").writeText("""{"status":"PASS","photos":2,"entry":"create-memory-video","orderAction":"later","firstDraftPosition":2,"firstDraftSeconds":1,"secondDraftPosition":1,"secondDraftSeconds":3,"backOrigin":"Photos","selectionRetained":true,"originalsUnchanged":true,"exportExecuted":false,"sourceHashes":[${originalHashes.joinToString(",") { "\"$it\"" }}]}""")
        } catch (failure: Throwable) {
            capture("failure")
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            sources.forEach { uri ->
                resolver.delete(uri, null, null)
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?",
                    arrayOf<Any>("external_primary", ContentUris.parseId(uri)),
                )
            }
            db.close()
        }
    }
}
