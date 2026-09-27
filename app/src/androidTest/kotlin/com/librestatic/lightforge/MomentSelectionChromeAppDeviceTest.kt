package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowInsets
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.data.ManualMomentRepository
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.selection.SelectionSpec
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The real committed-request entry retains a library selection while opening a memory.
 * No process death, private navigation mutation, or destructive UI action is involved.
 */
@Suppress("DEPRECATION")
class MomentSelectionChromeAppDeviceTest {
    @Test fun recoveredMomentOwnsTopBarWithoutLosingLibrarySelection(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val resolver = context.contentResolver
        val store = ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending"))
        // Reject another user's recovery before even creating our own two sources.
        check(store.read() == null) { "A pre-existing manual-memory request must be retained" }
        val db = GalleryDatabaseFactory.open(context)
        val fixture = "moment-chrome-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, fixture).apply { check(mkdirs()) }
        val sources = mutableListOf<Uri>()
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        fun metadata(uri: Uri): List<String>? = resolver.query(uri, columns, null, null, null)!!.use { cursor ->
            if (!cursor.moveToFirst()) null else columns.map { cursor.getString(cursor.getColumnIndexOrThrow(it)) }.also {
                check(!cursor.moveToNext())
            }
        }
        fun hash(uri: Uri): String = resolver.openInputStream(uri)!!.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= 1024 * 1024) { "Owned PNG exceeded its fixture bound" }
                digest.update(buffer, 0, count)
            }
            check(total > 0)
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun capture(label: String) {
            check(device.takeScreenshot(File(evidence, "$label.png")))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        }
        fun awaitTag(tag: String) {
            assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20000))
        }
        fun find(selector: BySelector, direction: Direction = Direction.UP): UiObject2 {
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                    device.findObjects(By.scrollable(true)).maxByOrNull {
                        it.visibleBounds.width() * it.visibleBounds.height()
                    }?.scroll(direction, 0.7f)
                } catch (_: StaleObjectException) { }
                SystemClock.sleep(100)
            }
            error("Missing visible $selector")
        }
        fun cover(id: String): List<String>? = db.openHelper.readableDatabase.query(
            "SELECT momentId,volumeName,mediaStoreId,isUserSelected FROM moment_covers WHERE momentId=?",
            arrayOf<Any>(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else (0 until cursor.columnCount).map(cursor::getString).also {
                check(!cursor.moveToNext())
            }
        }
        var momentId: String? = null
        var ownRequest: ManualMomentCreateRequest? = null
        var complete = false
        try {
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$fixture-$index.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$fixture/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                sources += uri
                // Preserve exact created URI even if setup fails before publication/hash capture.
                File(evidence, "owned-uris.txt").appendText("$uri\n")
                val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                assertEquals(1, resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null))
            }
            val sourceMetadata = sources.map { checkNotNull(metadata(it)) }
            val sourceHashes = sources.map(::hash)
            sourceMetadata.forEachIndexed { index, row ->
                assertEquals(context.packageName, row[1])
                assertEquals("$fixture-$index.png", row[2])
                assertEquals("Pictures/$fixture/", row[3])
                assertEquals("0", row[6]); assertEquals("0", row[7])
            }
            File(evidence, "source-baseline.json").writeText(JSONObject().put("fixture", fixture)
                .put("columns", JSONArray(columns.toList())).put("sources", JSONArray(sources.indices.map { index ->
                    JSONObject().put("uri", sources[index].toString()).put("sha256", sourceHashes[index])
                        .put("metadata", JSONArray(sourceMetadata[index]))
                })).toString(2))
            device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
            val grant = context.getString(com.librestatic.lightforge.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                find(By.text(grant)).click()
                val allow = By.res(Pattern.compile(".*permissioncontroller:id/permission_allow(?:_all)?_button"))
                assertTrue(device.wait(Until.hasObject(allow), 10000)); find(allow).click()
            }
            val decline = context.getString(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline)
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) find(By.text(decline)).click()
            lateinit var activity: MainActivity
            lateinit var vm: GalleryViewModel
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                vm = ViewModelProvider(activity)[GalleryViewModel::class.java]
                check(vm.selectionCount.value == 0L) { "Pre-existing selection must not be replaced" }
            }
            // selectRoot deliberately clears selection; reject unrelated selection BEFORE navigating.
            if (!device.hasObject(By.res("timeline_grid"))) find(By.text(context.getString(R.string.nav_photos))).click()
            awaitTag("timeline_grid")
            val keys = sources.map { MediaKey("external_primary", ContentUris.parseId(it)) }
            withTimeout(45000) {
                while (keys.any { db.libraryDao().media(it.volumeName, it.mediaStoreId) == null }) delay(100)
            }
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                check(resumed === activity && ViewModelProvider(resumed)[GalleryViewModel::class.java] === vm)
                check(vm.selectionCount.value == 0L) { "Selection changed while awaiting our indexed sources" }
            }
            val tags = keys.map { "media_${it.volumeName}_${it.mediaStoreId}" }
            find(By.res(tags[0])).longClick()
            assertTrue(device.wait(Until.hasObject(By.res(tags[0]).checked(true)), 5000))
            find(By.res(tags[1])).click()
            assertTrue(device.wait(Until.hasObject(By.res(tags[1]).checked(true)), 5000))
            val selectedLabel = context.getString(R.string.selection_count, 2)
            assertTrue(device.wait(Until.hasObject(By.text(selectedLabel)), 10000))
            fun assertKeys() {
                assertEquals(keys.toSet(), (vm.selection.value as SelectionSpec.Explicit).keys)
                assertEquals(2L, vm.selectionCount.value)
            }
            assertKeys()
            capture("photos-selected-two")

            val repo = ManualMomentRepository(db)
            val draft = repo.prepare(keys)
            val request = ManualMomentCreateRequest.capture(draft, fixture, keys, false)
            ownRequest = request
            momentId = draft.id
            check(store.read() == null) { "Another request appeared during fixture setup" }
            // Real durable intent + real Room commit; deliberately leave UI ACK to the real recovery entry.
            store.put(request)
            assertEquals(draft.id, repo.create(draft, request.orderedKeys, request.title, request.includeSpecialMedia))
            val momentBaseline = checkNotNull(db.momentDao().moment(draft.id))
            val membersBaseline = db.momentDao().allMembers(draft.id)
            val coverBaseline = checkNotNull(cover(draft.id))
            assertEquals(2, membersBaseline.size)
            File(evidence, "moment-baseline.txt").writeText("request=$request\nmoment=$momentBaseline\nmembers=$membersBaseline\ncover=$coverBaseline\n")
            instrumentation.runOnMainSync { vm.checkManualMomentRecovery() }
            awaitTag("manual-memory-recovery-open")
            find(By.res("manual-memory-recovery-open")).click()
            awaitTag("moment-screen")
            assertTrue(device.wait(Until.hasObject(By.text(fixture)), 10000))
            // Wait out AnimatedVisibility; otherwise its departing toolbar can be mistaken for a failure.
            assertTrue("Library selection chrome leaked into Moment",
                device.wait(Until.gone(By.text(selectedLabel)), 5000))
            assertKeys()
            listOf(R.string.selection_trash, R.string.selection_share, R.string.selection_favorite,
                R.string.selection_add_album).forEach { resource ->
                assertFalse("Library action leaked: ${context.getString(resource)}",
                    device.hasObject(By.desc(context.getString(resource))))
            }
            assertFalse(device.hasObject(By.text(context.getString(R.string.selection_delete))))
            var statusBottom = -1
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                val offset = IntArray(2); decor.getLocationOnScreen(offset)
                statusBottom = offset[1] + checkNotNull(decor.rootWindowInsets)
                    .getInsets(WindowInsets.Type.statusBars()).top
            }
            assertTrue("Requires a real nonzero status inset", statusBottom > 0)
            val titleBounds = find(By.text(fixture)).visibleBounds
            val backBounds = find(By.desc(context.getString(com.librestatic.lightforge.feature.collections.R.string.memory_back))).visibleBounds
            assertTrue("Moment title overlaps status bar: $titleBounds / $statusBottom", titleBounds.top >= statusBottom)
            assertTrue("Moment Back overlaps status bar: $backBounds / $statusBottom", backBounds.top >= statusBottom)
            File(evidence, "insets.txt").writeText("statusBottom=$statusBottom\ntitle=$titleBounds\nback=$backBounds\n")
            capture("moment-own-chrome")
            withTimeout(10000) { while (store.read() != null) delay(100) }
            assertEquals(momentBaseline, db.momentDao().moment(draft.id))
            assertEquals(membersBaseline, db.momentDao().allMembers(draft.id))
            assertEquals(coverBaseline, cover(draft.id))

            // Committed recovery's real Back chain is Moment -> MemoriesBrowser -> Collections.
            device.pressBack(); awaitTag("memories-browser-screen"); assertKeys()
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.res("memories-browser-screen")), 10000))
            // A positive Collections-only anchor proves the destination, not merely a departing browser.
            find(By.res("collections-all-memories"), Direction.DOWN)
            assertTrue(device.wait(Until.hasObject(By.text(selectedLabel)), 10000))
            assertTrue(device.wait(Until.hasObject(By.desc(context.getString(R.string.selection_trash))), 5000))
            assertKeys()
            capture("collections-selection-retained")
            assertEquals(sourceMetadata, sources.map { metadata(it) })
            assertEquals(sourceHashes, sources.map(::hash))
            assertNull(store.read()) // Actual display acknowledged only our durable intent.
            // All test assertions passed. Stop referring to our rows before exact fixture cleanup.
            instrumentation.runOnMainSync { vm.clearSelection() }
            db.withTransaction {
                check(db.momentDao().moment(draft.id) == momentBaseline)
                check(db.momentDao().allMembers(draft.id) == membersBaseline)
                check(cover(draft.id) == coverBaseline)
                check(db.momentDao().delete(draft.id) == 1)
                check(db.momentDao().moment(draft.id) == null)
                check(db.momentDao().allMembers(draft.id).isEmpty() && cover(draft.id) == null)
            }
            sources.forEachIndexed { index, uri ->
                check(metadata(uri) == sourceMetadata[index] && hash(uri) == sourceHashes[index])
                val where = columns.joinToString(" AND ") { "$it=?" }
                check(resolver.delete(uri, where, sourceMetadata[index].toTypedArray()) == 1)
                check(metadata(uri) == null)
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=? AND generationAdded=? AND generationModified=?",
                    arrayOf<Any>(keys[index].volumeName, keys[index].mediaStoreId,
                        sourceMetadata[index][4].toLong(), sourceMetadata[index][5].toLong()),
                )
                check(db.libraryDao().media(keys[index].volumeName, keys[index].mediaStoreId) == null)
                File(evidence, "cleanup.txt").appendText("CAS deleted and absence verified $uri\n")
            }
            complete = true
            File(evidence, "result.json").writeText(JSONObject().put("status", "PASS")
                .put("fixture", fixture).put("momentId", draft.id).put("selectionCount", 2)
                .put("backRoute", "MemoriesBrowser -> Collections").put("statusBottom", statusBottom)
                .put("sourceHashes", JSONArray(sourceHashes)).put("cleanupComplete", true)
                .put("processDeath", false).toString(2))
        } catch (failure: Throwable) {
            runCatching { capture("failure") }
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            if (!complete) {
                // No fallback deletion/adoption. A partial setup or failed assertion retains exact ownership evidence.
                val retained = "fixture=$fixture momentId=$momentId token=${ownRequest?.token} uris=$sources"
                File(evidence, "retained.txt").writeText(retained)
                android.util.Log.e("MOMENT_CHROME_FIXTURE", retained)
            }
            db.close()
        }
    }
}
