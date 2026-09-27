package com.ugallery.feature.pdfstudio

import android.app.UiAutomation
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android events; accessibility is read-only observation and existing services stay enabled. */
class PdfKeyboardPointerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test
    fun stylusResizeAndKeyboardNudgeUndoRedoPersistOnExpandedCanvas(): Unit = runBlocking {
        check(context.packageName == "com.ugallery.feature.pdfstudio.test")
        // Refuse to migrate a retained fixture database merely by starting this input test.
        val existingDatabase = context.getDatabasePath("pdf-projects.db")
        if (existingDatabase.exists()) SQLiteDatabase.openDatabase(existingDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use {
            check(it.version == 8) { "Existing PDF database requires separate migration acceptance" }
        }
        val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid"))
        require(UUID.fromString(uuid).toString() == uuid)
        val id = "input-$uuid"
        val directory = File(context.filesDir, "pdf-input-$uuid")
        check(!directory.exists() && directory.mkdir())
        val source = File(directory, "source.png")
        val receipt = File(directory, "receipt.json")
        val evidence = JSONObject().put("uuid", uuid).put("projectId", id)
            .put("database", "pdf-projects.db (real module singleton, not isolated)")
        fun record(phase: String) {
            evidence.put("phase", phase)
            FileOutputStream(receipt).use { it.write(evidence.toString(2).toByteArray()); it.fd.sync() }
            check(JSONObject(receipt.readText()).getString("phase") == phase)
        }
        val db = PdfProjectDatabase.get(context)
        val repo = PdfProjectRepository(context)
        // Constructors only create their fixed directories; reconcile starts with the real VM later.
        PdfExportQueue(context)
        val root = File(context.filesDir, "pdf-studio")
        val assets = File(root, "assets")
        val tables = withContext(Dispatchers.IO) { tableInventory(db) }
        val files = withContext(Dispatchers.IO) { fileInventory(root) }
        check(db.projects().get(id) == null)
        // Fail before VM/import can reconcile or sweep anything belonging to an earlier attempt.
        for (table in listOf("export_jobs", "destination_grants", "import_deliveries", "gallery_deliveries"))
            check(tables.getValue(table).isEmpty()) { "Existing $table requires its own recovery first" }
        check(root.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("import-") })
        evidence.put("beforeTables", JSONObject(tables)).put("beforeFiles", JSONObject(files))
        record("admitted-before-fixture")
        var scenario: ActivityScenario<PdfInputProbeActivity>? = null
        var fixtureHash: String? = null
        var originalHash: String? = null
        var primaryFailure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                try {
                    val seed = uuid.hashCode()
                    for (y in 0 until 64) for (x in 0 until 96)
                        bitmap.setPixel(x, y, 0xff000000.toInt() or ((seed + x * 1709 + y * 7919) and 0xffffff))
                    FileOutputStream(source).use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync()
                    }
                } finally { bitmap.recycle() }
                originalHash = PdfProjectRepository.sha256(source)
            }
            fixtureHash = requireNotNull(originalHash) // Known content address before a cancellable import returns.
            evidence.put("originalSha256", originalHash)
            record("source-created")
            val imported = repo.import(PdfProject(id = id, name = "Input $uuid"), listOf(Uri.fromFile(source)), 0) { _, _ -> }
            fixtureHash = imported.assets.single().hash
            assertEquals(originalHash, fixtureHash)
            // Initial arrangement only: leave space for real pointer resize and keyboard displacement.
            val image = imported.pages.single().images.single().copy(x = 30.0, y = 40.0, width = 100.0, height = 90.0)
            repo.save(imported.copy(snap = false, pages = listOf(imported.pages.single().copy(images = listOf(image)))))
            evidence.put("assetSha256", fixtureHash)
            record("fixture-ready")
            val launched = ActivityScenario.launch<PdfInputProbeActivity>(
                Intent(context, PdfInputProbeActivity::class.java).putExtra("fixtureUuid", uuid),
            )
            scenario = launched
            waitFor { state(launched).project?.id == id && !state(launched).busy }
            val activity = arrayOfNulls<PdfInputProbeActivity>(1)
            launched.onActivity { activity[0] = it }
            val originalActivity = requireNotNull(activity[0])
            waitFor { originalActivity.viewportDensity > 0 && !originalActivity.viewportBounds.isEmpty }
            launched.onActivity {
                val width = it.viewportBounds.width() / it.viewportDensity
                val height = it.viewportBounds.height() / it.viewportDensity
                assertEquals(840f, width, .01f); assertEquals(640f, height, .01f)
                assertTrue(PdfStudioLayoutPolicy.forSize(width, height, 1f).expanded)
                evidence.put("viewportBounds", it.viewportBounds.flattenToString()).put("viewportDensity", it.viewportDensity)
                    .put("viewportWidthDp", width).put("viewportHeightDp", height).put("expanded", true)
            }
            val originalTask = originalActivity.taskId
            val originalPid = android.os.Process.myPid()
            val imageLabel = originalActivity.getString(R.string.pdf_image_label, 1)
            val resizeLabel = originalActivity.getString(R.string.pdf_resize_label, 1)
            // The top bar's Undo action is always visible/focusable once there is undo history;
            // the removed manual Save button no longer exists (autosave covers persistence, and
            // the top bar subtitle/state.saveState report it) so this now exercises real Tab
            // traversal reaching an actionable control instead of activating a Save button.
            val undoLabel = originalActivity.getString(R.string.pdf_undo)
            // Pointer selection itself must expose the resize handle; no fixture-side selectImage.
            var imageBounds = findBounds(imageLabel)
            stylus(imageBounds.centerX().toFloat(), imageBounds.centerY().toFloat())
            val handle = findBounds(resizeLabel)
            evidence.put("initialImageBounds", imageBounds.flattenToString()).put("initialHandleBounds", handle.flattenToString())
                .put("scaledTouchSlop", android.view.ViewConfiguration.get(originalActivity).scaledTouchSlop)
            record("before-stylus-resize")
            stylus(handle.centerX().toFloat(), handle.centerY().toFloat(),
                handle.centerX() - handle.width() * .6f, handle.centerY() - handle.height() * .6f)
            waitFor {
                val actual = state(launched).project?.pages?.single()?.images?.single()
                actual != null && actual.width < image.width - 1 && actual.height < image.height - 1
            }
            val resized = state(launched).project!!.pages.single().images.single()
            assertEquals(image.x, resized.x, .001)
            assertEquals(image.y, resized.y, .001)
            assertEquals(image.width / image.height, resized.width / resized.height, .001)
            awaitPersisted(repo, id, resized)
            evidence.put("resized", geometry(resized))
            record("stylus-resize-persisted")
            imageBounds = findBounds(imageLabel)
            // Avoid the bottom-right resize overlay; this genuine click requests canvas input focus.
            stylus(imageBounds.left + imageBounds.width() * .2f, imageBounds.top + imageBounds.height() * .2f)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val nudged = resized.copy(x = resized.x + 1.0)
            waitFor { state(launched).project?.pages?.single()?.images?.single() == nudged }
            awaitPersisted(repo, id, nudged)
            chord(shift = false)
            waitFor { state(launched).project?.pages?.single()?.images?.single() == resized }
            awaitPersisted(repo, id, resized)
            chord(shift = true)
            waitFor { state(launched).project?.pages?.single()?.images?.single() == nudged }
            awaitPersisted(repo, id, nudged)
            evidence.put("nudgedAndRedone", geometry(nudged))
            record("keyboard-nudge-undo-redo-persisted")
            // TAB only moves focus; Undo is always reachable once there is undo history.
            var tabs = 0
            while (!focusedControl(undoLabel) && tabs < 80) {
                key(KeyEvent.KEYCODE_TAB); tabs++; delay(40)
            }
            check(focusedControl(undoLabel)) { "Real Tab traversal did not reach Undo" }
            evidence.put("tabCountBeforeEnter", tabs).put("focusedNodesBeforeEnter", JSONArray(windowNodes().filter { it.isFocused }.map { node ->
                JSONObject().put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
                    .put("bounds", Rect().also(node::getBoundsInScreen).flattenToString())
                    .put("descendantText", JSONArray(nodes(node).mapNotNull { it.text?.toString() }))
            }))
            evidence.put("geometryAfterTab", geometry(state(launched).project!!.pages.single().images.single()))
            record("focused-undo-before-autosave")
            assertEquals("Tab navigation without text edits must preserve exact image geometry", nudged, state(launched).project!!.pages.single().images.single())
            // Autosave (not a manual Save action) persists the nudge; wait for it to settle.
            waitFor { !state(launched).busy && state(launched).saveState == PdfSaveState.Saved }
            record("autosave-observed-saved")
            awaitPersisted(repo, id, nudged)
            launched.onActivity {
                assertSame(originalActivity, it); assertEquals(originalTask, it.taskId)
                assertEquals(originalPid, android.os.Process.myPid())
                assertFalse(it.isFinishing)
                check(it.receivedPointers.any { value -> value == "0:${InputDevice.SOURCE_STYLUS}:${MotionEvent.TOOL_TYPE_STYLUS}" })
                check(it.receivedKeys.any { value -> value.startsWith("0:${KeyEvent.KEYCODE_DPAD_RIGHT}:") && value.endsWith(":${InputDevice.SOURCE_KEYBOARD}") })
                evidence.put("keysReceived", JSONArray(it.receivedKeys))
                evidence.put("pointersReceived", JSONArray(it.receivedPointers))
            }
            assertEquals(originalHash, PdfProjectRepository.sha256(source))
            assertEquals(originalHash, PdfProjectRepository.sha256(repo.file(requireNotNull(fixtureHash))))
            assertEquals(tables, withContext(Dispatchers.IO) { tableInventory(db, id) })
            val during = withContext(Dispatchers.IO) { fileInventory(root) }
            files.forEach { (name, hash) -> assertEquals("Existing file changed: $name", hash, during[name]) }
            assertEquals(files.keys + "assets/${fixtureHash}", during.keys)
            evidence.put("tabCount", tabs).put("savedViaEnter", true)
            record("saved-before-reopen")
            launched.close()
            scenario = null
            val reopened = ActivityScenario.launch<PdfInputProbeActivity>(
                Intent(context, PdfInputProbeActivity::class.java).putExtra("fixtureUuid", uuid),
            )
            scenario = reopened
            waitFor {
                val current = state(reopened)
                current.project?.id == id && !current.busy && current.project?.pages?.single()?.images?.single() == nudged
            }
            findBounds(imageLabel)
            reopened.onActivity { assertNotSame(originalActivity, it) }
            awaitPersisted(repo, id, nudged)
            assertEquals(originalHash, PdfProjectRepository.sha256(source))
            assertEquals(originalHash, PdfProjectRepository.sha256(repo.file(requireNotNull(fixtureHash))))
            evidence.put("reopenedGeometry", geometry(state(reopened).project!!.pages.single().images.single()))
            record("input-save-reopen-accepted-before-cleanup")
        } catch (failure: Throwable) {
            primaryFailure = failure
            evidence.put("failure", failure.toString()).put("failedPhase", evidence.optString("phase"))
            scenario?.let { active ->
                runCatching { active.onActivity {
                    evidence.put("keysAtFailure", JSONArray(it.receivedKeys)).put("keyDispatchAtFailure", JSONArray(it.keyDispatchResults))
                        .put("busyAtFailure", it.observedState.busy).put("messageAtFailure", it.observedState.message)
                        .put("projectAtFailure", it.observedState.project?.id)
                } }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        finally {
            var cleanupFailure: Throwable? = null
            fun retain(error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            }
            var activityClosed = false
            try { scenario?.close(); activityClosed = true } catch (error: Throwable) { retain(error) }
            // Never delete assets while a failed Activity close may leave its VM using/writing them.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    check(activityClosed) { "Activity close failed; preserve the owned fixture" }
                    // Protect every current non-fixture asset, including unreferenced existing ones.
                    val protected = assets.listFiles().orEmpty().map { it.name }.toMutableSet()
                    fixtureHash?.let { hash ->
                        if ("assets/$hash" !in files) {
                            val asset = repo.file(hash)
                            if (asset.exists()) check(PdfProjectRepository.sha256(asset) == hash)
                            protected.remove(hash)
                        }
                    }
                    val row = db.projects().get(id)
                    if (row != null) check(row.name == "Input $uuid")
                    // Even a cancelled import with no row may have created our known content-addressed asset.
                    repo.delete(id, protected)
                    check(db.projects().get(id) == null)
                    assertEquals(tables, tableInventory(db))
                    assertEquals(files, fileInventory(root))
                    if (source.exists()) {
                        originalHash?.let { check(PdfProjectRepository.sha256(source) == it) }
                        check(source.delete())
                    }
                    evidence.put("originalPreservedUntilCleanup", originalHash != null)
                    evidence.put("tablesRestored", true).put("filesRestored", true)
                } catch (error: Throwable) { retain(error) }
                try { record(if (cleanupFailure == null) "cleanup-complete" else "cleanup-failed") }
                catch (error: Throwable) { retain(error) }
            }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }

    private fun state(scenario: ActivityScenario<PdfInputProbeActivity>): PdfStudioState {
        var result: PdfStudioState? = null
        scenario.onActivity { result = it.observedState }
        return requireNotNull(result)
    }
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(40)
    }
    private suspend fun awaitPersisted(repo: PdfProjectRepository, id: String, image: PdfImage) = withTimeout(15_000) {
        while (repo.load(id)?.pages?.single()?.images?.single() != image) delay(40)
    }
    private fun geometry(image: PdfImage) = JSONObject().put("x", image.x).put("y", image.y)
        .put("width", image.width).put("height", image.height)

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun windowNodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return nodes(automation.rootInActiveWindow)
    }
    private suspend fun findBounds(label: String): Rect {
        var found: Rect? = null
        waitFor {
            found = windowNodes().firstOrNull { it.contentDescription?.toString() == label && it.isVisibleToUser }
                ?.let { Rect().also(it::getBoundsInScreen) }?.takeIf { !it.isEmpty }
            found != null
        }
        return requireNotNull(found)
    }
    private fun focusedControl(label: String): Boolean = windowNodes().any { node ->
        // Icon-only controls expose their label as a content description rather than text.
        node.isFocused && node.isEnabled && node.isClickable &&
            nodes(node).any { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }
    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        fun send(action: Int) = check(automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(),
            action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
        try { send(KeyEvent.ACTION_DOWN) } finally { send(KeyEvent.ACTION_UP) }
    }
    private fun chord(shift: Boolean) {
        val now = SystemClock.uptimeMillis()
        val control = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val both = control or (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
        fun send(code: Int, action: Int, meta: Int) = check(automation.injectInputEvent(KeyEvent(now,
            SystemClock.uptimeMillis(), action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD,
            0, 0, InputDevice.SOURCE_KEYBOARD), true))
        try {
            send(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, control)
            if (shift) send(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, both)
            key(KeyEvent.KEYCODE_Z, both)
        } finally {
            try { if (shift) send(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, control) }
            finally { send(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0) }
        }
    }
    private fun stylus(x: Float, y: Float, endX: Float = x, endY: Float = y) {
        val down = SystemClock.uptimeMillis()
        var lastX = x; var lastY = y
        fun send(action: Int, px: Float, py: Float) {
            val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coordinates = MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                arrayOf(property), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            lastX = px; lastY = py
        }
        try {
            send(MotionEvent.ACTION_DOWN, x, y)
            if (x != endX || y != endY) for (step in 1..12) {
                SystemClock.sleep(18)
                send(MotionEvent.ACTION_MOVE, x + (endX - x) * step / 12, y + (endY - y) * step / 12)
            }
        } finally { send(MotionEvent.ACTION_UP, lastX, lastY) }
    }

    private suspend fun tableInventory(db: PdfProjectDatabase, excludeProject: String? = null): Map<String, List<String>> = db.withTransaction {
        listOf("projects", "export_jobs", "destination_grants", "import_receipts", "import_deliveries", "gallery_deliveries").associateWith { table ->
            db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (table == "projects" && excludeProject != null && cursor.getString(cursor.getColumnIndexOrThrow("id")) == excludeProject) continue
                        add(JSONArray().apply {
                            for (i in 0 until cursor.columnCount) put(when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                                Cursor.FIELD_TYPE_BLOB -> error("Unexpected PDF table blob")
                                else -> cursor.getString(i)
                            })
                        }.toString())
                    }
                }.sorted()
            }
        }
    }
    private fun fileInventory(root: File): Map<String, String> {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        check(files.sumOf { it.length() } <= 32L * 1024 * 1024) { "Existing PDF files exceed this bounded fixture budget" }
        return files.associate { it.relativeTo(root).invariantSeparatorsPath to PdfProjectRepository.sha256(it) }.toSortedMap()
    }
}
