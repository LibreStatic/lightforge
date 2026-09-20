package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.database.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** One real app→Room contract. Fixtures contain no face/identity data and own only two JPEGs. */
@Suppress("DEPRECATION")
class MomentParticipantsAppDeviceTest {
    @Test fun manualEmptyPersistsFromRealMemoryRouteWithoutChangingStoryOrPhotos(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val id = "moment-participants-app-${UUID.randomUUID()}"
        val title = "Participants ${UUID.randomUUID()}"
        val evidence = File(context.filesDir, id).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        val resolver = context.contentResolver
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        fun capture(label: String) {
            device.takeScreenshot(File(evidence, "$label.png"))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
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
            }
            val bounds = Rect(); node?.getBoundsInScreen(bounds)
            val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val accepted = node?.performAction(action) == true
            File(evidence, "actions.txt").appendText("ACTION_SCROLL_${if (backward) "BACKWARD" else "FORWARD"} accepted=$accepted resource=${node?.viewIdResourceName} bounds=$bounds\n")
            return accepted
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            var backward = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } } catch (_: StaleObjectException) {}
                if (!scroll(backward)) backward = !backward
                device.waitForIdle()
            }
            error("Missing participant app control $selector")
        }
        fun clickNode(selector: BySelector, label: String, matches: (AccessibilityNodeInfo) -> Boolean) {
            find(selector)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && node.isEnabled && matches(node)) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { target(it)?.let { found -> return found } }
                    return null
                }
                var node = instrumentation.uiAutomation.rootInActiveWindow?.let(::target)
                while (node != null && !node.isClickable) node = node.parent
                val accepted = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                File(evidence, "actions.txt").appendText("ACTION_CLICK $label accepted=$accepted\n")
                if (accepted) { device.waitForIdle(); return }
                android.os.SystemClock.sleep(100)
            }
            error("Click not accepted: $label")
        }
        fun click(tag: String) = clickNode(By.res(tag).enabled(true), tag) { it.viewIdResourceName == tag }
        fun clickText(text: String) = clickNode(By.text(text), text) { it.text?.toString() == text }
        fun state(): Pair<String, Long>? = db.openHelper.readableDatabase.query(
            "SELECT mode,revision FROM moment_participant_state WHERE momentId=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        }
        fun selected(): List<String> = db.openHelper.readableDatabase.query(
            "SELECT clusterId FROM moment_participants WHERE momentId=? ORDER BY clusterId", arrayOf(id)).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        fun cover(): List<String> = db.openHelper.readableDatabase.query(
            "SELECT volumeName,mediaStoreId,isUserSelected FROM moment_covers WHERE momentId=?", arrayOf(id)).use {
            if (it.moveToFirst()) listOf(it.getString(0), it.getLong(1).toString(), it.getInt(2).toString()) else emptyList()
        }
        try {
            repeat(2) { index ->
                val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$id-$index.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$id/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                sources += uri
                Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.rgb(40 + index * 70, 100, 160))
                    try { resolver.openOutputStream(uri, "w")!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
                    finally { bitmap.recycle() }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                clickText(grant)
                val allow = By.res(Pattern.compile(".*permissioncontroller:id/permission_allow(?:_all)?_button"))
                assertTrue(device.wait(Until.hasObject(allow), 10000)); click(device.findObject(allow).resourceName ?: error("Missing permission action resource"))
            }
            val decline = context.getString(com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline)
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) clickText(decline)
            withTimeout(30000) { while (sources.any { db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null }) delay(100) }
            val rows = sources.map { db.libraryDao().media("external_primary", ContentUris.parseId(it))!! }
            val originalHashes = sources.map(::hash)
            val now = System.currentTimeMillis()
            db.momentDao().upsertMoment(MomentEntity(id, "USER", "SAVED", "fixture", rows.minOf { it.timelineSortMillis },
                rows.maxOf { it.timelineSortMillis }, title, "USER", true, now, now))
            db.momentDao().insertMembers(rows.mapIndexed { index, row -> MomentMemberEntity(id, index,
                row.volumeName, row.mediaStoreId, row.generationModified, "USER", 1f) })
            db.momentDao().upsertCover(MomentCoverEntity(id, rows.last().volumeName, rows.last().mediaStoreId, true))
            val originalMembers = db.momentDao().allMembers(id)
            val originalCover = cover()
            clickText(context.getString(R.string.nav_collections))
            capture("collections-before-find")
            try { find(By.text(title)) } finally { capture("collections-after-find") }
            clickText(title)
            click("moment-participants-edit")
            find(By.res("moment-participants-screen"))
            click("moment-participants-manual")
            click("moment-participants-clear")
            click("moment-participants-apply")
            find(By.res("moment-participants-edit"))
            withTimeout(10000) { while (state() != ("MANUAL" to 1L)) delay(50) }
            assertTrue(selected().isEmpty())
            assertEquals(title, db.momentDao().moment(id)!!.title)
            assertEquals(originalMembers, db.momentDao().allMembers(id))
            assertEquals(originalCover, cover())
            assertEquals(originalHashes, sources.map(::hash))
            click("moment-participants-edit")
            val manual = find(By.res("moment-participants-manual"))
            assertTrue("Manual mode retained", manual.isSelected || manual.isChecked)
            find(By.text(context.getString(com.ugallery.feature.collections.R.string.moment_participants_count, 0)))
            click("moment-participants-cancel")
            find(By.res("moment-participants-edit"))
            assertEquals("Cancel performs no participant write", "MANUAL" to 1L, state())
            assertTrue(selected().isEmpty())
            assertEquals(title, db.momentDao().moment(id)!!.title)
            assertEquals(originalMembers, db.momentDao().allMembers(id))
            assertEquals(originalCover, cover())
            assertEquals(originalHashes, sources.map(::hash))
            File(evidence, "result.json").writeText("""{"status":"PASS","manualEmpty":true,"revision":1,"cancelNoWrite":true,"titleCoverOrderUnchanged":true,"sourceHashesUnchanged":true}""")
        } catch (failure: Throwable) {
            capture("failure")
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            db.momentDao().delete(id)
            sources.forEach { resolver.delete(it, null, null); db.libraryDao().deleteMedia("external_primary", ContentUris.parseId(it)) }
            db.close()
        }
    }
}
