package com.librestatic.lightforge.feature.widget

import android.Manifest
import android.app.UiAutomation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Real AppWidget service, broadcasts, production decoder/selector and touch PendingIntent. */
@RunWith(AndroidJUnit4::class)
class GalleryWidgetHostDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val manager = AppWidgetManager.getInstance(context)
    private val provider = ComponentName(context, GalleryWidgetProvider::class.java)
    private val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
        require(UUID.fromString(it).toString() == it)
    }
    // Runner verifies exact hostId absence in dumpsys appwidget before this UUID is launched.
    // AppWidgetHost.appWidgetIds alone cannot distinguish an absent host from a pre-existing empty host.
    private val hostInventorySha = requireNotNull(InstrumentationRegistry.getArguments().getString("hostInventorySha256")).also {
        require(it.matches(Regex("[0-9a-f]{64}")))
    }
    private val hostId = (UUID.fromString(uuid).hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    private val receipt = AtomicFile(File(context.filesDir, "widget-host-$uuid.json"))
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relativePath = "Pictures/widget-host-$uuid/"
    private val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/lightforge_widget.xml")
    private var activity: GalleryWidgetHostProbeActivity? = null
    private var widgetId: Int? = null
    private val sources = mutableListOf<OwnedImage>()
    private lateinit var record: JSONObject

    @Test fun touchAdvancesOwnedPhotoAndResizeDeliversThroughRealHost() {
        check(Build.VERSION.SDK_INT == 35)
        check(context.packageName == "com.librestatic.lightforge.feature.widget.test")
        check(instrumentation.context.packageName == context.packageName)
        noMediaGrant()
        requireNoAdoptedIdentity()
        check(!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists())
        check(manager.getAppWidgetIds(provider).isEmpty())
        check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
        check(!prefsFile.exists() && !File(prefsFile.path + ".bak").exists())
        check(context.getSharedPreferences("lightforge_widget", Context.MODE_PRIVATE).all.isEmpty())
        val beforeMedia = visibleIds()
        check(beforeMedia.isEmpty()) { "Existing accessible fixture media retained; no adoption" }
        val beforeFiles = inventory()
        record = JSONObject().put("uuid", uuid).put("package", context.packageName)
            .put("hostId", hostId).put("hostInventorySha256", hostInventorySha).put("beforeMedia", JSONArray(beforeMedia))
            .put("beforeWidgets", JSONArray()).put("beforeFiles", JSONObject(beforeFiles))
            .put("phase", "before-sources-or-binding").put("cleanupComplete", false)
        persist()
        var failure: Throwable? = null
        try {
            makeSource(0, Color.RED)
            makeSource(1, Color.BLUE)
            check(visibleIds().toSet() == sources.map { ContentUris.parseId(it.uri!!) }.toSet())
            phase("before-host-start")
            activity = instrumentation.startActivitySync(Intent(context, GalleryWidgetHostProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fixtureUuid", uuid).putExtra("hostId", hostId)) as GalleryWidgetHostProbeActivity
            widgetId = onMain { requireNotNull(activity).host.allocateAppWidgetId() }
            record.put("widgetId", widgetId)
            phase("before-bind")
            try {
                automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
                check(manager.bindAppWidgetIdIfAllowed(widgetId!!, provider)) { "Exact own widget binding denied" }
            } finally {
                automation.dropShellPermissionIdentity()
            }
            requireNoAdoptedIdentity()
            check(manager.getAppWidgetIds(provider).contentEquals(intArrayOf(widgetId!!)))
            val info = requireNotNull(manager.getAppWidgetInfo(widgetId!!))
            check(info.provider == provider)
            onMain { requireNotNull(activity).mount(widgetId!!, info) }
            await("initial-decoded-delivery") { sample()?.let { colorOf(it.pixel) != null && it.clickable } == true }
            awaitIdleWorker("before-touch")
            instrumentation.waitForIdleSync()
            val first = requireNotNull(sample())
            check(first.description == context.getString(R.string.widget_content_description))
            check(first.width > 0 && first.height > 0 && first.imageInsideHost)
            check(colorOf(first.pixel) != null)
            record.put("first", first.json())
            val initialIndex = context.getSharedPreferences("lightforge_widget", Context.MODE_PRIVATE).getInt("photo_index", -1)
            phase("before-real-touch")
            touch(first.centerX, first.centerY)
            await("touch-next-photo") {
                sample()?.let { it.deliveries > first.deliveries && colorOf(it.pixel) != null &&
                    colorOf(it.pixel) != colorOf(first.pixel) } == true
            }
            awaitIdleWorker("after-touch")
            instrumentation.waitForIdleSync()
            val second = requireNotNull(sample())
            val nextIndex = context.getSharedPreferences("lightforge_widget", Context.MODE_PRIVATE).getInt("photo_index", -1)
            check(initialIndex in 0..1 && nextIndex == (initialIndex + 1) % 2)
            record.put("second", second.json()).put("indices", JSONArray(listOf(initialIndex, nextIndex)))
            phase("before-real-options-resize")
            val oldOptions = manager.getAppWidgetOptions(widgetId!!)
            record.put("optionsBefore", options(oldOptions))
            onMain { requireNotNull(activity).resize() }
            await("resize-new-delivery") {
                val now = manager.getAppWidgetOptions(widgetId!!)
                sample()?.let { it.deliveries > second.deliveries && it.width > second.width &&
                    it.height > second.height && colorOf(it.pixel) != null &&
                    now.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > oldOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) &&
                    now.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) > oldOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) } == true
            }
            awaitIdleWorker("after-resize")
            val resized = requireNotNull(sample())
            check(resized.clickable && resized.description == first.description && resized.imageInsideHost)
            record.put("resized", resized.json()).put("optionsAfter", options(manager.getAppWidgetOptions(widgetId!!)))
            sources.forEach { check(read(it) == it.anchored) }
            phase("contract-observed")
        } catch (caught: Throwable) {
            failure = caught
            record.put("failure", caught.javaClass.name).put("failureMessage", caught.message)
        } finally {
            var cleanupFailed = false
            fun cleanupStep(label: String, action: () -> Unit) {
                try { action() } catch (caught: Throwable) {
                    cleanupFailed = true
                    record.put("cleanupComplete", false)
                    record.put("cleanupFailure-$label", caught.javaClass.name + ": " + caught.message)
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            cleanupStep("shell-identity") {
                automation.dropShellPermissionIdentity()
                requireNoAdoptedIdentity()
            }
            cleanupStep("delete-widget") {
                widgetId?.let { id ->
                    val host = AppWidgetHost(context, hostId)
                    check(id in host.appWidgetIds)
                    val bound = manager.getAppWidgetInfo(id)
                    check(bound == null || bound.provider == provider)
                    phase("before-own-widget-delete")
                    host.deleteAppWidgetId(id)
                    check(id !in host.appWidgetIds && manager.getAppWidgetInfo(id) == null)
                }
                check(manager.getAppWidgetIds(provider).isEmpty())
                check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
            }
            cleanupStep("host-activity") {
                activity?.let { current -> onMain { current.stopHost(); current.finish() } }
                instrumentation.waitForIdleSync()
            }
            cleanupStep("delete-own-host") {
                val host = AppWidgetHost(context, hostId)
                check(host.appWidgetIds.isEmpty())
                phase("before-own-host-delete")
                host.deleteHost()
                record.put("hostDeleteReturned", true)
                check(host.appWidgetIds.isEmpty())
                persist()
            }
            var quiescent = false
            cleanupStep("worker-quiescence") {
                check(manager.getAppWidgetIds(provider).isEmpty())
                awaitIdleWorker("cleanup")
                quiescent = true
            }
            // Do not remove files/preferences while a real in-flight selector can still write them.
            if (quiescent) {
                for (source in sources) cleanupStep("source-${source.index}") { deleteOwned(source) }
                cleanupStep("preferences") {
                    check(context.getSharedPreferences("lightforge_widget", Context.MODE_PRIVATE).edit().commit())
                    if (prefsFile.exists() || File(prefsFile.path + ".bak").exists())
                        check(context.deleteSharedPreferences("lightforge_widget"))
                    check(!prefsFile.exists() && !File(prefsFile.path + ".bak").exists())
                }
            }
            cleanupStep("inventory") {
                val afterMedia = visibleIds()
                val afterFiles = inventory()
                record.put("afterMedia", JSONArray(afterMedia)).put("afterFiles", JSONObject(afterFiles))
                    .put("afterWidgets", JSONArray(manager.getAppWidgetIds(provider).toList()))
                check(afterMedia == beforeMedia && afterFiles == beforeFiles)
                check(manager.getAppWidgetIds(provider).isEmpty())
                noMediaGrant()
                check(quiescent && !cleanupFailed)
                record.put("cleanupComplete", true)
            }
            record.put("phase", if (failure == null) "pass-cleaned" else "failed-preserved")
            cleanupStep("receipt") { persist() }
        }
        failure?.let { throw it }
        check(record.getBoolean("cleanupComplete"))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "WIDGET_HOST $uuid PASS touch-next/resize real-host cleanup-complete\n")
        })
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
        val source = OwnedImage(index, "widget-host-$uuid-$index.png", bytes)
        // Track this source BEFORE insertion, so every later exception reaches finally cleanup.
        sources.add(source)
        val state = JSONObject().put("name", source.name).put("path", relativePath)
            .put("hash", sha(bytes)).put("size", bytes.size).put("phase", "before-insert")
        record.put("source-$index", state); persist()
        source.uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
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

    private fun requireNoAdoptedIdentity() {
        // Public on the runtime test API but absent from public compile stubs. Never infer empty
        // identity from a failed observer, and never replace an already adopted caller identity.
        val value = try {
            UiAutomation::class.java.getMethod("getAdoptedShellPermissions").invoke(automation)
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("Shell identity observer unavailable before fixture mutation", failure)
        }
        check(value is Set<*> && value.isEmpty()) { "Shell identity observer is not empty: $value" }
    }

    private fun noMediaGrant() {
        for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.READ_EXTERNAL_STORAGE))
            check(context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
    }

    private fun inventory(): Map<String, String> = buildMap {
        var bytes = 0L
        for (root in listOf(context.filesDir, prefsFile.parentFile!!)) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                if (file == receipt.baseFile || file.path == receipt.baseFile.path + ".bak") return@forEach
                bytes += file.length(); check(bytes <= 64L * 1024 * 1024) { "Pre-existing inventory too large" }
                put(file.relativeTo(File(context.applicationInfo.dataDir)).path, sha(file.readBytes()))
            }
        }
    }

    private fun awaitIdleWorker(label: String) {
        // Read-only observer of the ACTUAL dispatch state, under its own production lock.
        // Reflection mismatch fails the observer; it never closes/substitutes the singleton.
        val field = GalleryWidgetProvider::class.java.getDeclaredField("updates").apply { isAccessible = true }
        val dispatcher = field.get(null)
        fun field(name: String) = dispatcher.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val lock = requireNotNull(field("lock").get(dispatcher))
        val draining = field("draining"); val active = field("active"); val pending = field("pending")
        var observed = ""
        try {
            await("worker-idle-$label") {
                synchronized(lock) {
                    val d = draining.getBoolean(dispatcher)
                    val a = active.get(dispatcher) != null
                    val p = pending.get(dispatcher) != null
                    observed = "draining=$d,active=$a,pending=$p"
                    !d && !a && !p
                }
            }
        } finally { record.put("worker-$label", observed); persist() }
    }

    private fun sample() = onMain { activity?.sample() }
    private fun colorOf(pixel: Int): String? = when {
        Color.red(pixel) > 220 && Color.blue(pixel) < 35 && Color.green(pixel) < 35 -> "red"
        Color.blue(pixel) > 220 && Color.red(pixel) < 35 && Color.green(pixel) < 35 -> "blue"
        else -> null
    }
    private fun GalleryWidgetHostProbeActivity.Sample.json() = JSONObject()
        .put("deliveries", deliveries).put("pixel", pixel).put("color", colorOf(pixel))
        .put("width", width).put("height", height).put("centerX", centerX).put("centerY", centerY)
        .put("clickable", clickable).put("description", description).put("imageInsideHost", imageInsideHost)
    private fun options(bundle: Bundle) = JSONObject().put("minWidth", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
        .put("minHeight", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        .put("maxWidth", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
        .put("maxHeight", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    private fun touch(x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; check(automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            SystemClock.sleep(60)
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timeout: $label" }
            SystemClock.sleep(40)
        }
    }
    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
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
}
