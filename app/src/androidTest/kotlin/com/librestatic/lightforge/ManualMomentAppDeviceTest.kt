package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real app→VM→Room contract. Two owned JPEGs; no real video export or authentication claim. */
@Suppress("DEPRECATION")
class ManualMomentAppDeviceTest {
    @Test fun receiptRequiresExplicitConsentAndManualMemoryOpensWithReviewedOrder(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val token = "manual-moment-app-${UUID.randomUUID()}"
        val title = "Manual trip $token"
        val evidence = File(context.filesDir, token).apply { check(mkdirs()) }
        val sources = mutableListOf<Uri>()
        var createdId: String? = null
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }
        fun capture(label: String) {
            device.takeScreenshot(File(evidence, "$label.png"))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        }
        fun awaitTag(tag: String) {
            assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20_000))
        }
        // Configuration recreation only: retain the process/VM, not a process-death claim.
        suspend fun recreate(expectedScreen: String, label: String) {
            val pid = android.os.Process.myPid()
            lateinit var previous: MainActivity
            instrumentation.runOnMainSync {
                previous = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                previous.recreate()
            }
            var replacementReady = false
            withTimeout(20_000) {
                while (!replacementReady) {
                    instrumentation.runOnMainSync {
                        val current = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<MainActivity>().singleOrNull()
                        replacementReady = current != null && current !== previous && previous.isDestroyed
                    }
                    if (!replacementReady) delay(50)
                }
            }
            device.waitForIdle()
            awaitTag(expectedScreen)
            assertEquals("Activity recreation must not replace the process", pid, android.os.Process.myPid())
            File(evidence, "actions.txt").appendText("MainActivity.recreate $label: distinct RESUMED instance; prior destroyed; pid=$pid\n")
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
            val accepted = node.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            File(evidence, "actions.txt").appendText("SCROLL backward=$backward accepted=$accepted\n")
            return accepted
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            var backward = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } }
                catch (_: StaleObjectException) { }
                if (!scroll(backward)) backward = !backward
                device.waitForIdle()
            }
            error("Missing $selector")
        }
        fun action(selector: BySelector, label: String, action: Int = AccessibilityNodeInfo.ACTION_CLICK,
            arguments: Bundle? = null, matches: (AccessibilityNodeInfo) -> Boolean) {
            find(selector)
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && node.isEnabled && matches(node)) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { target(it)?.let { found -> return found } }
                    return null
                }
                var node = instrumentation.uiAutomation.rootInActiveWindow?.let(::target)
                if (action == AccessibilityNodeInfo.ACTION_CLICK) while (node != null && !node.isClickable) node = node.parent
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) while (node != null && !node.isLongClickable) node = node.parent
                if (node?.performAction(action, arguments) == true) {
                    File(evidence, "actions.txt").appendText("ACTION $action $label accepted\n")
                    device.waitForIdle(); return
                }
                android.os.SystemClock.sleep(100)
            }
            error("Action not accepted: $label")
        }
        fun click(tag: String) = action(By.res(tag), tag) { it.viewIdResourceName == tag }
        fun clickText(text: String) = action(By.text(text), text) { it.text?.toString() == text }
        fun openDraft() {
            val label = context.getString(R.string.nav_create)
            val byDescription = device.findObject(By.desc(label)) != null
            action(if (byDescription) By.desc(label) else By.text(label), "Create") {
                if (byDescription) it.contentDescription?.toString() == label else it.text?.toString() == label
            }
            click("create-memory")
            awaitTag("manual-moment-screen")
            assertFalse("Consent starts off", find(By.res("manual-moment-include-special")).isChecked)
        }
        fun manualCount(): Long = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM moments WHERE origin='MANUAL'").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        fun receipt(): Pair<String, Long> = db.openHelper.readableDatabase.query(
            "SELECT category,updatedAtMillis FROM document_annotations WHERE volumeName='external_primary' AND mediaStoreId=?",
            arrayOf(ContentUris.parseId(sources[0]))).use {
            assertTrue(it.moveToFirst()); it.getString(0) to it.getLong(1)
        }
        try {
            repeat(2) { index ->
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$token-$index.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$token/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                sources += uri
                Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(if (index == 0) 0xffba3028.toInt() else 0xff2869bd.toInt())
                    try { resolver.openOutputStream(uri, "w")!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
                    finally { bitmap.recycle() }
                }
                assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            }
            val originalHashes = sources.map(::hash)
            device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
            val grant = context.getString(com.librestatic.lightforge.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                clickText(grant)
                val allow = By.res(Pattern.compile(".*permissioncontroller:id/permission_allow(?:_all)?_button"))
                assertTrue(device.wait(Until.hasObject(allow), 10_000)); device.findObject(allow).click()
            }
            val decline = context.getString(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline)
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) clickText(decline)
            if (!device.hasObject(By.res("timeline_grid"))) clickText(context.getString(R.string.nav_photos))
            awaitTag("timeline_grid")
            withTimeout(45_000) {
                while (sources.any { db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null }) delay(100)
            }
            val ids = sources.map(ContentUris::parseId)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO document_annotations(volumeName,mediaStoreId,category,updatedAtMillis) VALUES('external_primary',?,'Receipt',?)",
                arrayOf<Any>(ids[0], 424242L))
            val originalReceipt = receipt()
            val baselineCount = manualCount()
            val tags = ids.map { "media_external_primary_$it" }
            action(By.res(tags[0]), tags[0], AccessibilityNodeInfo.ACTION_LONG_CLICK) { it.viewIdResourceName == tags[0] }
            click(tags[1])
            capture("selected-two")
            openDraft()
            click("manual-moment-cancel")
            awaitTag("timeline_grid")
            assertEquals("Cancel must not create a memory", baselineCount, manualCount())
            assertEquals(originalReceipt, receipt())

            openDraft()
            click("manual-moment-save")
            find(By.res("manual-moment-error"))
            assertEquals("Consent off must reject Receipt without a partial memory", baselineCount, manualCount())
            assertEquals(originalReceipt, receipt())
            capture("receipt-consent-required")
            action(By.res("manual-moment-title"), "Set title", AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) }) {
                it.viewIdResourceName == "manual-moment-title"
            }
            click("manual-moment-include-special")
            assertTrue(find(By.res("manual-moment-include-special")).isChecked)
            click("manual-moment-up-external_primary:${ids[1]}")
            find(By.res("manual-moment-source-external_primary:${ids[1]}")
                .hasDescendant(By.text(context.getString(com.librestatic.lightforge.feature.collections.R.string.manual_moment_photo, 1, 2))))
            capture("reviewed-order-and-consent")
            recreate("manual-moment-screen", "reviewed-draft")
            find(By.text(title))
            assertTrue("Reviewed consent survives recreation", find(By.res("manual-moment-include-special")).isChecked)
            find(By.res("manual-moment-source-external_primary:${ids[1]}")
                .hasDescendant(By.text(context.getString(com.librestatic.lightforge.feature.collections.R.string.manual_moment_photo, 1, 2))))
            find(By.res("manual-moment-source-external_primary:${ids[0]}")
                .hasDescendant(By.text(context.getString(com.librestatic.lightforge.feature.collections.R.string.manual_moment_photo, 2, 2))))
            assertTrue("Restored draft remains saveable", find(By.res("manual-moment-save")).isEnabled)
            assertEquals("Draft recreation must not persist a memory", baselineCount, manualCount())
            assertEquals(originalReceipt, receipt())
            assertEquals(originalHashes, sources.map(::hash))
            capture("reviewed-draft-recreated")
            click("manual-moment-save")
            awaitTag("moment-screen")
            assertTrue("Committed memory consumes selection toolbar", device.wait(Until.gone(
                By.text(context.getString(R.string.selection_count, 2L))), 10_000))
            find(By.text(title))
            createdId = db.openHelper.readableDatabase.query("SELECT momentId FROM moments WHERE title=? AND origin='MANUAL'", arrayOf(title)).use {
                assertTrue(it.moveToFirst()); val id = it.getString(0); assertFalse(it.moveToNext()); id
            }
            val memoryId = checkNotNull(createdId)
            assertEquals(baselineCount + 1, manualCount())
            db.openHelper.readableDatabase.query("SELECT state,includeSpecialMedia FROM moments WHERE momentId=?", arrayOf(memoryId)).use {
                assertTrue(it.moveToFirst()); assertEquals("SAVED", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            val order = db.momentDao().allMembers(memoryId).map { it.mediaStoreId }
            assertEquals(listOf(ids[1], ids[0]), order)
            assertEquals(originalReceipt, receipt())
            assertEquals(originalHashes, sources.map(::hash))
            val generations = ids.map { checkNotNull(db.libraryDao().media("external_primary", it)).generationModified }
            find(By.res("moment-slide").hasDescendant(By.res("moment-image-loaded-external_primary:${ids[1]}-${generations[1]}")))
            click("moment-next")
            find(By.res("moment-slide").hasDescendant(By.res("moment-image-loaded-external_primary:${ids[0]}-${generations[0]}")))
            capture("saved-real-memory")
            recreate("moment-screen", "saved-memory")
            find(By.text(title))
            find(By.res("moment-slide").hasDescendant(By.res("moment-image-loaded-external_primary:${ids[0]}-${generations[0]}")))
            assertFalse("Saved route must not reopen the draft", device.hasObject(By.res("manual-moment-screen")))
            assertEquals("Recreation must not create a duplicate", baselineCount + 1, manualCount())
            db.openHelper.readableDatabase.query(
                "SELECT momentId,title,state,includeSpecialMedia FROM moments WHERE title=? AND origin='MANUAL'", arrayOf(title)
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(memoryId, it.getString(0))
                assertEquals(title, it.getString(1))
                assertEquals("SAVED", it.getString(2))
                assertEquals(1, it.getInt(3))
                assertFalse("Exactly one saved result for this draft", it.moveToNext())
            }
            assertEquals(listOf(ids[1], ids[0]), db.momentDao().allMembers(memoryId).map { it.mediaStoreId })
            assertEquals(originalReceipt, receipt())
            assertEquals(originalHashes, sources.map(::hash))
            assertFalse("Saved result keeps selection consumed", device.hasObject(By.text(context.getString(R.string.selection_count, 2L))))
            capture("saved-memory-recreated")
            // Native ordered slideshow preparation only; encoding has separate accepted evidence.
            click("moment-make-video")
            awaitTag("memory-video-screen")
            find(By.text(title))
            find(By.text(context.getString(com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_position, 1, 2)))
            capture("video-handoff-two")
            device.pressBack()
            awaitTag("moment-screen")
            assertEquals(listOf(ids[1], ids[0]), db.momentDao().allMembers(memoryId).map { it.mediaStoreId })
            assertEquals(originalReceipt, receipt())
            assertEquals(originalHashes, sources.map(::hash))
            File(evidence, "result.json").writeText("""{"status":"PASS","cancelNoRow":true,"falseRejected":true,"trueCommitted":true,"receiptUnchanged":true,"orderReversed":true,"reviewedDraftRecreated":true,"savedMemoryRecreated":true,"sameProcessNewActivity":true,"noDuplicateMemory":true,"selectedSlideRetained":true,"processDeathTested":false,"videoHandoffCount":2,"videoEncoded":false,"originalHashes":[${originalHashes.joinToString(",") { "\"$it\"" }}]}""")
        } catch (failure: Throwable) {
            capture("failure")
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            // Only IDs/URIs owned by this UUID fixture are removed; no application data reset.
            createdId?.let { db.openHelper.writableDatabase.execSQL("DELETE FROM moments WHERE momentId=?", arrayOf(it)) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM moments WHERE title=? AND origin='MANUAL'", arrayOf(title))
            sources.forEach { uri ->
                resolver.delete(uri, null, null)
                db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE volumeName='external_primary' AND mediaStoreId=?",
                    arrayOf<Any>(ContentUris.parseId(uri)))
            }
            db.close()
        }
    }
}
