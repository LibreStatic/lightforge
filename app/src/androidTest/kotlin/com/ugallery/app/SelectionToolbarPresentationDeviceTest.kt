package com.ugallery.app

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.app.UiAutomation
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import com.ugallery.core.selection.SelectionSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Real Photos selection toolbar in a LOCAL presentation configuration, not global system settings. */
class SelectionToolbarPresentationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
        require(UUID.fromString(it).toString() == it)
    }
    private val receipt = AtomicFile(File(context.filesDir, "selection-toolbar-$uuid.json"))
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relativePath = "Pictures/selection-toolbar-$uuid/"
    private val sources = mutableListOf<OwnedImage>()
    private lateinit var record: JSONObject
    private var scenario: ActivityScenario<MainActivity>? = null
    private var vm: GalleryViewModel? = null
    @Volatile private var observedRtl = false
    @Volatile private var observedFontScale = 0f

    @Test fun compactLargeRtlSelectionKeepsActionsAndClearReachable() {
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
            "Use the existing prepared acceptance permission state; this test never grants permission"
        }
        check(!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists())
        check(context.getDatabasePath(GalleryDatabaseFactory.DatabaseName).exists()) { "Prepared library required" }
        // Do not let opening a new production Activity acknowledge/reconcile an outstanding recovery.
        check(ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read() == null)
        for (name in listOf("collage-publications", "gif-publications", "motion-publications")) {
            val directory = File(context.noBackupFilesDir, name)
            check(!directory.exists() || directory.walkTopDown().none { it.isFile }) { "Existing recovery retained: $name" }
        }
        val baseline = persistentInventory()
        val modelsBefore = modelInventory()
        val operationalBefore = workManagerFiles()
        val originalPermissions = permissionsSnapshot()
        val originalConfiguration = Configuration(context.resources.configuration)
        record = JSONObject().put("uuid", uuid).put("package", context.packageName)
            .put("beforeState", JSONObject(baseline)).put("modelsBefore", modelsBefore)
            .put("workManagerBefore", operationalBefore).put("permissionsBefore", originalPermissions).put("phase", "before-activity")
            .put("cleanupComplete", false).put("profileBefore", profileMarker())
        persist()
        var failure: Throwable? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { activity -> vm = ViewModelProvider(activity)[GalleryViewModel::class.java] }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("timeline_grid").fetchSemanticsNodes().isNotEmpty() }
            check(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) { "Pre-existing dialog; no interaction" }
            compose.runOnIdle {
                check(vm!!.selectionCount.value == 0L && (vm!!.selection.value as SelectionSpec.Explicit).keys.isEmpty())
            }
            val startup = persistentInventory()
            record.put("startupState", JSONObject(startup)); persist()
            checkPersistentState(baseline, startup, "Startup")
            record.put("phase", "photos-ready-empty-selection"); persist()
            makeSource(0, Color.RED); makeSource(1, Color.BLUE)
            val keys = ownKeys()
            await("own-indexed") { keys.all { indexed(it) } }
            // Keep MainActivity, its real VM and injected PermissionCoordinator; replace only local rendering configuration.
            scenario!!.onActivity { activity ->
                activity.setContent {
                    val baseDensity = LocalDensity.current.density
                    val config = Configuration(originalConfiguration).apply {
                        fontScale = 2f; screenWidthDp = 360; screenHeightDp = 640; smallestScreenWidthDp = 360
                        orientation = Configuration.ORIENTATION_PORTRAIT
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                    }
                    CompositionLocalProvider(LocalConfiguration provides config,
                        LocalDensity provides Density(baseDensity, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                        val localRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                        val localFont = LocalDensity.current.fontScale
                        SideEffect { observedRtl = localRtl; observedFontScale = localFont }
                        UGalleryTheme(darkTheme = false, dynamicColor = false) {
                            Box(Modifier.requiredSize(360.dp, 640.dp).testTag("selection-presentation-viewport")) {
                                ProductionGalleryApp(vm!!, activity.permissionCoordinator)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.selection_count, 0)).assertDoesNotExist()
            record.put("initialZeroStatusAbsent", true); persist()
            for ((index, key) in keys.withIndex()) {
                val tag = "media_${key.volumeName}_${key.mediaStoreId}"
                compose.onNodeWithTag("timeline_grid").performScrollToNode(hasTestTag(tag))
                val tile = compose.onNodeWithTag(tag)
                tile.assertIsDisplayed()
                if (index == 0) tile.performTouchInput { longClick() } else tile.performTouchInput { click() }
                val expected = index + 1
                compose.waitUntil(10_000) { vm!!.selectionCount.value == expected.toLong() }
                checkSelectionAnnouncement(expected, requireTextLiveRegion = true)
            }
            compose.waitUntil(10_000) { vm!!.selectionCount.value == 2L }
            compose.runOnIdle { check((vm!!.selection.value as SelectionSpec.Explicit).keys == keys.toSet()) }
            val viewport = compose.onNodeWithTag("selection-presentation-viewport").fetchSemanticsNode().boundsInWindow
            check(viewport.width > 0 && viewport.height > 0)
            scenario!!.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                check(kotlin.math.abs(viewport.width / density - 360f) <= 1f)
                check(kotlin.math.abs(viewport.height / density - 640f) <= 1f)
                val location = IntArray(2); activity.window.decorView.getLocationOnScreen(location)
                check(viewport.left >= location[0] && viewport.top >= location[1] &&
                    viewport.right <= location[0] + activity.window.decorView.width &&
                    viewport.bottom <= location[1] + activity.window.decorView.height)
            }
            check(observedRtl && observedFontScale == 2f)
            record.put("viewport", rect(viewport)).put("observedRtl", observedRtl).put("observedFontScale", observedFontScale)
            val count = context.getString(R.string.selection_count, 2)
            checkText(count, viewport)
            val actions = listOf(R.string.selection_add_album, R.string.selection_share,
                R.string.selection_favorite, R.string.selection_trash).map(context::getString)
            val more = context.getString(com.ugallery.feature.viewer.R.string.viewer_more)
            for (label in actions + more) checkControl(compose.onNodeWithContentDescription(label), viewport, label)
            phase("toolbar-observed-before-menu")
            compose.onNodeWithContentDescription(more).performTouchInput { click() }
            val clearLabel = context.getString(R.string.selection_clear)
            val clear = compose.onNodeWithText(clearLabel)
            clear.performScrollTo()
            compose.waitForIdle()
            // Dropdown is a real popup window; its critical action must remain within the local viewport.
            checkControl(clear, viewport, clearLabel)
            checkText(clearLabel, viewport)
            phase("clear-visible-before-touch")
            clear.performTouchInput { click() }
            compose.waitUntil(10_000) { vm!!.selectionCount.value == 0L }
            compose.runOnIdle { check((vm!!.selection.value as SelectionSpec.Explicit).keys.isEmpty()) }
            compose.onNodeWithText(count).assertDoesNotExist()
            checkSelectionAnnouncement(0, requireTextLiveRegion = false)
            sources.forEach { check(read(it) == it.anchored) }
            phase("selection-cleared-originals-intact")
        } catch (caught: Throwable) {
            failure = caught
            record.put("failure", caught.javaClass.name).put("failureMessage", caught.message)
        } finally {
            var cleanupFailed = false
            fun step(label: String, action: () -> Unit) {
                try { action() } catch (caught: Throwable) {
                    cleanupFailed = true; record.put("cleanupComplete", false)
                    record.put("cleanupFailure-$label", caught.javaClass.name + ": " + caught.message)
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            step("own-selection") {
                scenario?.onActivity {
                    val selected = vm!!.selection.value as? SelectionSpec.Explicit
                    check(selected != null && selected.keys.all { key -> key in ownKeys() }) { "Other selection retained" }
                    if (selected.keys.isNotEmpty()) vm!!.clearSelection()
                }
            }
            for (source in sources) step("source-${source.index}") { deleteOwned(source) }
            step("index-cleanup") {
                scenario?.onActivity { vm!!.onForeground() }
                await("own-index-inaccessible") { ownKeys().none { indexed(it) } }
                record.put("indexCleanup", JSONArray(ownKeys().map { key ->
                    JSONObject().put("volume", key.volumeName).put("id", key.mediaStoreId)
                        .put("isAccessible", indexAccessibility(key) ?: JSONObject.NULL)
                }))
            }
            step("restore-configuration-and-close") {
                scenario?.onActivity { activity ->
                    activity.setContent {
                        UGalleryTheme { ProductionGalleryApp(vm!!, activity.permissionCoordinator) }
                    }
                }
                scenario?.close(); scenario = null
            }
            step("persistent-state") {
                val after = persistentInventory()
                val modelsAfter = modelInventory()
                record.put("afterState", JSONObject(after)).put("profileAfter", profileMarker())
                    .put("modelsAfter", modelsAfter).put("workManagerAfter", workManagerFiles())
                check(modelsAfter.toString() == modelsBefore.toString()) { "Model fixture changed; bytes preserved for review" }
                checkPersistentState(baseline, after, "Final")
                check(context.resources.configuration == originalConfiguration)
                record.put("permissionsAfter", permissionsSnapshot())
                check(permissionsSnapshot().toString() == originalPermissions.toString())
                check(!cleanupFailed)
                record.put("cleanupComplete", true)
            }
            record.put("phase", if (failure == null) "pass-cleaned" else "failed-preserved")
            step("receipt") { persist() }
        }
        failure?.let { throw it }
        check(record.getBoolean("cleanupComplete"))
    }

    /** Checks the Compose contract and its real Android accessibility bridge, not audible speech. */
    private fun checkSelectionAnnouncement(count: Int, requireTextLiveRegion: Boolean) {
        val text = context.getString(R.string.selection_count, count)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val label = compose.onNodeWithText(text, useUnmergedTree = true)
        label.assertIsDisplayed()
        val liveRegion = label.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        val observation = JSONObject().put("count", count).put("text", text)
            .put("composeTextLiveRegion", liveRegion?.toString() ?: "absent")
            .put("uptimeMillis", SystemClock.uptimeMillis())
        record.put("announcement-$count", observation); persist()
        if (requireTextLiveRegion) check(liveRegion == LiveRegionMode.Polite) { "Count text is not a polite live region: $count" }
        // Snackbar's live region can be a containing Android node, rather than the Text itself.
        // Do not alter accessibility services, move their focus or perform synthetic announcements.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var matched = false
        val deadline = SystemClock.uptimeMillis() + 3_000
        do {
            val matches = JSONArray()
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    var visited = 0
                    fun visit(node: AccessibilityNodeInfo, inherited: List<Int>, depth: Int) {
                        check(depth <= 32 && ++visited <= 2_000) { "Accessibility observer tree limit" }
                        val regions = inherited + node.liveRegion
                        if (node.packageName?.toString() == context.packageName && node.text?.toString() == text) {
                            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                            val polite = View.ACCESSIBILITY_LIVE_REGION_POLITE in regions
                            matches.put(JSONObject().put("text", node.text.toString()).put("package", node.packageName.toString())
                                .put("visible", node.isVisibleToUser).put("liveRegion", node.liveRegion)
                                .put("ancestorAndNodeLiveRegions", JSONArray(regions)).put("polite", polite)
                                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                            if (node.isVisibleToUser && !bounds.isEmpty && polite) matched = true
                        }
                        for (index in 0 until node.childCount) {
                            val child = node.getChild(index) ?: continue
                            try { visit(child, regions, depth + 1) }
                            finally { @Suppress("DEPRECATION") child.recycle() }
                        }
                    }
                    visit(root, emptyList(), 0)
                } finally { @Suppress("DEPRECATION") root.recycle() }
            }
            observation.put("androidMatches", matches).put("androidPoliteVisible", matched); persist()
            if (!matched) { compose.waitForIdle(); SystemClock.sleep(50) }
        } while (!matched && SystemClock.uptimeMillis() < deadline)
        check(matched) { "Android bridge has no visible polite selection status: $count" }
        val transitions = record.optJSONArray("announcementOrder") ?: JSONArray().also { record.put("announcementOrder", it) }
        transitions.put(count); persist()
    }

    private fun permissionsSnapshot() = JSONObject().apply {
        for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE)) put(permission, context.checkSelfPermission(permission))
    }
    private fun ownKeys() = sources.mapNotNull { it.uri?.let { uri -> MediaKey("external_primary", ContentUris.parseId(uri)) } }
    private fun indexed(key: MediaKey): Boolean = indexAccessibility(key) == 1
    private fun indexAccessibility(key: MediaKey): Int? = SQLiteDatabase.openDatabase(
        context.getDatabasePath(GalleryDatabaseFactory.DatabaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT isAccessible FROM media_items WHERE volumeName=? AND mediaStoreId=?", arrayOf(key.volumeName, key.mediaStoreId.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else null }
    }
    private fun checkControl(node: SemanticsNodeInteraction, viewport: Rect, label: String) {
        node.assertIsDisplayed().assertIsEnabled()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        record.put("control-$label", JSONObject().put("bounds", rect(bounds)).put("viewport", rect(viewport))); persist()
        check(inside(bounds, viewport)) { "Control clipped: $label $bounds / $viewport" }
    }
    private fun inside(box: Rect, outer: Rect) = box.width > 0 && box.height > 0 && box.left >= outer.left - 1f &&
        box.top >= outer.top - 1f && box.right <= outer.right + 1f && box.bottom <= outer.bottom + 1f
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun checkText(label: String, viewport: Rect) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        val bounds = node.fetchSemanticsNode().boundsInWindow
        record.put("text-bounds-$label", JSONObject().put("bounds", rect(bounds)).put("viewport", rect(viewport))); persist()
        check(inside(bounds, viewport))
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> check(action(layouts)) }
        val layout = layouts.single()
        record.put("text-$label", JSONObject().put("bounds", rect(bounds)).put("fontScale", layout.layoutInput.density.fontScale)
            .put("layoutWidth", layout.size.width).put("layoutHeight", layout.size.height)
            .put("paragraphWidth", layout.multiParagraph.width).put("overflowWidth", layout.didOverflowWidth))
        persist()
        check(layout.layoutInput.density.fontScale == 2f && layout.lineCount > 0)
        check(layout.getLineEnd(layout.lineCount - 1) == label.length)
        val local = Rect(0f, 0f, minOf(bounds.width, layout.size.width.toFloat()), minOf(bounds.height, layout.size.height.toFloat()))
        fun fits(r: Rect) = r.left >= -1f && r.top >= -1f && r.right <= local.right + 1f && r.bottom <= local.bottom + 1f
        for (line in 0 until layout.lineCount) {
            check(!layout.isLineEllipsized(line))
            check(fits(Rect(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))))
        }
        for (index in label.indices) check(fits(layout.getBoundingBox(index)))
    }
    private val operationalWorkManagerNames = setOf("androidx.work.workdb", "androidx.work.workdb-wal",
        "androidx.work.workdb-shm", "androidx.work.workdb-journal")
    private fun isWorkManagerFile(file: File) = file.parentFile == context.noBackupFilesDir &&
        file.name in operationalWorkManagerNames
    private fun isModelFile(file: File) = file.toPath().normalize().startsWith(File(context.noBackupFilesDir, "models").toPath().normalize())
    private fun persistentInventory(): Map<String, String> = buildMap {
        // Startup check covers prefs/recovery/credentials, not operational WorkManager SQLite bytes.
        // Models receive a streaming before/final audit separately, avoiding a third large hash on startup.
        for (root in listOf(File(context.applicationInfo.dataDir, "shared_prefs"), context.noBackupFilesDir, File(context.filesDir, "datastore"))) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                if (isWorkManagerFile(file) || isModelFile(file)) return@forEach
                val path = file.relativeTo(File(context.applicationInfo.dataDir)).path
                val snapshot = streamingIdentity(file)
                if (path == galleryPreferencesPath) {
                    check(file.length() <= 1_048_576L) { "Preferences snapshot exceeds 1 MiB" }
                    val bytes = file.readBytes()
                    check(sha(bytes) == snapshot.getString("sha256") && bytes.size.toLong() == snapshot.getLong("bytes"))
                    check(streamingIdentity(file).toString() == snapshot.toString()) { "Preferences changed during semantic snapshot" }
                    // DataStore is an implementation dependency of core.preferences. Read its runtime
                    // serializer without adding or widening production dependencies.
                    val preferences = runBlocking { suspendCoroutine<Any> { continuation ->
                        try {
                            val type = Class.forName("androidx.datastore.preferences.core.PreferencesFileSerializer")
                            val serializer = type.getField("INSTANCE").get(null)
                            val result = type.getMethod("readFrom", java.io.InputStream::class.java,
                                kotlin.coroutines.Continuation::class.java).invoke(serializer, ByteArrayInputStream(bytes), continuation)
                            if (result !== COROUTINE_SUSPENDED) continuation.resume(requireNotNull(result))
                        } catch (failure: Throwable) { continuation.resumeWithException(failure) }
                    } }
                    val raw = preferences.javaClass.getMethod("asMap").invoke(preferences) as Map<*, *>
                    val entries = raw.entries.associate { entry ->
                        val key = requireNotNull(entry.key)
                        (key.javaClass.getMethod("getName").invoke(key) as String) to requireNotNull(entry.value)
                    }
                    val typed = JSONObject()
                    entries.entries.sortedBy { it.key }.forEach { (key, value) ->
                        val encoded = when (value) {
                            is Boolean -> "Boolean:$value"
                            is Int -> "Int:$value"
                            is Long -> "Long:$value"
                            is Float -> "FloatBits:${value.toRawBits()}"
                            is Double -> "DoubleBits:${value.toRawBits()}"
                            is String -> "String:$value"
                            is Set<*> -> {
                                check(value.all { it is String })
                                "StringSet:" + JSONArray(value.map { it as String }.sorted()).toString()
                            }
                            is ByteArray -> "Bytes:" + value.joinToString("") { "%02x".format(it) }
                            else -> error("Unknown preferences type: ${key}/${value.javaClass.name}")
                        }
                        typed.put(key, encoded)
                    }
                    snapshot.put("typedPreferences", typed)
                }
                put(path, snapshot.toString())
            }
        }
    }
    private val galleryPreferencesPath = "files/datastore/gallery-settings.preferences_pb"
    private fun checkPersistentState(before: Map<String, String>, after: Map<String, String>, phase: String) {
        check(before.keys == after.keys) { "$phase persistent file inventory changed; preserved for review" }
        for (path in before.keys) {
            val a = JSONObject(before.getValue(path))
            val b = JSONObject(after.getValue(path))
            if (path == galleryPreferencesPath) {
                val first = a.getJSONObject("typedPreferences")
                val last = b.getJSONObject("typedPreferences")
                fun keys(value: JSONObject) = value.keys().asSequence().toSet() - "portable.revision"
                check(keys(first) == keys(last)) { "$phase preferences key set changed" }
                for (key in keys(first)) check(first.getString(key) == last.getString(key)) {
                    "$phase preference changed: $key; never overwritten"
                }
                fun revision(value: JSONObject): Long {
                    if (!value.has("portable.revision")) return 0L
                    val encoded = value.getString("portable.revision")
                    check(encoded.startsWith("Long:")) { "Unexpected revision type" }
                    return encoded.removePrefix("Long:").toLong().also { check(it >= 0) }
                }
                // PagedPhotosTimeline's initial columns callback calls update(), which advances
                // this portable-import CAS revision even when every user setting stays identical.
                // No preference (including columns), receipt, unknown key or revision rollback is allowed.
                val initialRevision = revision(first)
                val finalRevision = revision(last)
                record.put("preferencesRevision-$phase", JSONObject().put("before", initialRevision).put("after", finalRevision))
                persist()
                check(finalRevision >= initialRevision) { "$phase preferences revision decreased" }
            } else {
                // Atomic replacement of identical bytes is not a preference change. Identity is
                // still checked inside streamingIdentity to reject an unstable individual read.
                check(a.getLong("bytes") == b.getLong("bytes") && a.getString("sha256") == b.getString("sha256")) {
                    "$phase persistent bytes changed: $path; preserved for review, never overwritten"
                }
            }
        }
    }
    private fun streamingIdentity(file: File): JSONObject {
        val before = Os.stat(file.path)
        check(OsConstants.S_ISREG(before.st_mode))
        // Bound each file, not all no_backup together; no whole-model byte array allocation.
        check(before.st_size in 0..1_073_741_824L) { "Individual fixture file exceeds 1 GiB: ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                count += n; check(count <= before.st_size)
                digest.update(buffer, 0, n)
            }
        }
        val after = Os.stat(file.path)
        check(count == before.st_size && before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
            before.st_size == after.st_size && before.st_mtime == after.st_mtime && before.st_ctime == after.st_ctime) {
            "File changed during snapshot: ${file.name}"
        }
        return JSONObject().put("bytes", count).put("device", before.st_dev).put("inode", before.st_ino)
            .put("mtime", before.st_mtime).put("ctime", before.st_ctime)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
    }
    private fun modelInventory(): JSONObject = JSONObject().apply {
        val directory = File(context.noBackupFilesDir, "models")
        if (directory.exists()) directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            put(file.relativeTo(File(context.applicationInfo.dataDir)).path, streamingIdentity(file))
        }
    }
    private fun workManagerFiles(): JSONArray = JSONArray(operationalWorkManagerNames.sorted().map { name ->
        val file = File(context.noBackupFilesDir, name)
        JSONObject().put("path", file.relativeTo(File(context.applicationInfo.dataDir)).path)
            .put("exists", file.exists()).apply { if (file.exists()) put("bytes", file.length()) }
    })
    private fun profileMarker(): JSONObject {
        val file = File(context.filesDir, "profileInstalled")
        return JSONObject().put("exists", file.exists()).apply {
            if (file.exists()) { check(file.length() <= 1024); put("bytes", file.length()); put("sha256", sha(file.readBytes())) }
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { "Timeout $label" }; SystemClock.sleep(75) }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun phase(value: String) { record.put("phase", value); persist() }
    private fun persist() {
        val bytes = record.toString().toByteArray()
        val output = receipt.startWrite()
        try { output.write(bytes); output.fd.sync(); receipt.finishWrite(output) }
        catch (failure: Throwable) { receipt.failWrite(output); throw failure }
        check(receipt.openRead().use { it.readBytes() }.contentEquals(bytes))
        val fd = Os.open(receipt.baseFile.parentFile!!.path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }

    private class OwnedImage(val index: Int, val name: String, val bytes: ByteArray) {
        var uri: Uri? = null
        var initial: JSONObject? = null
        var anchored: String? = null
    }

    private fun makeSource(index: Int, color: Int): OwnedImage {
        val bitmap = Bitmap.createBitmap(48, 40, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(color)
            // UUID-specific edge pixels preserve a large uniform decoded center.
            uuid.toByteArray().forEachIndexed { x, value -> bitmap.setPixel(x, 0, Color.rgb(value.toInt() and 255, index, 37)) }
            ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
        val source = OwnedImage(index, "selection-toolbar-$uuid-$index.png", bytes)
        // Track this source BEFORE insertion, so every later exception reaches finally cleanup.
        sources.add(source)
        val state = JSONObject().put("name", source.name).put("path", relativePath)
            .put("hash", sha(bytes)).put("size", bytes.size).put("phase", "before-insert")
        record.put("source-$index", state); persist()
        source.uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis() + index)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        check(source.uri == ContentUris.withAppendedId(collection, ContentUris.parseId(source.uri!!)))
        state.put("uri", source.uri.toString()).put("phase", "before-write"); persist()
        source.initial = metadata(source)
        state.put("initial", source.initial); persist()
        context.contentResolver.openOutputStream(source.uri!!, "w")!!.use { it.write(bytes) }
        val pending = JSONObject(read(source))
        check(pending.getString("sha") == sha(bytes) && pending.getLong("pending") == 1L)
        state.put("pending", pending).put("phase", "before-publish"); persist()
        check(context.contentResolver.update(source.uri!!, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, cas(pending)) == 1)
        source.anchored = read(source)
        val published = JSONObject(source.anchored!!)
        check(published.getString("sha") == sha(bytes) && published.getLong("pending") == 0L)
        state.put("published", published).put("phase", "published"); persist()
        return source
    }

    private fun includeStates() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    private fun visibleIds(): List<Long> = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), includeStates(), null)!!.use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0)) }.sorted()
    }

    private fun metadata(source: OwnedImage): JSONObject {
        val uri = requireNotNull(source.uri)
        val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path",
            "generation_added", "generation_modified", "is_pending", "is_trashed", "_size")
        return context.contentResolver.query(uri, columns, includeStates(), null)!!.use { c ->
            check(c.moveToFirst())
            JSONObject().put("id", c.getLong(0)).put("owner", c.getString(1)).put("name", c.getString(2))
                .put("path", c.getString(3)).put("added", c.getLong(4)).put("modified", c.getLong(5))
                .put("pending", c.getLong(6)).put("trashed", c.getLong(7))
                .put("size", if (c.isNull(8)) -1L else c.getLong(8)).also { check(!c.moveToNext()) }
        }
    }

    /** Exact URI, owner, UUID name/path and metadata before/after bytes. Never scans to adopt an insert. */
    private fun read(source: OwnedImage): String {
        val uri = requireNotNull(source.uri)
        val before = metadata(source)
        check(before.getLong("id") == ContentUris.parseId(uri) && before.getString("owner") == context.packageName &&
            before.getString("name") == source.name && before.getString("path") == relativePath)
        check(before.getLong("trashed") == 0L)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= source.bytes.size) { "Changed source is larger; retained" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(before.toString() == metadata(source).toString())
        check(before.getLong("size") == bytes.size.toLong() ||
            (before.getLong("pending") == 1L && before.getLong("size") == -1L))
        check(bytes.contentEquals(source.bytes.copyOf(bytes.size))) { "Changed source bytes retained" }
        return before.put("sha", sha(bytes)).put("actualSize", bytes.size).toString()
    }

    private fun cas(row: JSONObject) = includeStates().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
            "_id=? AND owner_package_name=? AND _display_name=? AND relative_path=? AND generation_added=? AND " +
                "generation_modified=? AND is_pending=? AND is_trashed=? AND " +
                if (row.getLong("size") < 0) "_size IS NULL" else "_size=?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            arrayOf("id", "owner", "name", "path", "added", "modified", "pending", "trashed")
                .map { row.get(it).toString() }.toTypedArray() +
                if (row.getLong("size") < 0) emptyArray() else arrayOf(row.getLong("size").toString()))
    }

    private fun deleteOwned(source: OwnedImage) {
        val uri = source.uri ?: return // Unknown insert outcome is retained, never adopted by name.
        val current = read(source)
        if (source.anchored != null) check(current == source.anchored)
        else {
            val row = JSONObject(current)
            source.initial?.let { check(row.getLong("added") == it.getLong("added")) }
            check(row.getLong("pending") == 1L || row.getString("sha") == sha(source.bytes))
        }
        record.getJSONObject("source-${source.index}").put("cleanupSnapshot", JSONObject(current)); persist()
        check(context.contentResolver.delete(uri, cas(JSONObject(current))) == 1)
        context.contentResolver.query(uri, arrayOf("_id"), includeStates(), null)!!.use { check(!it.moveToFirst()) }
    }

}
