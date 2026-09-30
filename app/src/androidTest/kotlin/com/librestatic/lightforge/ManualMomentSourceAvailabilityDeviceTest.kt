package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.model.MediaKey
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Photos → manual draft → missing MediaStore source → recoverable error.
 * A second Room connection holds a write transaction without mutations while Save is pressed:
 * index invalidation/commit cannot explain rejection, and the prepared indexed row remains valid.
 * This deletes an exact UUID-owned fixture URI; it does not test permission revocation/process death.
 */
@Suppress("DEPRECATION")
class ManualMomentSourceAvailabilityDeviceTest {
    @Test fun missingProviderSourceRejectsBeforeRoomCommitAndLeavesDraftRecoverable(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val recovery = InstrumentationRegistry.getArguments().getString("manualSourceCleanup")
        if (recovery != null) {
            recoverOwnedFixture(context, recovery)
            return@runBlocking
        }
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val token = "manual-source-unavailable-${UUID.randomUUID()}"
        val title = "Missing source $token"
        val evidence = File(context.filesDir, token).apply { check(mkdirs()) }
        val sources = mutableListOf<Uri>()
        var draftId: String? = null
        var lockJob: Job? = null
        val releaseWriter = CompletableDeferred<Unit>()
        val writerHeld = AtomicBoolean(false)
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
        fun count(table: String, id: String): Long {
            check(table in setOf("moments", "moment_members", "moment_covers"))
            return db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM $table WHERE momentId=?", arrayOf(id)
            ).use { check(it.moveToFirst()); it.getLong(0) }
        }
        fun assertNoAttributedRows(id: String) {
            for (table in listOf("moments", "moment_members", "moment_covers"))
                assertEquals("No partial $table for this draft", 0L, count(table, id))
        }
        fun indexedSnapshot(id: Long): List<Long> = db.openHelper.readableDatabase.query(
            "SELECT generationAdded,generationModified,isAccessible,isTrashed,mediaType FROM media_items " +
                "WHERE volumeName='external_primary' AND mediaStoreId=?", arrayOf(id)
        ).use {
            assertTrue("Prepared row must remain present in the stale Room index", it.moveToFirst())
            (0..4).map(it::getLong)
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
            val hashes = sources.map(::hash)
            val ids = sources.map(ContentUris::parseId)
            File(evidence, "owned.json").writeText(JSONObject().put("token", token)
                .put("sourceUris", JSONArray(sources.map(Uri::toString)))
                .put("originalHashes", JSONArray(hashes)).toString())
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
                while (ids.any { db.libraryDao().media("external_primary", it) == null }) delay(100)
            }
            val tags = ids.map { "media_external_primary_$it" }
            action(By.res(tags[0]), tags[0], AccessibilityNodeInfo.ACTION_LONG_CLICK) { it.viewIdResourceName == tags[0] }
            click(tags[1])
            openDraft()
            action(By.res("manual-moment-title"), "Set title", AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) }) {
                it.viewIdResourceName == "manual-moment-title"
            }
            lateinit var viewModel: GalleryViewModel
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                viewModel = ViewModelProvider(activity)[GalleryViewModel::class.java]
            }
            val session = checkNotNull(viewModel.manualMomentSession.value)
            draftId = session.draft.id
            assertEquals(ids, session.draft.sources.map { it.key.mediaStoreId })
            assertFalse(viewModel.manualMomentBusy.value)
            assertFalse(viewModel.manualMomentError.value)
            val originalIndex = indexedSnapshot(ids[0])
            assertEquals(listOf(1L, 0L, 1L), originalIndex.drop(2))
            assertEquals(session.draft.sources[0].generationAdded, originalIndex[0])
            assertEquals(session.draft.sources[0].generationModified, originalIndex[1])
            assertNoAttributedRows(session.draft.id)
            db.openHelper.readableDatabase.query("PRAGMA journal_mode").use {
                check(it.moveToFirst())
                assertEquals("Concurrent stale-index read requires WAL", "wal", it.getString(0).lowercase())
            }
            val writerReady = CompletableDeferred<Unit>()
            lockJob = launch(Dispatchers.IO) {
                db.withTransaction {
                    writerHeld.set(true)
                    try {
                        writerReady.complete(Unit)
                        withTimeout(90_000) { releaseWriter.await() }
                    } finally { writerHeld.set(false) }
                }
            }
            withTimeout(20_000) { writerReady.await() }
            assertTrue(writerHeld.get())
            // The OS source disappears, but no DB writer can reconcile this stale index yet.
            assertEquals(1, resolver.delete(sources[0], null, null))
            val missing = MediaKey("external_primary", ids[0])
            assertNull("Actual MediaStore reader must observe absence", MediaStoreReader(resolver).readOne(missing))
            assertEquals(originalIndex, indexedSnapshot(ids[0]))
            assertEquals(hashes[1], hash(sources[1]))
            capture("missing-provider-stale-index")
            click("manual-moment-save")
            find(By.res("manual-moment-error"))
            withTimeout(10_000) {
                while (viewModel.manualMomentBusy.value || !viewModel.manualMomentError.value) delay(50)
            }
            // createManualMoment returns early without error if session/runtime is absent. Here
            // the real callback emitted error while its later Room write remains excluded.
            assertTrue("Error must arrive before allowing any Room commit/reconciliation", writerHeld.get())
            assertSame(session, viewModel.manualMomentSession.value)
            assertEquals(originalIndex, indexedSnapshot(ids[0]))
            assertNoAttributedRows(session.draft.id)
            assertTrue(find(By.res("manual-moment-save")).isEnabled)
            assertTrue(find(By.res("manual-moment-cancel")).isEnabled)
            assertTrue(find(By.res("manual-moment-title")).isEnabled)
            find(By.text(title))
            assertEquals(hashes[1], hash(sources[1]))
            capture("recoverable-error-before-room-write")
            releaseWriter.complete(Unit)
            withTimeout(20_000) { lockJob?.join() }
            assertFalse(writerHeld.get())
            // User recovery is an explicit Cancel, not a silently committed reduced memory.
            click("manual-moment-cancel")
            awaitTag("timeline_grid")
            assertNull(viewModel.manualMomentSession.value)
            assertNoAttributedRows(session.draft.id)
            assertEquals(hashes[1], hash(sources[1]))
            File(evidence, "result.json").writeText(JSONObject().put("status", "PASS")
                .put("draftId", session.draft.id).put("providerSourceAbsent", true)
                .put("indexedSourceUnchangedDuringRejection", true).put("writerHeldDuringError", true)
                .put("partialRows", 0).put("errorRecoverable", true).put("cancelReturnedToPhotos", true)
                .put("remainingSourceHash", hashes[1]).put("permissionRevocationTested", false)
                .put("processDeathTested", false).toString())
        } catch (failure: Throwable) {
            runCatching { capture("failure") }
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            withContext(NonCancellable) {
                releaseWriter.complete(Unit)
                withTimeout(20_000) { lockJob?.join() }
                try {
                    File(evidence, "cleanup.json").writeText(JSONObject().put("status", "STARTED")
                        .put("sourceUris", JSONArray(sources.map(Uri::toString)))
                        .put("draftId", draftId).toString())
                    draftId?.let {
                        db.openHelper.writableDatabase.execSQL("DELETE FROM moments WHERE momentId=? AND origin='MANUAL'", arrayOf(it))
                    }
                    // Every deleted URI/row was created by this invocation; never discover/adopt
                    // unrelated output IDs, delete global memories, or reset app/media permissions.
                    sources.forEachIndexed { index, uri ->
                        val key = MediaKey("external_primary", ContentUris.parseId(uri))
                        // Android 30 may deny deletion of an already-absent URI; absence is not an error.
                        if (MediaStoreReader(resolver).readOne(key) != null) {
                            val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.GENERATION_ADDED,
                                MediaStore.MediaColumns.GENERATION_MODIFIED)
                            val snapshot = resolver.query(uri, columns, null, null, null)!!.use {
                                check(it.moveToFirst())
                                (0..4).map(it::getString)
                            }
                            check(snapshot.take(3) == listOf("$token-$index.jpg", "Pictures/$token/", context.packageName))
                            check(resolver.delete(uri, columns.joinToString(" AND ") { "$it=?" }, snapshot.toTypedArray()) == 1)
                        }
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM media_items WHERE volumeName='external_primary' AND mediaStoreId=?",
                            arrayOf<Any>(ContentUris.parseId(uri)))
                    }
                    sources.forEach { uri ->
                        assertNull("Owned source removed during cleanup", MediaStoreReader(resolver).readOne(
                            MediaKey("external_primary", ContentUris.parseId(uri))))
                        db.openHelper.readableDatabase.query(
                            "SELECT COUNT(*) FROM media_items WHERE volumeName='external_primary' AND mediaStoreId=?",
                            arrayOf(ContentUris.parseId(uri))
                        ).use { check(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
                    }
                    draftId?.let(::assertNoAttributedRows)
                    File(evidence, "cleanup.json").writeText(JSONObject().put("status", "COMPLETE")
                        .put("sourceUris", JSONArray(sources.map(Uri::toString)))
                        .put("draftId", draftId).toString())
                } finally { db.close() }
            }
        }
    }
    /** Host-only recovery of a completed invocation whose cleanup was interrupted. */
    private fun recoverOwnedFixture(context: android.content.Context, name: String) {
        val prefix = "manual-source-unavailable-"
        check(name.startsWith(prefix) && prefix + UUID.fromString(name.removePrefix(prefix)).toString() == name)
        val directory = File(context.filesDir, name)
        check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val owned = JSONObject(File(directory, "owned.json").readText())
        val result = JSONObject(File(directory, "result.json").readText())
        check(owned.getString("token") == name && result.getString("status") == "PASS")
        val uris = owned.getJSONArray("sourceUris")
        val hashes = owned.getJSONArray("originalHashes")
        check(uris.length() == 2 && hashes.length() == 2)
        val resolver = context.contentResolver
        val database = GalleryDatabaseFactory.open(context)
        try {
            val id = result.getString("draftId")
            for (table in listOf("moments", "moment_members", "moment_covers")) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE momentId=?", arrayOf(id)).use {
                    check(it.moveToFirst() && it.getLong(0) == 0L)
                }
            }
            for (index in 0..1) {
                val uri = Uri.parse(uris.getString(index))
                check(uri.scheme == "content" && uri.authority == "media" &&
                    uri.pathSegments.take(3) == listOf("external_primary", "images", "media") && uri.pathSegments.size == 4)
                val key = MediaKey("external_primary", ContentUris.parseId(uri))
                if (MediaStoreReader(resolver).readOne(key) != null) {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use {
                        check(it.moveToFirst() && it.getString(0) == "$name-$index.jpg" &&
                            it.getString(1) == "Pictures/$name/" && it.getString(2) == context.packageName)
                    }
                    val hash = resolver.openInputStream(uri)!!.use { stream ->
                        MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") { "%02x".format(it) }
                    }
                    check(hash == hashes.getString(index))
                    check(resolver.delete(uri, null, null) == 1)
                }
                check(MediaStoreReader(resolver).readOne(key) == null)
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?", arrayOf<Any>(key.volumeName, key.mediaStoreId))
            }
        } finally { database.close() }
        File(directory, "cleanup.json").writeText(JSONObject().put("status", "COMPLETE_RECOVERED")
            .put("sourceUris", uris).put("draftId", result.getString("draftId")).toString())
        println("OWNED CLEANUP RECOVERED $name")
    }

}
