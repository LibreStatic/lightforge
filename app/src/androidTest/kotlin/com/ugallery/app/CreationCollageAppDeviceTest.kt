package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
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
class CreationCollageAppDeviceTest {
    @Test fun selectionCreatesExactStripAndRetainsResultAcrossRecreation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val focused = InstrumentationRegistry.getArguments().getString("collageFocused") == "true"
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val name = "creation-collage-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        var output: Uri? = null
        val createdOutputs = mutableListOf<Uri>()
        fun capture(label: String) { device.takeScreenshot(File(evidence, "$label.png")); device.dumpWindowHierarchy(File(evidence, "$label.xml")) }
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        fun awaitTag(tag: String) { assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20000)) }
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
                try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } } catch (_: StaleObjectException) {}
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
                    device.waitForIdle(); return
                }
                android.os.SystemClock.sleep(100)
            }
            error("Click not accepted: $tag")
        }
        fun assertFirstPhoto(number: Int) {
            val label = context.getString(com.ugallery.feature.collage.R.string.creation_collage_photo, number)
            // Wait for the updated descendant, not only the pre-existing parent chip.
            find(By.res("creation-collage-slot-0").hasDescendant(By.text(label)))
        }
        fun recreate() {
            val originalPid = android.os.Process.myPid()
            lateinit var previous: MainActivity
            instrumentation.runOnMainSync {
                previous = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                previous.recreate()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            var replacedAndResumed = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    assertEquals("Recreation must retain the application process", originalPid, android.os.Process.myPid())
                    val current = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                    replacedAndResumed = current != null && current !== previous && previous.isDestroyed
                }
                if (replacedAndResumed) break
                android.os.SystemClock.sleep(50)
            }
            assertTrue("Previous Activity must be destroyed and a distinct MainActivity RESUMED", replacedAndResumed)
            assertEquals("Recreation must retain the application process", originalPid, android.os.Process.myPid())
            File(evidence, "actions.txt").appendText("ACTIVITY_RECREATED pid=$originalPid previousDestroyed=true instanceChanged=true resumed=true\n")
            // The old Activity's still-visible tag must never satisfy this wait.
            device.waitForIdle(); awaitTag("creation-collage-screen")
        }
        fun outputs(): Set<Long> = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf("Pictures/UGallery/Collage/", context.packageName), null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        val before = outputs()
        try {
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
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
            withTimeout(45000) { while (sources.any { db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null }) delay(100) }
            val tags = sources.map { "media_external_primary_${ContentUris.parseId(it)}" }
            awaitTag(tags[0]); find(By.res(tags[0])).longClick()
            tags.drop(1).forEach(::click)
            fun openCreateSheet() {
                val label = context.getString(R.string.nav_create)
                val control = device.findObject(By.desc(label)) ?: find(By.text(label))
                control.click(); click("create-collage")
                awaitTag("creation-collage-screen")
            }
            openCreateSheet()
            awaitTag("creation-collage-preview")
            click("creation-collage-template-Strip4")
            if (!focused) {
            click("creation-collage-later") // GREEN,RED,BLUE,YELLOW; source crop stays attached to RED.
            assertFirstPhoto(2)
            if (!focused) recreate()
            assertTrue(find(By.res("creation-collage-template-Strip4")).isChecked)
            assertFirstPhoto(2)
            capture("draft-recreated")
            }
            capture("before-export")
            click("creation-collage-export")
            capture("after-export-click")
            if (!focused) recreate() // In-flight recreation is independently covered by the small module fixture.
            withTimeout(20000) { while ((outputs() - before).isEmpty()) delay(100) }
            if (!focused) find(By.res("creation-collage-saved"))
            val fresh = outputs() - before
            assertEquals("Exactly one published copy", 1, fresh.size)
            output = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fresh.single())
            createdOutputs += output!!
            val bytes = resolver.openInputStream(output!!)!!.use { it.readBytes() }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(2048, decoded.width); assertEquals(2048, decoded.height)
            (if (focused) listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW) else listOf(Color.GREEN, Color.RED, Color.BLUE, Color.YELLOW)).forEachIndexed { index, color ->
                assertEquals(color, decoded.getPixel(index * 512 + 256, 1024))
            }
            decoded.recycle()
            val publishedHash = hash(output!!)
            File(evidence, "output.png").writeBytes(bytes)
            assertEquals(originalHashes, sources.map(::hash))
            capture("saved-recreated")
            // Real Android chooser intents; closing the chooser must preserve this result.
            click("creation-collage-share")
            assertTrue(device.wait(Until.hasObject(By.pkg(Pattern.compile("android|com\\.android\\.intentresolver"))), 10000))
            capture("share-chooser"); device.pressBack(); awaitTag("creation-collage-screen")
            if (!focused) find(By.res("creation-collage-saved"))
            click("creation-collage-open")
            assertTrue(device.wait(Until.hasObject(By.pkg(Pattern.compile("android|com\\.android\\.intentresolver"))), 10000))
            capture("open-chooser"); device.pressBack(); awaitTag("creation-collage-screen")
            if (!focused) recreate()
            if (!focused) find(By.res("creation-collage-saved"))
            assertEquals(publishedHash, hash(output!!)); assertEquals(1, (outputs() - before).size)
            if (!focused) {
            device.pressBack(); awaitTag("timeline_grid")
            click(tags[0]) // New source selection is GREEN,BLUE,YELLOW, never the old draft/result.
            openCreateSheet(); awaitTag("creation-collage-preview")
            assertTrue(find(By.res("creation-collage-template-Grid3")).isChecked)
            assertFalse(device.hasObject(By.res("creation-collage-saved")))
            assertFirstPhoto(1)
            assertEquals(originalHashes, sources.map(::hash))
            capture("fresh-second-draft")
            device.pressBack(); awaitTag("timeline_grid")
            }
            File(evidence, "result.json").writeText("""{"status":"PASS","uri":"$output","width":2048,"height":2048,"order":"${if (focused) "red,green,blue,yellow" else "green,red,blue,yellow"}","draftRecreated":${!focused},"publicationRecreated":${!focused},"savedResultRecreated":${!focused},"realOpenAndShareChoosers":true,"newDraft":${!focused},"originalsUnchanged":true}""")
        } catch (failure: Throwable) {
            capture("failure"); File(evidence, "failure.txt").writeText(failure.stackTraceToString()); throw failure
        } finally {
            try {
                val registered = createdOutputs.map(ContentUris::parseId).toSet()
                val unrecorded = outputs() - before - registered
                fun cleanupEvidence(status: String, remaining: Set<Long>) {
                    File(evidence, "cleanup.json").writeText(org.json.JSONObject()
                        .put("status", status)
                        .put("registeredOutputIds", org.json.JSONArray(registered.sorted()))
                        .put("unattributedOutputIdsRetained", org.json.JSONArray(unrecorded.sorted()))
                        .put("remainingOutputIds", org.json.JSONArray(remaining.sorted()))
                        .put("ownedSourceUris", org.json.JSONArray(sources.map(Uri::toString)))
                        .toString())
                }
                cleanupEvidence("STARTED", outputs() - before)
                // A new row in the package's output directory does not establish fixture ownership.
                // In particular, an interrupted export must not adopt an unrelated concurrent row.
                createdOutputs.distinct().forEach { resolver.delete(it, null, null) }
                sources.forEach { uri ->
                    resolver.delete(uri, null, null)
                    db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?", arrayOf<Any>("external_primary", ContentUris.parseId(uri)))
                }
                val remaining = outputs() - before
                cleanupEvidence(if (remaining.isEmpty()) "COMPLETE" else "PARTIAL_OUTPUTS_RETAINED", remaining)
            } finally { db.close() }
        }
    }
}
